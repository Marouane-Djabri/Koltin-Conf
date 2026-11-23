package dev.filamentiso

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.filamentiso.scene.SceneContent
import dev.filamentiso.scene.SceneGesture
import dev.filamentiso.ui.Ui

/** The Filament interior renderer is Android-only for now; the web shows a placeholder. */
@Composable
actual fun IsoSceneView(
    content: SceneContent,
    config: IsoSceneConfig,
    onGesture: (SceneGesture) -> Unit,
    modifier: Modifier,
) {
    Box(modifier.background(Ui.bg), contentAlignment = Alignment.Center) {
        BasicText("3D interiors are coming soon on the web.", style = Ui.body)
    }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
