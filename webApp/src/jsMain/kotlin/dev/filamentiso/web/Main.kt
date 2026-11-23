package dev.filamentiso.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import dev.filamentiso.App
import dev.filamentiso.storage.InMemoryHouseStore
import kotlinx.browser.document
import org.jetbrains.skiko.wasm.onWasmReady
import org.maplibre.compose.browser.installMaplibreCompose

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    onWasmReady {
        installMaplibreCompose() // must run after skiko loads and before Compose starts
        ComposeViewport(document.body!!) {
            App(store = InMemoryHouseStore())
        }
    }
}
