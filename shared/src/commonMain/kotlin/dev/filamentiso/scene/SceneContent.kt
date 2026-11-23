package dev.filamentiso.scene

import dev.filamentiso.Bounds
import dev.filamentiso.Mat4
import dev.filamentiso.Rgb
import dev.filamentiso.Vec3

/** Where a model's .glb bytes come from. */
sealed interface ModelSource {
    /** A file in the platform's bundled assets (Android: src/main/assets). */
    data class Asset(val path: String) : ModelSource

    /**
     * Bytes generated at runtime. Equality uses [id] only, so renderers can cheaply tell
     * whether they must reload: change the id whenever the bytes change.
     */
    class Bytes(val id: String, val bytes: ByteArray) : ModelSource {
        override fun equals(other: Any?) = other is Bytes && other.id == id
        override fun hashCode() = id.hashCode()
        override fun toString() = "Bytes($id, ${bytes.size} B)"
    }
}

/** One glTF model placed in the scene. Engine-agnostic. */
data class SceneModel(
    /** Stable identity across updates (e.g. a furniture id). */
    val key: String,
    val source: ModelSource,
    val position: Vec3 = Vec3(0f, 0f, 0f),
    val rotationYDegrees: Float = 0f,
    /** Full world matrix; when set it replaces [position] and [rotationYDegrees]. Built in shared code. */
    val transform: Mat4? = null,
    /** Overrides every material's base color (used for the green/red placement ghost). */
    val tint: Rgb? = null,
    /** Material name → base color (user-chosen furniture colors). */
    val recolor: Map<String, Rgb> = emptyMap(),
    val castShadows: Boolean = true,
    /** glTF node names to leave out of the scene. */
    val hiddenNodes: Set<String> = emptySet(),
    /** Material names that exported without PBR data: render as matte dielectrics. */
    val matteMaterials: Set<String> = emptySet(),
)

data class SceneContent(
    val models: List<SceneModel>,
    /** What the camera fits. Null = fit everything visible once loaded. */
    val fitBounds: Bounds? = null,
)

/** Raw input from the platform view, in view pixels. Interpretation lives in common code. */
sealed interface SceneGesture {
    data class Tap(val x: Float, val y: Float) : SceneGesture
    data class DragStart(val x: Float, val y: Float) : SceneGesture
    data class DragMove(val x: Float, val y: Float) : SceneGesture
    data class DragEnd(val x: Float, val y: Float) : SceneGesture
    data object DragCancel : SceneGesture
    /** Two-finger pan, in pixels since the last event. */
    data class Pan(val dx: Float, val dy: Float) : SceneGesture
    /** Pinch, as a multiplicative factor since the last event. */
    data class Zoom(val factor: Float) : SceneGesture
}
