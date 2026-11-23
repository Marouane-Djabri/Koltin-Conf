package dev.filamentiso.world.model

import dev.filamentiso.Vec3
import dev.filamentiso.geometry.SampleBuildings
import dev.filamentiso.model.Level
import dev.filamentiso.world.geo.LatLng
import kotlinx.serialization.Serializable

@Serializable
data class Vec2(val x: Float, val y: Float) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    companion object { val ZERO = Vec2(0f, 0f) }
}

/** An imported .glb, stored on the device. The file itself is never modified. */
@Serializable
data class ModelAsset(
    val id: String,
    val ownerId: String,
    val fileName: String,
    val sizeBytes: Long,
    val triangleCount: Int,
    val bboxMin: Vec3,
    val bboxMax: Vec3,
    /** Key in the [dev.filamentiso.storage.AssetStore]. */
    val storagePath: String,
)

/**
 * How a model sits on its anchor, on top of the automatic fit (see [dev.filamentiso.glb.Normalization]).
 * [offsetMeters] is (east, north).
 */
@Serializable
data class ModelTransform(
    val scale: Float = 1f,
    val rotationDeg: Float = 0f,
    val offsetMeters: Vec2 = Vec2.ZERO,
) {
    companion object {
        const val MIN_SCALE = 0.25f
        const val MAX_SCALE = 3f
        const val ROTATION_STEP = 15f

        /** Rotations snap to 15° and stay in [0, 360). */
        fun snapRotation(deg: Float): Float {
            val snapped = kotlin.math.round(deg / ROTATION_STEP) * ROTATION_STEP
            return ((snapped % 360f) + 360f) % 360f
        }
    }

    fun withScale(s: Float) = copy(scale = s.coerceIn(MIN_SCALE, MAX_SCALE))
    fun rotatedBy(deg: Float) = copy(rotationDeg = snapRotation(rotationDeg + deg))
}

@Serializable
enum class Visibility { PRIVATE, FRIENDS, PUBLIC }

/**
 * A building on the world map: either an imported [asset] or a built-in [placeholderKind].
 * [floors] are the interior levels (edited in the isometric view).
 */
@Serializable
data class Building(
    val id: String,
    val ownerId: String,
    val name: String,
    val position: LatLng,
    val asset: ModelAsset? = null,
    val placeholderKind: SampleBuildings.Kind? = SampleBuildings.Kind.COTTAGE,
    val transform: ModelTransform = ModelTransform(),
    val visibility: Visibility = Visibility.PRIVATE,
    val floors: List<Level> = emptyList(),
    val formatVersion: Int = 1,
) {
    /** What the overlay draws. */
    val modelRef: ModelRef
        get() = asset?.let { ModelRef.Asset(it.id) } ?: ModelRef.Placeholder(placeholderKind ?: SampleBuildings.Kind.COTTAGE)
}

/** Which model to draw, independent of how its bytes are loaded. */
sealed interface ModelRef {
    val key: String

    data class Placeholder(val kind: SampleBuildings.Kind) : ModelRef {
        override val key get() = "placeholder:${kind.id}"
    }

    data class Asset(val assetId: String) : ModelRef {
        override val key get() = "asset:$assetId"
    }
}

/** No accounts in this step: everything belongs to the local device user. */
const val LOCAL_USER = "local"
