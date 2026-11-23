package dev.filamentiso.world.overlay

import dev.filamentiso.IsoCameraSetup
import dev.filamentiso.Mat4
import dev.filamentiso.Vec3
import dev.filamentiso.glb.Normalization
import dev.filamentiso.world.geo.LatLng
import dev.filamentiso.world.model.ModelTransform
import dev.filamentiso.world.model.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.tan

/** A point on screen, in physical pixels, y down. */
data class ScreenPoint(val x: Float, val y: Float) {
    operator fun minus(o: ScreenPoint) = ScreenPoint(x - o.x, y - o.y)
}

data class ScreenRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    operator fun contains(p: ScreenPoint) = p.x in left..right && p.y in top..bottom
    val center get() = ScreenPoint((left + right) / 2, (top + bottom) / 2)
}

/** The map as the overlay sees it for one frame. [project] maps a lat/lng to screen pixels. */
class MapView(
    val widthPx: Int,
    val heightPx: Int,
    val zoom: Double,
    val bearingDeg: Double,
    val tiltDeg: Double,
    val project: (LatLng) -> ScreenPoint,
)

/** A model whose bytes are loaded and whose bounds are known. */
class ResolvedModel(val source: dev.filamentiso.scene.ModelSource, val normalization: Normalization, val bboxMin: Vec3, val bboxMax: Vec3)

/** One building drawn this frame: its full matrix plus its screen box for tapping. */
data class ScreenEntity(
    val id: String,
    val model: ResolvedModel,
    val matrix: Mat4,
    val anchor: ScreenPoint,
    val bounds: ScreenRect,
    /** Larger = closer to the viewer. */
    val depth: Float,
)

/**
 * Glues 3D models to the map without knowing anything about the map library.
 *
 * The renderer uses one orthographic camera in *pixel space* (x right, y up = -screen y).
 * Each model is placed at its anchor's screen position, scaled by the ground's pixels-per-metre
 * at that anchor (which already includes MapLibre's perspective shrink), and turned by the map's
 * bearing and tilt. Depth is offset by screen y so models lower on screen draw in front.
 */
object OverlayProjector {
    private const val PROBE_M = 10.0
    private const val METERS_PER_DEG_LAT = 111_320.0
    /** Large enough that every model fits between near and far. */
    private const val DEPTH_RANGE = 200_000f

    fun camera(widthPx: Int, heightPx: Int): IsoCameraSetup {
        val cx = widthPx / 2f; val cy = -heightPx / 2f
        return IsoCameraSetup(
            eye = Vec3(cx, cy, DEPTH_RANGE / 2),
            target = Vec3(cx, cy, 0f),
            up = Vec3.UP,
            left = -widthPx / 2.0, right = widthPx / 2.0,
            bottom = -heightPx / 2.0, top = heightPx / 2.0,
            near = 1.0, far = DEPTH_RANGE.toDouble(),
        )
    }

    /** Moves [p] by metres east / north. Fine for the few hundred metres we need. */
    fun offset(p: LatLng, eastM: Double, northM: Double) = LatLng(
        lat = p.lat + northM / METERS_PER_DEG_LAT,
        lng = p.lng + eastM / (METERS_PER_DEG_LAT * cos(p.lat * PI / 180.0)),
    )

    /**
     * Pixels per metre on the ground at [anchor], from where 10 m east and 10 m north land on screen.
     * A tilted ground square of side s shows as a parallelogram with area s²·cos(t) and
     * |E|² + |N|² = s²(1 + cos²t); solving both gives s without knowing the map's projection.
     */
    fun groundScale(anchor: ScreenPoint, east: ScreenPoint, north: ScreenPoint, probeMeters: Double = PROBE_M): Float {
        val e = east - anchor; val n = north - anchor
        val k = probeMeters.toFloat()
        val area = abs(e.x * n.y - e.y * n.x) / (k * k)
        val q = (e.x * e.x + e.y * e.y + n.x * n.x + n.y * n.y) / (k * k)
        val disc = (q * q - 4 * area * area).coerceAtLeast(0f)
        return sqrt((q + sqrt(disc)) / 2f)
    }

    /** The rotation that turns ground axes (X east, Y up, -Z north) into pixel space for this map view. */
    fun viewRotation(bearingDeg: Double, tiltDeg: Double): Mat4 =
        Mat4.rotationX((90.0 - tiltDeg).toFloat()) * Mat4.rotationY(bearingDeg.toFloat())

    /** Sunlight direction in pixel space, so lighting stays put on the ground while the map turns. */
    fun sunDirection(groundSun: Vec3, bearingDeg: Double, tiltDeg: Double): Vec3 =
        viewRotation(bearingDeg, tiltDeg).rotate(groundSun).normalized()

    fun modelMatrix(
        anchor: ScreenPoint,
        pxPerMeter: Float,
        bearingDeg: Double,
        tiltDeg: Double,
        transform: ModelTransform,
        normalization: Normalization,
        viewHeightPx: Int,
    ): Mat4 {
        val depth = depthOf(anchor, tiltDeg, viewHeightPx)
        return Mat4.translation(anchor.x, -anchor.y, depth) *
            viewRotation(bearingDeg, tiltDeg) *
            Mat4.scale(pxPerMeter) *
            localMatrix(transform, normalization)
    }

    /** Ground-space (metres) placement shared with the isometric view: offset, turn, fit, recentre. */
    fun localMatrix(transform: ModelTransform, normalization: Normalization): Mat4 {
        val o: Vec2 = transform.offsetMeters
        return Mat4.translation(o.x, 0f, -o.y) *
            Mat4.rotationY(transform.rotationDeg) *
            Mat4.scale(normalization.fitScale * transform.scale) *
            Mat4.translation(Vec3(-normalization.pivot.x, -normalization.pivot.y, -normalization.pivot.z))
    }

    private fun depthOf(anchor: ScreenPoint, tiltDeg: Double, viewHeightPx: Int): Float {
        val t = tan(tiltDeg.coerceIn(0.0, 80.0) * PI / 180.0).toFloat()
        return (anchor.y - viewHeightPx) * t - DEPTH_RANGE * 0.1f
    }

    /** One building's placement for this frame, or null when its anchor is far off screen. */
    fun place(
        id: String,
        position: LatLng,
        transform: ModelTransform,
        model: ResolvedModel,
        view: MapView,
        marginPx: Float = 400f,
    ): ScreenEntity? {
        val anchor = view.project(position)
        if (anchor.x < -marginPx || anchor.y < -marginPx || anchor.x > view.widthPx + marginPx || anchor.y > view.heightPx + marginPx) return null
        val east = view.project(offset(position, PROBE_M, 0.0))
        val north = view.project(offset(position, 0.0, PROBE_M))
        val ppm = groundScale(anchor, east, north)
        if (!ppm.isFinite() || ppm <= 0f) return null
        val m = modelMatrix(anchor, ppm, view.bearingDeg, view.tiltDeg, transform, model.normalization, view.heightPx)
        return ScreenEntity(id, model, m, anchor, screenBounds(m, model.bboxMin, model.bboxMax), depth = m.m[14])
    }

    /** Screen rectangle covering the model's bounding box under [m] (pixel space → screen y down). */
    fun screenBounds(m: Mat4, lo: Vec3, hi: Vec3): ScreenRect {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in 0 until 8) {
            val p = m.transform(Vec3(if (i and 1 == 0) lo.x else hi.x, if (i and 2 == 0) lo.y else hi.y, if (i and 4 == 0) lo.z else hi.z))
            val sx = p.x; val sy = -p.y
            if (sx < l) l = sx; if (sx > r) r = sx
            if (sy < t) t = sy; if (sy > b) b = sy
        }
        return ScreenRect(l, t, r, b)
    }

    /** The building under a tap: the nearest one whose screen box contains it. */
    fun hitTest(entities: List<ScreenEntity>, tap: ScreenPoint): ScreenEntity? =
        entities.filter { tap in it.bounds }.maxByOrNull { it.depth }
}
