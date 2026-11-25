package dev.filamentiso.world.scene

import dev.filamentiso.model.newId
import dev.filamentiso.world.geo.LatLng
import dev.filamentiso.world.model.LOCAL_USER
import dev.filamentiso.world.overlay.OverlayProjector
import dev.filamentiso.world.overlay.ScreenPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Outdoor plots on the map: sizes, creation and their outline. */
object Plots {
    /** Side lengths offered when claiming, in metres. */
    val sizes = listOf(20, 40, 60)

    fun new(at: LatLng, size: Int = sizes.first()) = Scene(
        id = newId("p_"), ownerId = LOCAL_USER, name = "My plot", kind = SceneKind.OUTDOOR_PLOT,
        anchor = at, width = size, depth = size,
    )

    /** The four corners on the map, in order around the plot. Same rotation direction as buildings. */
    fun corners(s: Scene): List<LatLng> {
        val r = s.headingDeg * PI / 180
        val hw = s.widthM / 2.0
        val hd = s.depthM / 2.0
        return listOf(-hw to -hd, hw to -hd, hw to hd, -hw to hd).map { (x, z) ->
            val east = cos(r) * x + sin(r) * z
            val south = -sin(r) * x + cos(r) * z
            OverlayProjector.offset(s.anchor, east, -south)
        }
    }

    /** Even-odd point-in-polygon test. */
    fun inside(p: ScreenPoint, polygon: List<ScreenPoint>): Boolean {
        var hit = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) hit = !hit
            j = i
        }
        return hit
    }
}
