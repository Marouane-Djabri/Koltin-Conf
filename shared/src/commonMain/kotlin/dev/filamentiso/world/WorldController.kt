package dev.filamentiso.world

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.filamentiso.geometry.SampleBuildings
import dev.filamentiso.model.newId
import dev.filamentiso.storage.AssetStore
import dev.filamentiso.storage.BuildingRepository
import dev.filamentiso.world.geo.LatLng
import dev.filamentiso.world.model.Building
import dev.filamentiso.world.model.LOCAL_USER
import dev.filamentiso.world.model.ModelTransform
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
) {
    val models = ModelProvider(assets)

    var buildings by mutableStateOf<List<Building>>(emptyList()); private set
    var selectedId by mutableStateOf<String?>(null)
    /** Building being placed / adjusted: drag moves it, sliders scale/rotate it. Saved on "Done". */
    var placing by mutableStateOf<Building?>(null); private set
    var message by mutableStateOf<String?>(null)
    /** Bumped when an async model load finishes, so the overlay re-resolves. */
    var modelsVersion by mutableIntStateOf(0); private set

    val selected: Building? get() = buildings.firstOrNull { it.id == selectedId }

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
    fun setKind(kind: SampleBuildings.Kind) = updatePlacing { it.copy(placeholderKind = kind, asset = null, name = "My ${kind.title.lowercase()}") }

    fun cancelPlacing() { placing = null }

    suspend fun finishPlacing() {
        val b = placing ?: return
        save(b)
        placing = null
        selectedId = b.id
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
