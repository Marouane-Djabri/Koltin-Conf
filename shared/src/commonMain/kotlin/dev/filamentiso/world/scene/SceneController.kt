package dev.filamentiso.world.scene

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.filamentiso.Bounds
import dev.filamentiso.IsoCamera
import dev.filamentiso.IsoCameraSetup
import dev.filamentiso.IsoSceneConfig
import dev.filamentiso.Vec3
import dev.filamentiso.editor.History
import dev.filamentiso.editor.cellLine
import dev.filamentiso.geometry.GlbWriter
import dev.filamentiso.geometry.MeshPart
import dev.filamentiso.geometry.OverlayBuilder
import dev.filamentiso.geometry.OverlayState
import dev.filamentiso.model.GridPoint
import dev.filamentiso.model.Level
import dev.filamentiso.scene.ModelSource
import dev.filamentiso.scene.SceneContent
import dev.filamentiso.scene.SceneGesture
import dev.filamentiso.scene.SceneModel
import dev.filamentiso.world.IsoStage
import kotlin.math.floor

enum class SceneTool(val label: String, val hint: String) {
    VIEW("View", "Drag to move around · pinch to zoom"),
    PAINT("Ground", "Tap or drag to paint the ground"),
}

/** Editor of an outdoor plot: camera, ground painting, undo. Shared by both platforms. */
class SceneController(initial: Scene, private val onSave: (Scene) -> Unit) {
    private val history = History(initial)
    var scene by mutableStateOf(initial); private set
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set

    var tool by mutableStateOf(SceneTool.VIEW); private set
    var ground by mutableStateOf(Ground.SAND)
    var brush by mutableIntStateOf(1)

    private var yaw by mutableStateOf(45f)
    private var zoom by mutableStateOf(1f)
    private var pan by mutableStateOf(Vec3(0f, 0f, 0f))
    private var viewW = 1
    private var viewH = 1

    /** Tiles of the stroke in progress. */
    private var stroke by mutableStateOf<Set<GridPoint>>(emptySet())
    private var lastCell: GridPoint? = null
    private var lastDrag: Pair<Float, Float>? = null

    private val bounds = Bounds(
        Vec3(initial.widthM / 2, 0.5f, initial.depthM / 2),
        Vec3(initial.widthM / 2, 1.5f, initial.depthM / 2),
    )

    fun setViewport(w: Int, h: Int) { viewW = w.coerceAtLeast(1); viewH = h.coerceAtLeast(1) }
    fun selectTool(t: SceneTool) { cancelStroke(); tool = t }
    fun rotateView() { yaw = (yaw + 90f) % 360f }

    fun undo() { cancelStroke(); if (history.undo()) commit() }
    fun redo() { cancelStroke(); if (history.redo()) commit() }

    private fun edit(f: (Scene) -> Scene) { if (history.apply(f)) commit() }

    private fun commit() {
        scene = history.current
        canUndo = history.canUndo
        canRedo = history.canRedo
        onSave(scene)
    }

    fun config(): IsoSceneConfig = IsoStage.config(yaw, zoom).copy(pan = pan)

    fun camera(): IsoCameraSetup = IsoCamera.compute(config(), bounds, viewW.toFloat() / viewH)

    private fun cellAt(x: Float, y: Float): GridPoint? = IsoCamera.screenToPlane(camera(), x, y, viewW, viewH)?.let {
        GridPoint(floor(it.x / scene.tileSizeM).toInt(), floor(it.z / scene.tileSizeM).toInt())
    }

    // ------------------------------------------------------------------ gestures

    fun onGesture(g: SceneGesture) {
        when (g) {
            is SceneGesture.Pan -> panBy(g.dx, g.dy)
            is SceneGesture.Zoom -> zoom = (zoom * g.factor).coerceIn(IsoStage.MIN_ZOOM, IsoSceneConfig.MAX_ZOOM)
            is SceneGesture.Tap -> if (tool == SceneTool.PAINT) { paintTo(g.x, g.y); endStroke() }
            is SceneGesture.DragStart -> { lastDrag = g.x to g.y; if (tool == SceneTool.PAINT) paintTo(g.x, g.y) }
            is SceneGesture.DragMove -> dragTo(g.x, g.y)
            is SceneGesture.DragEnd -> { dragTo(g.x, g.y); endStroke() }
            SceneGesture.DragCancel -> cancelStroke()
        }
    }

    private fun dragTo(x: Float, y: Float) {
        val last = lastDrag ?: return
        if (tool == SceneTool.PAINT) paintTo(x, y) else panBy(x - last.first, y - last.second)
        lastDrag = x to y
    }

    private fun panBy(dx: Float, dy: Float) {
        val d = IsoCamera.panDelta(camera(), dx, dy, viewW, viewH)
        val lx = scene.widthM / 2
        val lz = scene.depthM / 2
        pan = Vec3((pan.x + d.x).coerceIn(-lx, lx), 0f, (pan.z + d.z).coerceIn(-lz, lz))
    }

    private fun paintTo(x: Float, y: Float) {
        val c = cellAt(x, y) ?: return
        // Fill between two touch samples so fast strokes leave no gaps.
        val line = lastCell?.let { cellLine(it, c) } ?: listOf(c)
        lastCell = c
        stroke = stroke + line.flatMap { brushCells(it, brush) }.filter { scene.contains(it) }
    }

    private fun endStroke() {
        val cells = stroke
        cancelStroke()
        if (cells.isNotEmpty()) edit { it.paint(cells, ground) }
    }

    private fun cancelStroke() { stroke = emptySet(); lastCell = null; lastDrag = null }

    // ------------------------------------------------------------------ drawing

    private var generation = 0
    private var groundKey: Any? = null
    private var groundSource: ModelSource.Bytes? = null
    private var overlayKey: Any? = null
    private var overlaySource: ModelSource.Bytes? = null

    /** Everything the renderer shows now. Reads Compose state, so it recomposes on change. */
    fun sceneContent(): SceneContent {
        val s = scene
        if (groundKey != s.tiles) {
            groundKey = s.tiles
            groundSource = source("ground", GroundBuilder.build(s))
        }
        val state = OverlayState(showGrid = tool == SceneTool.PAINT, previewCells = stroke, previewColor = ground.color.scaled(0.85f))
        if (overlayKey != state) {
            overlayKey = state
            val grid = Level(plotWidth = s.width, plotDepth = s.depth, cellSize = s.tileSizeM)
            val parts = OverlayBuilder.build(grid, state)
            overlaySource = if (parts.isEmpty()) null else source("overlay", parts)
        }
        return SceneContent(
            listOfNotNull(
                groundSource?.let { SceneModel("plot_ground", it, castShadows = false) },
                overlaySource?.let { SceneModel("plot_overlay", it, castShadows = false) },
            ),
            fitBounds = bounds,
        )
    }

    private fun source(name: String, parts: List<MeshPart>) =
        ModelSource.Bytes("${scene.id}:$name:${generation++}", GlbWriter.write(parts, name))
}
