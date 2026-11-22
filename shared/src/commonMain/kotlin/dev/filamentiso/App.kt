package dev.filamentiso

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared UI: holds the view state (survives rotation) and draws a small HUD. */
@Composable
fun App(assetPath: String = "isoroom.glb") {
    var yaw by rememberSaveable { mutableFloatStateOf(45f) }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }

    val base = remember {
        IsoSceneConfig(
            hiddenNodes = IsoRoomFixups.hiddenNodes,
            matteMaterials = IsoRoomFixups.matteMaterials,
        )
    }
    val config = base.copy(yawDegrees = yaw, zoom = zoom)
    val bg = config.backgroundColor

    Box(Modifier.fillMaxSize().background(Color(bg.r, bg.g, bg.b))) {
        IsoSceneView(
            assetPath = assetPath,
            config = config,
            onConfigChange = { yaw = it.yawDegrees; zoom = it.zoom },
            modifier = Modifier.fillMaxSize(),
        )
        BasicText(
            text = "yaw ${yaw.toInt()}°  ·  zoom ${(zoom * 100).toInt()}%",
            style = TextStyle(color = Color(0xB3FFFFFF), fontSize = 13.sp),
            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
        )
    }
}
