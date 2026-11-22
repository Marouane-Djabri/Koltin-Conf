package dev.filamentiso

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val length: Float get() = sqrt(this dot this)
    fun normalized(): Vec3 = length.let { if (it == 0f) this else Vec3(x / it, y / it, z / it) }

    companion object {
        val UP = Vec3(0f, 1f, 0f)
    }
}

/** Axis-aligned box, as center + half-extents (matches Filament's `Box`). */
data class Bounds(val center: Vec3, val halfExtent: Vec3)

/**
 * Result of [IsoCamera.compute]: a look-at plus orthographic bounds in view space,
 * ready to hand to any engine (Filament: `Camera.lookAt` + `setProjection(ORTHO, …)`).
 */
data class IsoCameraSetup(
    val eye: Vec3,
    val target: Vec3,
    val up: Vec3,
    val left: Double,
    val right: Double,
    val bottom: Double,
    val top: Double,
    val near: Double,
    val far: Double,
)

object IsoCamera {

    /** Unit vector from the target toward the eye for the given yaw/pitch (Y up). */
    fun viewDirection(yawDegrees: Float, pitchDegrees: Float): Vec3 {
        val yaw = yawDegrees.toRad()
        val pitch = pitchDegrees.toRad()
        return Vec3(cos(pitch) * sin(yaw), sin(pitch), cos(pitch) * cos(yaw))
    }

    /**
     * Orthographic iso camera that fits [bounds] into a viewport of aspect [aspect] (width / height).
     *
     * The fit treats the box as the vertical cylinder that encloses it, so the framing does not
     * "breathe" while yaw changes: horizontally the screen must hold the cylinder's radius,
     * vertically its top and bottom ellipses seen at [IsoSceneConfig.pitchDegrees].
     * Zoom then divides the bounds.
     */
    fun compute(config: IsoSceneConfig, bounds: Bounds, aspect: Float): IsoCameraSetup {
        require(aspect > 0f) { "aspect must be > 0, was $aspect" }
        val h = bounds.halfExtent
        val pitch = config.pitchDegrees.toRad()

        val radiusXZ = sqrt(h.x * h.x + h.z * h.z)
        val screenHalfW = radiusXZ
        val screenHalfH = radiusXZ * sin(pitch) + h.y * cos(pitch)

        var halfW = screenHalfW * (1f + config.fitMargin)
        var halfH = screenHalfH * (1f + config.fitMargin)
        if (halfW / halfH < aspect) halfW = halfH * aspect else halfH = halfW / aspect
        val zoom = config.zoom.coerceIn(IsoSceneConfig.MIN_ZOOM, IsoSceneConfig.MAX_ZOOM)
        halfW /= zoom
        halfH /= zoom

        // Place the eye outside the bounding sphere; ortho doesn't care about distance,
        // only near/far must enclose the model.
        val radius = max(h.length, 1e-3f)
        val distance = radius * 3f
        val dir = viewDirection(config.yawDegrees, config.pitchDegrees)
        val eye = bounds.center + dir * distance

        return IsoCameraSetup(
            eye = eye,
            target = bounds.center,
            up = Vec3.UP,
            left = -halfW.toDouble(),
            right = halfW.toDouble(),
            bottom = -halfH.toDouble(),
            top = halfH.toDouble(),
            near = (distance - radius * 1.5f).toDouble().coerceAtLeast(0.01),
            far = (distance + radius * 1.5f).toDouble(),
        )
    }

    /** Projects [point] onto the camera's screen plane: (x right, y up) in world units. */
    fun projectToScreen(setup: IsoCameraSetup, point: Vec3): Pair<Float, Float> {
        val forward = (setup.target - setup.eye).normalized()
        val right = (forward cross setup.up).normalized()
        val up = right cross forward
        val d = point - setup.target
        return (d dot right) to (d dot up)
    }

    internal fun Float.toRad() = (this * PI / 180.0).toFloat()
}
