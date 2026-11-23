package dev.filamentiso.world

import dev.filamentiso.Bounds
import dev.filamentiso.IsoSceneConfig
import dev.filamentiso.Mat4
import dev.filamentiso.Rgb
import dev.filamentiso.Vec3
import dev.filamentiso.geometry.GlbWriter
import dev.filamentiso.geometry.MaterialSpec
import dev.filamentiso.geometry.MeshBuilder
import dev.filamentiso.scene.ModelSource
import dev.filamentiso.scene.SceneModel
import dev.filamentiso.world.model.Building
import dev.filamentiso.world.model.Vec2
import dev.filamentiso.world.overlay.OverlayProjector
import dev.filamentiso.world.overlay.ResolvedModel
import kotlin.math.max

/**
 * The isometric "diorama" for one building: the model centred on the origin (same fit, scale and
 * rotation as on the map, in metres), standing on a soft pastel ground disc. Pure shared code;
 * the renderers only receive models, matrices and a camera config.
 */
object IsoStage {
    const val MIN_ZOOM = 0.6f
    const val MAX_ZOOM = 3.2f
    /** Pinching out below this leaves the iso view (back to the map). */
    const val EXIT_ZOOM = 0.5f

    /** Unit disc (radius 1, 0.12 m thick, top at y = 0) with a lighter rim. */
    val groundDisc: ModelSource by lazy {
        val b = MeshBuilder()
        b.prism(MaterialSpec("ground_rim", Rgb(0.86f, 0.90f, 0.82f), roughness = 0.95f), 0f, 0f, -0.12f, -0.02f, 1f, sides = 48,
            top = MaterialSpec("ground", Rgb(0.80f, 0.90f, 0.78f), roughness = 0.95f))
        b.prism(MaterialSpec("ground", Rgb(0.80f, 0.90f, 0.78f), roughness = 0.95f), 0f, 0f, -0.02f, 0f, 0.96f, sides = 48)
        ModelSource.Bytes("iso_ground_disc", GlbWriter.write(b.build(), "ground"))
    }

    /** The building's matrix in the iso scene: map placement without the map offset. */
    fun buildingMatrix(b: Building, model: ResolvedModel): Mat4 =
        OverlayProjector.localMatrix(b.transform.copy(offsetMeters = Vec2.ZERO), model.normalization)

    /** World-space bounds of the placed model (transformed bounding box). */
    fun bounds(m: Mat4, lo: Vec3, hi: Vec3): Bounds {
        var mn = Vec3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        var mx = Vec3(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in 0 until 8) {
            val p = m.transform(Vec3(if (i and 1 == 0) lo.x else hi.x, if (i and 2 == 0) lo.y else hi.y, if (i and 4 == 0) lo.z else hi.z))
            mn = Vec3(minOf(mn.x, p.x), minOf(mn.y, p.y), minOf(mn.z, p.z))
            mx = Vec3(maxOf(mx.x, p.x), maxOf(mx.y, p.y), maxOf(mx.z, p.z))
        }
        return Bounds((mn + mx) * 0.5f, (mx - mn) * 0.5f)
    }

    /** Ground disc sized to the footprint. */
    fun ground(bounds: Bounds): SceneModel {
        val r = max(bounds.halfExtent.x, bounds.halfExtent.z) * 1.45f + 1f
        return SceneModel(
            key = "iso_ground", source = groundDisc,
            transform = Mat4.translation(bounds.center.x, 0f, bounds.center.z) * Mat4.scale(r, 1f, r),
            castShadows = false,
        )
    }

    /** Frames the building (not the disc) so it fills the view nicely. */
    fun frame(bounds: Bounds) = Bounds(
        Vec3(bounds.center.x, bounds.halfExtent.y, bounds.center.z),
        Vec3(bounds.halfExtent.x * 1.15f, bounds.halfExtent.y, bounds.halfExtent.z * 1.15f),
    )

    /**
     * Camera + light for the iso view. Classic isometric pitch, yaw in 90° steps (from [yawDeg]),
     * transparent clear so the Compose gradient backdrop shows behind the diorama.
     */
    fun config(yawDeg: Float, zoom: Float) = IsoSceneConfig(
        yawDegrees = yawDeg,
        zoom = zoom.coerceIn(IsoSceneConfig.MIN_ZOOM, IsoSceneConfig.MAX_ZOOM),
        transparent = true,
        shadows = true,
        fitMargin = 0.12f,
        sunDirection = Vec3(-0.55f, -1f, -0.35f),
        sunIntensity = 70_000f,
        ambientIntensity = 13_000f,
    )

    /** Yaw for orbit step k: 45°, 135°, 225°, 315° (always a true isometric corner view). */
    fun yawForStep(step: Int) = 45f + 90f * step
}
