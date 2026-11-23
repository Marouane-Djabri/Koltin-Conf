package dev.filamentiso

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.filamentiso.scene.SceneContent
import dev.filamentiso.scene.SceneGesture

/**
 * The KMP seam: a platform view that renders [content] (glTF models) with [config]'s
 * camera and lighting, and reports raw touch input through [onGesture].
 *
 * Android: Filament + gltfio (androidMain). Desktop / iOS / web would add their own `actual`.
 * All interpretation of input (rotate, place furniture, draw rooms…) happens in common code.
 */
@Composable
expect fun IsoSceneView(
    content: SceneContent,
    config: IsoSceneConfig,
    /** Null = the view ignores touches and lets them through to what is below (map overlay). */
    onGesture: ((SceneGesture) -> Unit)?,
    modifier: Modifier = Modifier,
)

/** System back button (Android) — no-op on platforms without one. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
