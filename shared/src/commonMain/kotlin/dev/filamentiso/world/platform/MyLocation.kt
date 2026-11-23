package dev.filamentiso.world.platform

import androidx.compose.runtime.Composable
import dev.filamentiso.world.geo.LatLng

/** One-shot "where am I?". Asks for permission if needed; reports null if denied or unavailable. */
fun interface MyLocation {
    fun request(onResult: (LatLng?) -> Unit)
}

/** Android: location permission + LocationManager. Web: the browser Geolocation API. */
@Composable
expect fun rememberMyLocation(): MyLocation
