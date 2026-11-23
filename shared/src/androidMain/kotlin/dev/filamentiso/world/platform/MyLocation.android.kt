package dev.filamentiso.world.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.filamentiso.world.geo.LatLng

@Composable
actual fun rememberMyLocation(): MyLocation {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<(LatLng?) -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val callback = pending[0] ?: return@rememberLauncherForActivityResult
        pending[0] = null
        if (grants.values.any { it }) currentLocation(context, callback) else callback(null)
    }
    return remember(context) {
        MyLocation { onResult ->
            if (hasPermission(context)) {
                currentLocation(context, onResult)
            } else {
                pending[0] = onResult
                launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        }
    }
}

private fun hasPermission(context: Context) =
    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** A fresh fix if the platform can give one quickly, else the last known one. */
@SuppressLint("MissingPermission") // checked by the caller
private fun currentLocation(context: Context, onResult: (LatLng?) -> Unit) {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers = lm.getProviders(true)
    val lastKnown = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
    fun Location.toLatLng() = LatLng(latitude, longitude)

    val provider = when {
        LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
        LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
        else -> null
    }
    if (provider == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        onResult(lastKnown?.toLatLng())
        return
    }
    val main = Handler(Looper.getMainLooper())
    lm.getCurrentLocation(provider, null, context.mainExecutor) { loc ->
        main.post { onResult((loc ?: lastKnown)?.toLatLng()) }
    }
}
