package dev.filamentiso.world.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.filamentiso.world.geo.LatLng

@Composable
actual fun rememberMyLocation(): MyLocation = remember {
    MyLocation { onResult ->
        val geolocation: dynamic = js("navigator.geolocation")
        if (geolocation == null || geolocation == undefined) {
            onResult(null)
        } else {
            geolocation.getCurrentPosition(
                { pos: dynamic -> onResult(LatLng(pos.coords.latitude as Double, pos.coords.longitude as Double)) },
                { _: dynamic -> onResult(null) },
                js("({ enableHighAccuracy: false, timeout: 10000, maximumAge: 60000 })"),
            )
        }
    }
}
