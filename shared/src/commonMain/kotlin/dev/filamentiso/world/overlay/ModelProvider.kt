package dev.filamentiso.world.overlay

import dev.filamentiso.geometry.SampleBuildings
import dev.filamentiso.glb.GlbCheck
import dev.filamentiso.glb.GlbLimits
import dev.filamentiso.glb.GlbValidator
import dev.filamentiso.glb.Normalization
import dev.filamentiso.scene.ModelSource
import dev.filamentiso.storage.AssetStore
import dev.filamentiso.world.model.ModelAsset
import dev.filamentiso.world.model.ModelRef

/**
 * Turns a [ModelRef] into bytes + bounds the renderers can draw. Same for placeholders and imports:
 *
 * - `placeholder(kind)`: generated in code, already in metres, so it keeps its real size.
 * - `fromAsset(asset)`: bytes from the [AssetStore], auto-fitted to [Normalization.HOME_FOOTPRINT_M].
 *
 * Keeps a small in-memory cache; renderers free GPU memory themselves when a model leaves the scene.
 */
class ModelProvider(private val assets: AssetStore, private val maxCached: Int = 24) {
    private val cache = LinkedHashMap<String, ResolvedModel>()

    fun cached(ref: ModelRef): ResolvedModel? = cache[ref.key]?.also { touch(ref.key, it) }

    fun placeholder(kind: SampleBuildings.Kind): ResolvedModel {
        val key = ModelRef.Placeholder(kind).key
        cache[key]?.let { touch(key, it); return it }
        val bytes = SampleBuildings.glb(kind)
        val info = (GlbValidator.validate(bytes, GlbLimits.Building) as GlbCheck.Valid).info
        val pivot = Normalization.of(info.bboxMin, info.bboxMax, 1f).pivot
        return put(key, ResolvedModel(ModelSource.Bytes(key, bytes), Normalization(pivot, 1f), info.bboxMin, info.bboxMax))
    }

    /** Null if the bytes are gone (e.g. browser storage was cleared). */
    suspend fun fromAsset(asset: ModelAsset, targetFootprint: Float = Normalization.HOME_FOOTPRINT_M): ResolvedModel? {
        val key = ModelRef.Asset(asset.id).key
        cache[key]?.let { touch(key, it); return it }
        val bytes = assets.load(asset.storagePath) ?: return null
        return put(key, resolveBytes(key, bytes, asset, targetFootprint))
    }

    /** For a file that was just validated (import preview) and isn't stored yet. */
    fun resolveBytes(key: String, bytes: ByteArray, asset: ModelAsset, targetFootprint: Float = Normalization.HOME_FOOTPRINT_M) =
        ResolvedModel(ModelSource.Bytes(key, bytes), Normalization.of(asset.bboxMin, asset.bboxMax, targetFootprint), asset.bboxMin, asset.bboxMax)

    fun remember(key: String, model: ResolvedModel) { put(key, model) }

    fun forget(ref: ModelRef) { cache.remove(ref.key) }

    private fun touch(key: String, m: ResolvedModel) { cache.remove(key); cache[key] = m }

    private fun put(key: String, m: ResolvedModel): ResolvedModel {
        cache[key] = m
        while (cache.size > maxCached) cache.remove(cache.keys.first())
        return m
    }
}
