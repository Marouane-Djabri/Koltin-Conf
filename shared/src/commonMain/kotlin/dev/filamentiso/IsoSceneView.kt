package dev.filamentiso

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The KMP seam: a platform-specific view that renders [assetPath] (a .glb) with [config].
 *
 * Android: Filament + gltfio (androidMain). Desktop / iOS / web would add their own `actual`.
 * Gestures (drag = yaw, pinch = zoom) are reported through [onConfigChange] so the state
 * stays in common code.
 */
@Composable
expect fun IsoSceneView(
    assetPath: String,
    config: IsoSceneConfig,
    onConfigChange: (IsoSceneConfig) -> Unit,
    modifier: Modifier = Modifier,
)
