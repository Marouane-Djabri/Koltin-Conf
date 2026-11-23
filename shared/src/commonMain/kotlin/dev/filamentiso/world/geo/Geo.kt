package dev.filamentiso.world.geo

import androidx.compose.ui.unit.IntSize
import kotlinx.serialization.Serializable
import org.maplibre.spatialk.geojson.Position

/** A WGS84 point. Our own type, so shared logic doesn't depend on map library classes. */
@Serializable
data class LatLng(val lat: Double, val lng: Double)

/** The map camera as the world overlay sees it. Angles in degrees, [viewportPx] in physical pixels. */
data class MapCamera(
    val target: LatLng,
    val zoom: Double,
    val bearing: Double,
    val tilt: Double,
    val viewportPx: IntSize,
)

fun LatLng.toPosition() = Position(longitude = lng, latitude = lat)
fun Position.toLatLng() = LatLng(lat = latitude, lng = longitude)

object Places {
    /** Default start: central Algiers, looking over the bay. */
    val Algiers = LatLng(36.7755, 3.0597)
}
