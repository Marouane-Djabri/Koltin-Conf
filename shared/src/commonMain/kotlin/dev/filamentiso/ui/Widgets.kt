package dev.filamentiso.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Small design system: Compose Foundation only (no Material dependency). */
object Ui {
    val bg = Color(0xFF14161C)
    val panel = Color(0xE61E222B)
    val panelSolid = Color(0xFF1E222B)
    val accent = Color(0xFF3D7BFF)
    val danger = Color(0xFFE5484D)
    val text = Color(0xFFF2F3F5)
    val muted = Color(0xFF9AA0AC)

    val body = TextStyle(color = text, fontSize = 14.sp)
    val small = TextStyle(color = muted, fontSize = 12.sp)
    val title = TextStyle(color = text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    val heading = TextStyle(color = text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    color: Color = Ui.accent,
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> Ui.panelSolid
        selected -> color
        else -> Color(0xFF2A2F3A)
    }
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        BasicText(text, style = Ui.body.copy(color = if (enabled) Ui.text else Ui.muted))
    }
}
