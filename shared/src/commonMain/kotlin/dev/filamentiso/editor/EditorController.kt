package dev.filamentiso.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.filamentiso.Bounds
import dev.filamentiso.IsoCamera
import dev.filamentiso.IsoCameraSetup
import dev.filamentiso.IsoSceneConfig
import dev.filamentiso.Rgb
import dev.filamentiso.Vec3
import dev.filamentiso.geometry.GlbWriter
import dev.filamentiso.geometry.OverlayBuilder
import dev.filamentiso.geometry.OverlayState
import dev.filamentiso.geometry.ShellBuilder
import dev.filamentiso.model.BuiltinCatalog
import dev.filamentiso.model.Catalog
import dev.filamentiso.model.CatalogItem
import dev.filamentiso.model.Edge
import dev.filamentiso.model.FurnitureItem
import dev.filamentiso.model.GridPoint
import dev.filamentiso.model.House
import dev.filamentiso.model.Level
import dev.filamentiso.model.OpeningType
import dev.filamentiso.model.cells
import dev.filamentiso.model.newId
import dev.filamentiso.scene.ModelSource
import dev.filamentiso.scene.SceneContent
import dev.filamentiso.scene.SceneGesture
import dev.filamentiso.scene.SceneModel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

enum class Tool(val label: String, val hint: String) {
    VIEW("View", "Drag to rotate · pinch to zoom · two fingers to pan"),
    ROOM("Room", "Drag on the grid to draw a room"),
    WALL("Wall", "Drag along grid lines to add a wall"),
    DOOR("Door", "Tap a wall to add / remove a door"),
    WINDOW("Window", "Tap a wall to add / remove a window"),
    FURNITURE("Furniture", "Pick an item, then tap or drag inside a room"),
    SELECT("Select", "Tap to select · drag furniture to move it"),
    ERASE("Erase", "Tap furniture, a wall or a room to delete it"),
}

sealed interface Selection {
    data class Furniture(val id: String) : Selection
    data class RoomSel(val id: String) : Selection
}

/** The placement preview: drawn green when it fits, red when it doesn't. */
data class Ghost(val item: FurnitureItem, val valid: Boolean, val hidesId: String? = null)

/**
 * All editor behaviour, in common code. The platform view only reports raw gestures; this
 * class turns them into grid coordinates (via [IsoCamera.screenToPlane]) and Level edits.
 * State is Compose state, so the UI recomposes when it changes.
 */
class EditorController(
    initial: House,
    val catalog: Catalog,
    private val onSave: (House) -> Unit,
) {
    private val history = History(initial.level)

    var house by mutableStateOf(initial); private set
    val level: Level get() = house.level
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set

    /** Empty basements open straight into drawing rooms. */
    var tool by mutableStateOf(if (initial.level.rooms.isEmpty()) Tool.ROOM else Tool.VIEW); private set
    var catalogId by mutableStateOf(catalog.items.firstOrNull()?.id)
    var placeTurns by mutableStateOf(0); private set
    var selection by mutableStateOf<Selection?>(null); private set
    var planView by mutableStateOf(false); private set
    var cutaway by mutableStateOf(true); private set
    var yaw by mutableStateOf(45f); private set
    var zoom by mutableStateOf(1f); private set
    var pan by mutableStateOf(Vec3(0f, 0f, 0f)); private set
    var message by mutableStateOf<String?>(null)

    private var overlay by mutableStateOf(OverlayState())
    var ghost by mutableStateOf<Ghost?>(null); private set

    private var viewW = 1
    private var viewH = 1

    // ------------------------------------------------------------------ view

    fun setViewport(w: Int, h: Int) { viewW = w.coerceAtLeast(1); viewH = h.coerceAtLeast(1) }

    /** What the camera frames: the rooms (plus a margin) or the whole plot when empty. Stable while editing. */
    var fitBounds by mutableStateOf(computeFit(initial.level)); private set

    private fun computeFit(l: Level): Bounds {
        val cells = l.rooms.flatMap { it.cells }
        if (cells.isEmpty()) return plotBoundsOf(l)
        val cs = l.cellSize
        val x0 = (cells.minOf { it.x } - 1) * cs; val x1 = (cells.maxOf { it.x } + 2) * cs
        val z0 = (cells.minOf { it.z } - 1) * cs; val z1 = (cells.maxOf { it.z } + 2) * cs
        val top = l.wallHeight; val bottom = -ShellBuilder.SLAB_DEPTH
        return Bounds(
            Vec3((x0 + x1) / 2, (top + bottom) / 2, (z0 + z1) / 2),
            Vec3((x1 - x0) / 2, (top - bottom) / 2, (z1 - z0) / 2),
        )
    }

    private fun plotBoundsOf(l: Level): Bounds {
        val top = l.wallHeight; val bottom = -ShellBuilder.SLAB_DEPTH
        return Bounds(
            Vec3(l.widthMeters / 2, (top + bottom) / 2, l.depthMeters / 2),
            Vec3(l.widthMeters / 2, (top - bottom) / 2, l.depthMeters / 2),
        )
    }

    private val effectiveYaw: Float get() = if (planView) yaw - 45f else yaw

    fun cameraConfig(base: IsoSceneConfig): IsoSceneConfig = base.copy(
        yawDegrees = effectiveYaw,
        pitchDegrees = if (planView) 89.9f else IsoSceneConfig.ISO_PITCH_DEGREES,
        zoom = zoom,
        pan = pan,
    )

    private fun setup(): IsoCameraSetup =
        IsoCamera.compute(cameraConfig(IsoSceneConfig()), fitBounds, viewW.toFloat() / viewH)

    fun selectTool(t: Tool) {
        cancelDrag()
        tool = t
        if (t != Tool.SELECT) selection = null
        if (t != Tool.VIEW) yaw = snapIso(yaw)
        message = null
        if (t == Tool.FURNITURE && catalogId == null) catalogId = catalog.items.firstOrNull()?.id
    }

    fun rotateView() { yaw = wrap(snapIso(yaw) + 90f) }
    fun togglePlan() { planView = !planView; if (planView) yaw = snapIso(yaw) }
    fun toggleCutaway() { cutaway = !cutaway }
    fun resetView() { zoom = 1f; pan = Vec3(0f, 0f, 0f); fitBounds = computeFit(level) }
    fun rotatePlacement() { placeTurns = (placeTurns + 1) % 4 }

    private fun snapIso(y: Float) = wrap(45f + ((y - 45f) / 90f).roundToInt() * 90f)
    private fun wrap(y: Float) = ((y % 360f) + 360f) % 360f

    // ------------------------------------------------------------------ edits

    private fun apply(edit: (Level) -> Level): Boolean {
        if (!history.apply(edit)) return false
        commit()
        return true
    }

    private fun commit() {
        house = house.copy(level = history.current)
        canUndo = history.canUndo; canRedo = history.canRedo
        val sel = selection
        if (sel is Selection.Furniture && level.furniture.none { it.id == sel.id }) selection = null
        if (sel is Selection.RoomSel && level.rooms.none { it.id == sel.id }) selection = null
        onSave(house)
    }

    fun undo() { cancelDrag(); if (history.undo()) commit() }
    fun redo() { cancelDrag(); if (history.redo()) commit() }

    val selectedFurniture: FurnitureItem?
        get() = (selection as? Selection.Furniture)?.let { s -> level.furniture.firstOrNull { it.id == s.id } }
    val selectedRoom get() = (selection as? Selection.RoomSel)?.let { s -> level.rooms.firstOrNull { it.id == s.id } }

    fun rotateSelected() {
        val f = selectedFurniture ?: return
        val r = level.rotatedFurniture(f, catalog)
        if (r == null) message = "No room to rotate here" else apply { it.updateFurniture(r, catalog) }
    }

    fun recolorSelected() {
        selectedFurniture?.let { f ->
            val def = catalog[f.catalogId] ?: return
            if (def.colors.isEmpty()) { message = "${def.name} has no color options"; return }
            val next = def.colors[(def.colors.indexOf(f.color) + 1) % def.colors.size]
            apply { it.updateFurniture(f.copy(color = next), catalog) }
            return
        }
        selectedRoom?.let { r ->
            val next = ROOM_COLORS[(ROOM_COLORS.indexOf(r.floorColor) + 1) % ROOM_COLORS.size]
            apply { it.recolorRoom(r.id, next) }
        }
    }

    fun deleteSelected() {
        selectedFurniture?.let { f -> apply { it.removeFurniture(f.id) }; selection = null; return }
        selectedRoom?.let { r -> apply { it.removeRoomAt(r.cells.first(), catalog) }; selection = null }
    }

    // ------------------------------------------------------------------ picking

    private fun point(x: Float, y: Float, planeY: Float = 0f): Vec3? =
        IsoCamera.screenToPlane(setup(), x, y, viewW, viewH, planeY)

    private fun cellAt(x: Float, y: Float, planeY: Float = 0f): GridPoint? = point(x, y, planeY)?.let {
        GridPoint(floor(it.x / level.cellSize).toInt(), floor(it.z / level.cellSize).toInt())
    }

    private fun vertexAt(x: Float, y: Float): GridPoint? = point(x, y)?.let {
        GridPoint(
            (it.x / level.cellSize).roundToInt().coerceIn(0, level.plotWidth),
            (it.z / level.cellSize).roundToInt().coerceIn(0, level.plotDepth),
        )
    }

    private fun clampCell(c: GridPoint) =
        GridPoint(c.x.coerceIn(0, level.plotWidth - 1), c.z.coerceIn(0, level.plotDepth - 1))

    private fun shellOptions(): ShellBuilder.Options {
        val r = effectiveYaw * PI.toFloat() / 180f
        return ShellBuilder.Options(cutaway = cutaway && !planView, viewDirX = sin(r), viewDirZ = cos(r))
    }

    /** Nearest existing wall to the touch, tested at mid-wall height and at cut-wall height. */
    private fun wallAt(x: Float, y: Float): Edge? {
        val opts = shellOptions()
        for (planeY in floatArrayOf(1.2f, 0.15f)) {
            val p = point(x, y, planeY) ?: continue
            val u = p.x / level.cellSize; val v = p.z / level.cellSize
            val hz = v.roundToInt(); val vx = u.roundToInt()
            val candidates = listOf(
                abs(v - hz) to Edge.of(GridPoint(floor(u).toInt(), hz), GridPoint(floor(u).toInt() + 1, hz)),
                abs(u - vx) to Edge.of(GridPoint(vx, floor(v).toInt()), GridPoint(vx, floor(v).toInt() + 1)),
            ).sortedBy { it.first }
            for ((dist, e) in candidates) {
                if (dist > 0.45f || e !in level.walls) continue
                if (planeY > 0.5f && ShellBuilder.isCut(e, level, opts)) continue
                return e
            }
        }
        return null
    }

    /** Furniture under the touch. Tests a few heights so tall items are hit where they're drawn. */
    private fun furnitureAt(x: Float, y: Float): FurnitureItem? {
        for (planeY in floatArrayOf(1.4f, 0.9f, 0.45f, 0.05f)) {
            val c = cellAt(x, y, planeY) ?: continue
            val hits = level.furniture.filter { f ->
                val def = catalog[f.catalogId] ?: return@filter false
                def.height >= planeY - 0.06f && c in f.cells(catalog)
            }
            hits.firstOrNull { catalog[it.catalogId]?.floorLayer == false }?.let { return it }
            hits.firstOrNull()?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------------ gestures

    private sealed interface Drag {
        data class Rotate(var lastX: Float) : Drag
        data class RoomRect(val start: GridPoint) : Drag
        data class WallLine(val start: GridPoint) : Drag
        data object Place : Drag
        data class Move(val id: String, val grab: GridPoint) : Drag
        data object Ignore : Drag
    }

    private var drag: Drag? = null

    fun onGesture(g: SceneGesture) {
        when (g) {
            is SceneGesture.Pan -> {
                val d = IsoCamera.panDelta(setup(), g.dx, g.dy, viewW, viewH)
                val lim = Vec3(level.widthMeters / 2 + 1f, 0f, level.depthMeters / 2 + 1f)
                pan = Vec3((pan.x + d.x).coerceIn(-lim.x, lim.x), 0f, (pan.z + d.z).coerceIn(-lim.z, lim.z))
            }
            is SceneGesture.Zoom -> zoom = (zoom * g.factor).coerceIn(IsoSceneConfig.MIN_ZOOM, IsoSceneConfig.MAX_ZOOM)
            is SceneGesture.Tap -> onTap(g.x, g.y)
            is SceneGesture.DragStart -> onDragStart(g.x, g.y)
            is SceneGesture.DragMove -> onDragMove(g.x, g.y)
            is SceneGesture.DragEnd -> { onDragMove(g.x, g.y); onDragEnd() }
            SceneGesture.DragCancel -> cancelDrag()
        }
    }

    private fun onTap(x: Float, y: Float) {
        when (tool) {
            Tool.VIEW, Tool.ROOM, Tool.WALL -> message = tool.hint
            Tool.DOOR, Tool.WINDOW -> {
                val e = wallAt(x, y)
                if (e == null) message = "Tap directly on a wall"
                else apply { it.toggleOpening(e, if (tool == Tool.DOOR) OpeningType.DOOR else OpeningType.WINDOW) }
            }
            Tool.FURNITURE -> {
                val g = ghostAt(x, y) ?: return
                if (g.valid) apply { it.placeFurniture(g.item.copy(id = newId("f_")), catalog) }
                else message = problemText(g.item, null)
            }
            Tool.SELECT -> {
                val f = furnitureAt(x, y)
                selection = when {
                    f != null -> Selection.Furniture(f.id)
                    else -> cellAt(x, y)?.let { level.roomAt(it) }?.let { Selection.RoomSel(it.id) }
                }
            }
            Tool.ERASE -> {
                furnitureAt(x, y)?.let { f -> apply { it.removeFurniture(f.id) }; return }
                wallAt(x, y)?.let { e -> apply { it.removeWall(e) }; return }
                cellAt(x, y)?.let { c -> if (!apply { it.removeRoomAt(c, catalog) }) message = "Nothing to erase here" }
            }
        }
    }

    private fun onDragStart(x: Float, y: Float) {
        drag = when (tool) {
            Tool.VIEW -> Drag.Rotate(x)
            Tool.ROOM -> cellAt(x, y)?.let { Drag.RoomRect(clampCell(it)) }
            Tool.WALL -> vertexAt(x, y)?.let { Drag.WallLine(it) }
            Tool.FURNITURE -> Drag.Place
            Tool.SELECT -> furnitureAt(x, y)?.let { f ->
                selection = Selection.Furniture(f.id)
                val c = cellAt(x, y, (catalog[f.catalogId]?.height ?: 0f).coerceAtMost(0.45f)) ?: f.cell
                Drag.Move(f.id, GridPoint(c.x - f.cell.x, c.z - f.cell.z))
            } ?: Drag.Ignore
            else -> Drag.Ignore
        }
        onDragMove(x, y)
    }

    private fun onDragMove(x: Float, y: Float) {
        when (val d = drag) {
            is Drag.Rotate -> { yaw = wrap(yaw - (x - d.lastX) * 180f / viewW); d.lastX = x }
            is Drag.RoomRect -> cellAt(x, y)?.let { overlay = OverlayState(previewCells = rectCells(d.start, clampCell(it))) }
            is Drag.WallLine -> vertexAt(x, y)?.let { overlay = OverlayState(previewEdges = wallLineEdges(d.start, it).toSet()) }
            Drag.Place -> ghost = ghostAt(x, y)
            is Drag.Move -> {
                val f = level.furniture.firstOrNull { it.id == d.id } ?: return
                val h = (catalog[f.catalogId]?.height ?: 0f).coerceAtMost(0.45f)
                val c = cellAt(x, y, h) ?: return
                val moved = f.copy(cell = GridPoint(c.x - d.grab.x, c.z - d.grab.z))
                ghost = Ghost(moved, level.placementProblem(moved, catalog) == null, hidesId = f.id)
            }
            Drag.Ignore, null -> Unit
        }
    }

    private fun onDragEnd() {
        when (val d = drag) {
            is Drag.RoomRect -> overlay.previewCells.takeIf { it.isNotEmpty() }?.let { cells ->
                val xs = cells.map { it.x }; val zs = cells.map { it.z }
                apply { it.addRectRoom(GridPoint(xs.min(), zs.min()), GridPoint(xs.max(), zs.max())) }
            }
            is Drag.WallLine -> overlay.previewEdges.takeIf { it.isNotEmpty() }?.let { edges ->
                apply { it.copy(walls = it.walls + edges) }
            }
            Drag.Place -> ghost?.let { g ->
                if (g.valid) apply { it.placeFurniture(g.item.copy(id = newId("f_")), catalog) }
                else message = problemText(g.item, null)
            }
            is Drag.Move -> ghost?.let { g ->
                if (g.valid) apply { it.updateFurniture(g.item, catalog) } else message = problemText(g.item, d.id)
            }
            else -> Unit
        }
        cancelDrag()
    }

    private fun cancelDrag() {
        drag = null
        ghost = null
        overlay = OverlayState()
    }

    private fun ghostAt(x: Float, y: Float): Ghost? {
        val def = catalogId?.let { catalog[it] } ?: run { message = "Pick an item first"; return null }
        val c = cellAt(x, y) ?: return null
        val item = FurnitureItem("ghost", def.id, anchorFor(c, def, placeTurns), placeTurns)
        return Ghost(item, level.placementProblem(item, catalog, ignoreId = null) == null)
    }

    private fun problemText(item: FurnitureItem, ignoreId: String?) =
        when (level.placementProblem(item, catalog, ignoreId)) {
            PlaceProblem.OUTSIDE_ROOM -> "Furniture must be inside a room"
            PlaceProblem.OVERLAPS -> "Overlaps other furniture"
            PlaceProblem.CROSSES_WALL -> "Crosses a wall"
            PlaceProblem.UNKNOWN_ITEM -> "Unknown item"
            null -> "Can't place here"
        }

    // ------------------------------------------------------------------ scene

    private var shellKey: Any? = null
    private var shellSource: ModelSource.Bytes? = null
    private var overlayKey: Any? = null
    private var overlaySource: ModelSource.Bytes? = null
    private var generation = 0
    private val catalogBytes = HashMap<String, ModelSource.Bytes>()

    private fun catalogSource(def: CatalogItem): ModelSource? {
        def.file?.let { return ModelSource.Asset(it) }
        return catalogBytes.getOrPut(def.id) {
            val parts = BuiltinCatalog.mesh(def.id) ?: return null
            ModelSource.Bytes("catalog:${def.id}", GlbWriter.write(parts, def.id))
        }
    }

    /** Everything the renderer should show right now. Reads Compose state → recomposes on change. */
    fun sceneContent(): SceneContent {
        val l = level
        val opts = shellOptions()
        val models = mutableListOf<SceneModel>()

        val sKey = l to opts.let { Triple(it.cutaway, it.viewDirX > 0, it.viewDirZ > 0) }
        if (sKey != shellKey) {
            shellKey = sKey
            val parts = ShellBuilder.build(l, opts)
            shellSource = if (parts.isEmpty()) null else ModelSource.Bytes("shell:${generation++}", GlbWriter.write(parts, "shell"))
        }
        shellSource?.let { models += SceneModel("shell", it) }

        val ov = overlay.copy(
            showGrid = tool != Tool.VIEW || l.rooms.isEmpty(),
            selectedCells = selectedFurniture?.cells(catalog) ?: selectedRoom?.cells ?: emptySet(),
        )
        val oKey = Triple(l.plotWidth, l.plotDepth, ov)
        if (oKey != overlayKey) {
            overlayKey = oKey
            val parts = OverlayBuilder.build(l, ov)
            overlaySource = if (parts.isEmpty()) null else ModelSource.Bytes("overlay:${generation++}", GlbWriter.write(parts, "overlay"))
        }
        overlaySource?.let { models += SceneModel("overlay", it, castShadows = false) }

        val g = ghost
        for (f in l.furniture) {
            if (f.id == g?.hidesId) continue
            furnitureModel(f, f.id)?.let { models += it }
        }
        if (g != null) furnitureModel(g.item, "ghost")?.let {
            models += it.copy(
                tint = if (g.valid) Rgb(0.35f, 0.95f, 0.45f) else Rgb(1f, 0.30f, 0.30f),
                castShadows = false,
                recolor = emptyMap(),
            )
        }
        return SceneContent(models, fitBounds = fitBounds)
    }

    private fun furnitureModel(f: FurnitureItem, key: String): SceneModel? {
        val def = catalog[f.catalogId] ?: return null
        val src = catalogSource(def) ?: return null
        val (w, d) = def.footprint(f.quarterTurns)
        val cs = level.cellSize
        return SceneModel(
            key = key,
            source = src,
            position = Vec3((f.cell.x + w / 2f) * cs, 0f, (f.cell.z + d / 2f) * cs),
            rotationYDegrees = f.quarterTurns * 90f,
            recolor = f.color?.let { c -> def.recolorable.associateWith { c } } ?: emptyMap(),
        )
    }
}
