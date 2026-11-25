package dev.filamentiso.world

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.filamentiso.geometry.SampleBuildings
import dev.filamentiso.model.newId
import dev.filamentiso.storage.AssetStore
import dev.filamentiso.storage.BlobPlotRepository
import dev.filamentiso.storage.BuildingRepository
import dev.filamentiso.storage.InMemoryBlobStore
import dev.filamentiso.storage.PlotRepository
import dev.filamentiso.world.scene.Plots
import dev.filamentiso.world.scene.Scene
import kotlinx.coroutines.CancellationException
import dev.filamentiso.world.geo.LatLng
import dev.filamentiso.world.model.Building
import dev.filamentiso.world.model.LOCAL_USER
import dev.filamentiso.world.model.ModelTransform
import dev.filamentiso.world.model.ModelAsset
import dev.filamentiso.world.model.ModelRef
import dev.filamentiso.glb.GlbCheck
import dev.filamentiso.glb.GlbLimits
import dev.filamentiso.glb.GlbProblem
import dev.filamentiso.glb.GlbValidator
import dev.filamentiso.glb.Normalization
import dev.filamentiso.storage.StorageException
import dev.filamentiso.world.overlay.ModelProvider
import dev.filamentiso.world.overlay.ResolvedModel
import dev.filamentiso.world.overlay.ScreenEntity

/**
 * World state shared by Android and the Web: the buildings, which one is selected, and the
 * placement session. Compose state, so the UI and the overlay recompose on changes.
 * All saving goes through [repository]; nothing here knows about files or IndexedDB.
 */
class WorldController(
    val repository: BuildingRepository,
    val assets: AssetStore,
    /** Returns true once, on the first launch (then remembers it). Null = never seed. */
    private val firstRun: (suspend () -> Boolean)? = null,
    private val plotRepository: PlotRepository = BlobPlotRepository(InMemoryBlobStore()),
) {
    val models = ModelProvider(assets)

    var buildings by mutableStateOf<List<Building>>(emptyList()); private set
    var plots by mutableStateOf<List<Scene>>(emptyList()); private set
    /** Id of the selected building or plot. */
    var selectedId by mutableStateOf<String?>(null)
    /** Building being placed / adjusted: drag moves it, sliders scale/rotate it. Saved on "Done". */
    var placing by mutableStateOf<Building?>(null); private set
    var message by mutableStateOf<String?>(null)
    /** Bumped when an async model load finishes, so the overlay re-resolves. */
    var modelsVersion by mutableIntStateOf(0); private set

    val selected: Building? get() = buildings.firstOrNull { it.id == selectedId }

    val selectedPlot: Scene? get() = plots.firstOrNull { it.id == selectedId }

    /** What the overlay drew last frame, for tap hit-testing (not state: it changes every frame). */
    var lastEntities: List<ScreenEntity> = emptyList()

    private var loaded = false

    /** Loads saved buildings; on the very first run, adds the demo street (see [DemoContent]). */
    suspend fun load() {
        if (loaded) return
        loaded = true
        try {
            if (firstRun?.invoke() == true) DemoContent.buildings().forEach { repository.save(it) }
            buildings = repository.list().sortedBy { it.name }
            plots = plotRepository.list().sortedBy { it.name }
        } catch (e: Exception) {
            message = e.message ?: "Could not read saved buildings."
        }
    }

    /** Synchronous lookup for the overlay; null while an imported model is still loading. */
    fun resolvedModel(b: Building): ResolvedModel? {
        val asset = b.asset ?: return models.placeholder(b.placeholderKind ?: SampleBuildings.Kind.COTTAGE)
        return models.cached(b.modelRef)
    }

    /** Loads imported models the overlay asked for but that aren't in memory yet. */
    suspend fun loadMissing(wanted: List<Building>) {
        var changed = false
        for (b in wanted) {
            val asset = b.asset ?: continue
            if (models.cached(b.modelRef) == null && models.fromAsset(asset) != null) changed = true
        }
        if (changed) modelsVersion++
    }

    // ---------------------------------------------------------------- plots

    /** Plot being claimed or moved. It follows the map centre until [finishClaim]. */
    var claiming by mutableStateOf<Scene?>(null); private set
    val claimingExisting: Boolean get() = claiming?.let { c -> plots.any { it.id == c.id } } == true

    fun startClaim(at: LatLng) { selectedId = null; claiming = Plots.new(at) }
    fun startMovingPlot(p: Scene) { selectedId = null; claiming = p }
    fun moveClaim(p: LatLng) { claiming = claiming?.copy(anchor = p) }
    fun rotateClaim(deg: Float) { claiming = claiming?.let { it.copy(headingDeg = ModelTransform.snapRotation(it.headingDeg + deg)) } }

    /** Only new plots can change size: an existing one has content. */
    fun setClaimSize(meters: Int) { if (!claimingExisting) claiming = claiming?.copy(width = meters, depth = meters) }
    fun cancelClaim() { claiming = null }

    suspend fun finishClaim() {
        val p = claiming ?: return
        if (!savePlot(p)) return
        claiming = null
        selectedId = p.id
    }

    /** Returns false (with a [message]) if the plot could not be stored. */
    suspend fun savePlot(p: Scene): Boolean {
        val isNew = plots.none { it.id == p.id }
        fun remember() { plots = (plots.filter { it.id != p.id } + p).sortedBy { it.name } }
        // Edits show at once, even while the write is still running.
        if (!isNew) remember()
        return try {
            plotRepository.save(p)
            if (isNew) remember()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = e.message ?: "Could not save."
            false
        }
    }

    suspend fun deletePlot(p: Scene) {
        try {
            plotRepository.delete(p.id)
            plots = plots.filter { it.id != p.id }
            if (selectedId == p.id) selectedId = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = e.message ?: "Could not delete."
        }
    }

    // ---------------------------------------------------------------- buildings

    fun startNewBuilding(at: LatLng, kind: SampleBuildings.Kind = SampleBuildings.Kind.COTTAGE) {
        selectedId = null
        placing = Building(
            id = newId("b_"), ownerId = LOCAL_USER, name = "My ${kind.title.lowercase()}",
            position = at, placeholderKind = kind,
        )
    }

    fun startPlacing(b: Building) {
        selectedId = null
        placing = b
    }

    /** Placement edits are local until [finishPlacing]: dragging doesn't hammer storage. */
    fun updatePlacing(f: (Building) -> Building) { placing = placing?.let(f) }

    fun moveTo(p: LatLng) = updatePlacing { it.copy(position = p) }
    fun setScale(s: Float) = updatePlacing { it.copy(transform = it.transform.withScale(s)) }
    fun rotate(deg: Float) = updatePlacing { it.copy(transform = it.transform.rotatedBy(deg)) }
    suspend fun setKind(kind: SampleBuildings.Kind) {
        val old = placing?.asset
        updatePlacing { it.copy(placeholderKind = kind, asset = null, name = "My ${kind.title.lowercase()}") }
        old?.let { dropIfOrphan(it) }
    }

    suspend fun cancelPlacing() {
        val b = placing ?: return
        placing = null
        b.asset?.let { dropIfOrphan(it) }
    }

    suspend fun finishPlacing() {
        val b = placing ?: return
        // Floors remember where they are: refresh that after a move.
        save(if (b.scenes.isEmpty()) b else b.withFloors(b.floors))
        placing = null
        selectedId = b.id
    }

    // ---------------------------------------------------------------- import

    /** True while a file is being read / validated / stored. */
    var importing by mutableStateOf(false); private set

    /**
     * The whole import pipeline, shared by both platforms: validate → store → use as the model of the
     * building being placed. Problems end up in [message] with a user-friendly text.
     */
    suspend fun importModel(fileName: String, bytes: ByteArray) {
        val target = placing ?: return
        importing = true
        try {
            val info = when (val check = GlbValidator.validate(fileName, bytes, GlbLimits.Building)) {
                is GlbCheck.Invalid -> { message = check.message; return }
                is GlbCheck.Valid -> check.info
            }
            val id = newId("a_")
            val path = assets.save(id, bytes)
            val asset = ModelAsset(id, LOCAL_USER, fileName, info.sizeBytes, info.triangleCount, info.bboxMin, info.bboxMax, path)
            val ref = ModelRef.Asset(id)
            models.remember(ref.key, models.resolveBytes(ref.key, bytes, asset))
            val old = target.asset
            updatePlacing {
                it.copy(asset = asset, placeholderKind = null, name = fileName.substringBeforeLast('.'), transform = ModelTransform())
            }
            old?.let { dropIfOrphan(it) }
            message = "Imported ${asset.fileName}: ${info.triangleCount} triangles, fitted to ${Normalization.HOME_FOOTPRINT_M.toInt()} m."
        } catch (e: StorageException) {
            message = e.message
        } catch (e: Exception) {
            message = "Could not import this file (${e.message ?: "unknown error"})."
        } finally {
            importing = false
        }
    }

    // ---------------------------------------------------------------- isometric view

    var viewMode by mutableStateOf<ViewMode>(ViewMode.Map); private set
    /** Orbit position in 90° steps (any integer; the UI animates between steps). */
    var isoStep by mutableIntStateOf(0); private set
    var isoZoom by mutableFloatStateOf(1f); private set

    /** The building shown in the iso view (also during both transitions). */
    val isoBuilding: Building? get() = viewMode.buildingIdOrNull?.let { id -> buildings.firstOrNull { it.id == id } }

    /** The plot shown in the iso view. View modes only carry an id, so plots use the same transition. */
    val isoPlot: Scene? get() = viewMode.buildingIdOrNull?.let { id -> plots.firstOrNull { it.id == id } }
    val isoAnchor: LatLng? get() = isoBuilding?.position ?: isoPlot?.anchor

    fun open(p: Scene) {
        selectedId = null
        viewMode = ViewModeMachine.open(viewMode, p.id)
    }

    fun open(b: Building) {
        if (viewMode == ViewMode.Map) { isoStep = 0; isoZoom = 1f }
        selectedId = null
        viewMode = ViewModeMachine.open(viewMode, b.id)
    }

    fun back() { viewMode = ViewModeMachine.back(viewMode) }
    fun enterInterior() { viewMode = ViewModeMachine.enterInterior(viewMode) }

    fun tick(dtMs: Float) {
        if (viewMode.buildingIdOrNull != null && isoAnchor == null) { viewMode = ViewModeMachine.buildingGone(viewMode); return }
        val before = viewMode
        viewMode = ViewModeMachine.tick(viewMode, dtMs)
        // Re-select the building when we land back on the map, so its sheet is still there.
        if (before is ViewMode.TransitionToMap && viewMode == ViewMode.Map) selectedId = before.buildingId
    }

    fun orbit(steps: Int) { isoStep += steps }

    /** Pinch in the iso view; pinching out past [IsoStage.EXIT_ZOOM] on the exterior goes back to the map. */
    fun zoomIso(factor: Float) {
        val z = isoZoom * factor
        val m = viewMode
        if (z < IsoStage.EXIT_ZOOM && m is ViewMode.Iso && m.sub == IsoSub.EXTERIOR) { back(); return }
        isoZoom = z.coerceIn(IsoStage.MIN_ZOOM, IsoStage.MAX_ZOOM)
    }

    /** Called by the UI when the picker's file is bigger than the limit, before reading it all. */
    fun rejectTooBig(size: Long) {
        message = GlbProblem.TooBig(size, GlbLimits.Building.maxBytes).message
    }

    /** Deletes an imported file nobody uses anymore (placement cancelled or replaced). */
    private suspend fun dropIfOrphan(a: ModelAsset) {
        if (buildings.any { it.asset?.id == a.id } || placing?.asset?.id == a.id) return
        runCatching { assets.delete(a.storagePath) }
        models.forget(ModelRef.Asset(a.id))
    }

    suspend fun save(b: Building) {
        try {
            repository.save(b)
            buildings = (buildings.filter { it.id != b.id } + b).sortedBy { it.name }
        } catch (e: Exception) {
            message = e.message ?: "Could not save."
        }
    }

    suspend fun delete(b: Building) {
        repository.delete(b.id)
        b.asset?.let { a -> if (buildings.none { it.id != b.id && it.asset?.id == a.id }) assets.delete(a.storagePath) }
        models.forget(b.modelRef)
        buildings = buildings.filter { it.id != b.id }
        if (selectedId == b.id) selectedId = null
    }

    companion object {
        const val FULL_MODEL_MIN_ZOOM = 16.0
        const val MAX_FULL_MODELS = 15
        val DEFAULT_TRANSFORM = ModelTransform()
    }
}
