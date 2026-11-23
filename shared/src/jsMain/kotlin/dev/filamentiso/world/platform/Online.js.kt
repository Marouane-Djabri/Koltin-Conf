package dev.filamentiso.world.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import org.w3c.dom.events.Event

@Composable
actual fun rememberOnline(): Boolean {
    var online by remember { mutableStateOf(window.navigator.onLine) }
    DisposableEffect(Unit) {
        val update: (Event) -> Unit = { online = window.navigator.onLine }
        window.addEventListener("online", update)
        window.addEventListener("offline", update)
        onDispose {
            window.removeEventListener("online", update)
            window.removeEventListener("offline", update)
        }
    }
    return online
}
