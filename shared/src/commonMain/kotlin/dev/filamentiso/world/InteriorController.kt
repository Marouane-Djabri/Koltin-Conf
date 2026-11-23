package dev.filamentiso.world

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.filamentiso.IsoSceneConfig
import dev.filamentiso.Mat4
import dev.filamentiso.Vec3
import dev.filamentiso.editor.EditorController
import dev.filamentiso.editor.ExternalModel
import dev.filamentiso.editor.Tool
import dev.filamentiso.editor.addRectRoom
import dev.filamentiso.editor.toggleOpening
import dev.filamentiso.geometry.GlbWriter
import dev.filamentiso.geometry.ShellBuilder
import dev.filamentiso.glb.GlbCheck
import dev.filamentiso.glb.GlbLimits
import dev.filamentiso.glb.GlbValidator
import dev.filamentiso.glb.Normalization
import dev.filamentiso.model.BuiltinCatalog
import dev.filamentiso.model.Catalog
import dev.filamentiso.model.CatalogItem
import dev.filamentiso.model.Edge
import dev.filamentiso.model.GridPoint
import dev.filamentiso.model.House
import dev.filamentiso.model.Level
import dev.filamentiso.model.OpeningType
import dev.filamentiso.model.newId
import dev.filamentiso.scene.ModelSource
import dev.filamentiso.scene.SceneContent
import dev.filamentiso.scene.SceneModel
import dev.filamentiso.storage.StorageException
import dev.filamentiso.world.model.Building
import dev.filamentiso.world.model.ImportedFurniture
import dev.filamentiso.world.model.LOCAL_USER
import dev.filamentiso.world.model.ModelAsset
import dev.filamentiso.world.model.ModelRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * The interior of one building: up to [MAX_FLOORS] floors, each edited with the shared
 * [EditorController] (rooms, walls, doors/windows, paint, furniture, undo). An imported GLB is only
 * a shell, so the interior is our own grid, sized from the shell's footprint. The shell itself is
 * hidden while inside (same on both platforms; see README).
 */
class InteriorController(
    private val world: WorldController,
    buildingId: String,
    private val scope: CoroutineScope,
) {
    companion object {
        const val MAX_FLOORS = 5
        const val FLOOR_HEIGHT = 3f
        const val MIN_CELLS = 8
        const val MAX_CELLS = 80

        /** Grid size (cells of [Level.cellSize]) covering a footprint in metres, clamped to sane limits. */
        fun plotCells(widthM: Float, depthM: Float, cellSize: Float = 0.5f): Pair<Int, Int> {
            fun cells(m: Float) = ceil(m / cellSize - 1e-3f).toInt().coerceIn(MIN_CELLS, MAX_CELLS)
            return cells(widthM) to cells(depthM)
        }

        /** Interior grid for a building: its placed shell's footprint in cells. */
        fun footprintCellsOf(b: Building, model: dev.filamentiso.world.overlay.ResolvedModel): Pair<Int, Int> {
            val box = IsoStage.bounds(IsoStage.buildingMatrix(b, model), model.bboxMin, model.bboxMax)
            return plotCells(box.halfExtent.x * 2, box.halfExtent.z * 2)
        }

        /** A new floor: one room filling the plot, walls all around, and a front door on the ground floor. */
        fun newFloor(index: Int, w: Int, d: Int): Level {
            var l = Level(plotWidth = w, plotDepth = d, wallHeight = 2.6f)
                .addRectRoom(GridPoint(0, 0), GridPoint(w - 1, d - 1), if (index == 0) "Ground floor" else "Floor ${index + 1}")
            if (index == 0) {
                val mid = w / 2 - 1
                l = l.toggleOpening(Edge.of(GridPoint(mid, d), GridPoint(mid + 1, d)), OpeningType.DOOR)
            }
            return l
        }
    }

    var building by mutableStateOf(world.buildings.first { it.id == buildingId }); private set
    var floorIndex by mutableIntStateOf(0); private set
    var editor by mutableStateOf(makeEditor(0)); private set
    /** Bumped when an imported furniture model finishes loading. */
    private var assetsVersion by mutableIntStateOf(0)

    init {
        if (building.floors.isEmpty()) {
            val (w, d) = footprintCells()
            update(building.copy(floors = listOf(newFloor(0, w, d))))
            editor = makeEditor(0)
        }
    }

    val floorCount get() = building.floors.size
    val canAddFloor get() = floorCount < MAX_FLOORS
    val canRemoveFloor get() = floorCount > 1

    private fun footprintCells(): Pair<Int, Int> =
        world.resolvedModel(building)?.let { footprintCellsOf(building, it) } ?: (20 to 20)

    fun selectFloor(i: Int) {
        if (i !in building.floors.indices || i == floorIndex) return
        val keep = editor
        floorIndex = i
        editor = makeEditor(i).also { it.selectTool(keep.tool) }
    }

    fun addFloor() {
        if (!canAddFloor) return
        val below = building.floors.last()
        update(building.copy(floors = building.floors + newFloor(floorCount, below.plotWidth, below.plotDepth)))
        selectFloor(floorCount - 1)
    }

    /** Removes the top floor (floors are a stack). */
    fun removeTopFloor() {
        if (!canRemoveFloor) return
        val top = floorCount - 1
        if (floorIndex == top) selectFloor(top - 1)
        update(building.copy(floors = building.floors.dropLast(1)))
    }

    private fun catalog() = Catalog(building.importedFurniture.map { it.item } + BuiltinCatalog.items)

    private fun makeEditor(i: Int): EditorController {
        val level = building.floors.getOrNull(i) ?: Level()
        val house = House("${building.id}#$i", building.name, level)
        return EditorController(house, catalog(), onSave = { h -> saveFloor(i, h.level) }, externalModel = ::externalModel)
    }

    private fun saveFloor(i: Int, level: Level) {
        if (i !in building.floors.indices) return
        update(building.copy(floors = building.floors.toMutableList().also { it[i] = level }))
    }

    private fun update(b: Building) {
        building = b
        scope.launch { world.save(b) }
    }

    // ---------------------------------------------------------------- imported furniture

    private fun externalModel(def: CatalogItem): ExternalModel? {
        assetsVersion // read: recompose when a model finishes loading
        val imported = building.importedFurniture.firstOrNull { it.item.id == def.id } ?: return null
        val a = imported.asset
        val cached = world.models.cached(ModelRef.Asset(a.id))
        if (cached == null) {
            scope.launch { if (world.models.fromAsset(a) != null) assetsVersion++ }
            return null
        }
        val cs = editor.level.cellSize
        return ExternalModel(cached.source, Normalization.fitInto(a.bboxMin, a.bboxMax, def.footprintW * cs, def.footprintD * cs))
    }

    var importing by mutableStateOf(false); private set

    /** Same pipeline as buildings, with the furniture limits (5 MB, 15k triangles); fitted to [w] x [d] cells. */
    suspend fun importFurniture(fileName: String, bytes: ByteArray, w: Int, d: Int) {
        importing = true
        try {
            val info = when (val check = GlbValidator.validate(fileName, bytes, GlbLimits.Furniture)) {
                is GlbCheck.Invalid -> { editor.message = check.message; return }
                is GlbCheck.Valid -> check.info
            }
            val id = newId("a_")
            val path = world.assets.save(id, bytes)
            val asset = ModelAsset(id, LOCAL_USER, fileName, info.sizeBytes, info.triangleCount, info.bboxMin, info.bboxMax, path)
            val cs = editor.level.cellSize
            val fit = Normalization.fitInto(info.bboxMin, info.bboxMax, w * cs, d * cs)
            val item = CatalogItem(
                id = "asset:$id", name = fileName.substringBeforeLast('.'), category = "Imported",
                footprintW = w, footprintD = d, height = (info.bboxMax.y - info.bboxMin.y) * fit.fitScale, assetId = id,
            )
            world.models.remember(ModelRef.Asset(id).key, world.models.resolveBytes(ModelRef.Asset(id).key, bytes, asset))
            update(building.copy(importedFurniture = building.importedFurniture + ImportedFurniture(item, asset)))
            editor.catalog = catalog()
            editor.selectTool(Tool.FURNITURE)
            editor.catalogId = item.id
            editor.message = "Imported ${item.name}: tap the floor to place it"
        } catch (e: StorageException) {
            editor.message = e.message
        } catch (e: Exception) {
            editor.message = "Could not import this file (${e.message ?: "unknown error"})."
        } finally {
            importing = false
        }
    }

    // ---------------------------------------------------------------- scene

    private val shellCache = HashMap<Level, ModelSource.Bytes>()

    /** Floors below the current one, drawn underneath it (walls full height, no cutaway). */
    private fun lowerFloors(): List<SceneModel> = (0 until floorIndex).mapNotNull { i ->
        val level = building.floors[i]
        val src = shellCache.getOrPut(level) {
            val parts = ShellBuilder.build(level, ShellBuilder.Options(cutaway = false, viewDirX = 1f, viewDirZ = 1f))
            if (parts.isEmpty()) return@mapNotNull null
            ModelSource.Bytes("floor_below:${level.hashCode()}", GlbWriter.write(parts, "floor_$i"))
        }
        SceneModel("floor_below_$i", src, transform = Mat4.translation(0f, -(floorIndex - i) * FLOOR_HEIGHT, 0f), castShadows = false)
    }

    fun sceneContent(): SceneContent {
        val c = editor.sceneContent()
        return c.copy(models = c.models + lowerFloors())
    }

    /** The editor's own camera (it picks with the same numbers), with the iso view's light and backdrop. */
    fun config(): IsoSceneConfig = editor.cameraConfig(
        IsoSceneConfig(
            transparent = true,
            shadows = true,
            sunDirection = Vec3(-0.55f, -1f, -0.35f),
            sunIntensity = 70_000f,
            ambientIntensity = 16_000f,
        ),
    )
}
