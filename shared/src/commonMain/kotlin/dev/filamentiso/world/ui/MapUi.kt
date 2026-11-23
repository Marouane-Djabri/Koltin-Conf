package dev.filamentiso.world.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Light, rounded HUD style that matches the pastel map. Compose Foundation only. */
object MapUi {
    val surface = Color(0xF7FFFFFF)
    val outline = Color(0x1F5B4E6E)
    val ink = Color(0xFF4A4257)
    val inkSoft = Color(0xFF8A8197)
    val accent = Color(0xFFFF8FAB)
    val accentInk = Color(0xFFFFFFFF)
    val loading = Color(0xFFFBF6EC)

    val body = TextStyle(color = ink, fontSize = 15.sp)
    val bodyStrong = TextStyle(color = ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val small = TextStyle(color = inkSoft, fontSize = 12.sp)
    val tiny = TextStyle(color = ink, fontSize = 10.sp)
    val title = TextStyle(color = ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
}

/** A floating rounded chip. */
@Composable
fun MapChip(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .shadow(4.dp, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .background(if (primary) MapUi.accent else MapUi.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        BasicText(text, style = MapUi.bodyStrong.copy(color = if (primary) MapUi.accentInk else MapUi.ink))
    }
}

/** A floating round icon-ish button (text glyph, so no icon dependency). */
@Composable
fun MapRoundButton(
    glyph: String,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .shadow(4.dp, CircleShape)
            .clip(CircleShape)
            .background(MapUi.surface)
            .border(1.dp, MapUi.outline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(glyph, style = MapUi.title.copy(fontSize = 18.sp))
    }
}
