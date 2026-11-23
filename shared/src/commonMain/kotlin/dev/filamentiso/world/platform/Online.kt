package dev.filamentiso.world.platform

import androidx.compose.runtime.Composable

/**
 * Whether the device has a network connection, updated live. Only the map tiles need it:
 * buildings, imports and interiors are stored on the device and work offline.
 * Android: ConnectivityManager callback. Web: navigator.onLine + online/offline events.
 */
@Composable
expect fun rememberOnline(): Boolean

const val OFFLINE_MESSAGE = "You're offline: the map can't load new areas. Your buildings and interiors are saved on this device."
