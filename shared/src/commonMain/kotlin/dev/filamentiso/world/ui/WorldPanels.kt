package dev.filamentiso.world.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.filamentiso.geometry.SampleBuildings
import dev.filamentiso.world.WorldController
import dev.filamentiso.world.model.Building
import dev.filamentiso.world.model.ModelTransform
import kotlinx.coroutines.delay

/** Floating rounded card used for bottom panels. */
@Composable
fun MapCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .widthIn(max = 460.dp)
            .shadow(8.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(MapUi.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

/** Placement: the building follows the map centre; choose a model, scale and rotate it. */
@Composable
fun PlacementPanel(world: WorldController, b: Building, onDone: () -> Unit, extraActions: @Composable () -> Unit = {}) {
    MapCard {
        BasicText("Drag the map to move your building", style = MapUi.bodyStrong)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (k in SampleBuildings.Kind.entries) {
                MapChip(k.title, primary = b.asset == null && b.placeholderKind == k) { world.setKind(k) }
            }
            extraActions()
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText("Size", style = MapUi.small)
            MapSlider(
                value = b.transform.scale, range = ModelTransform.MIN_SCALE..ModelTransform.MAX_SCALE,
                onChange = { world.setScale(it) }, modifier = Modifier.weight(1f),
            )
            BasicText("${(b.transform.scale * 100).toInt()}%", style = MapUi.small)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MapChip("−15°") { world.rotate(-ModelTransform.ROTATION_STEP) }
            BasicText("${b.transform.rotationDeg.toInt()}°", style = MapUi.body)
            MapChip("+15°") { world.rotate(ModelTransform.ROTATION_STEP) }
            Box(Modifier.weight(1f))
            MapChip("Cancel") { world.cancelPlacing() }
            MapChip("Done", primary = true, onClick = onDone)
        }
    }
}

@Composable
fun BuildingSheet(b: Building, onEnter: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    MapCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BasicText(b.name, style = MapUi.title)
                val kind = b.asset?.let { "Imported · ${it.fileName} · ${it.triangleCount / 1000}k triangles" }
                    ?: (b.placeholderKind?.title ?: "Building")
                BasicText(kind, style = MapUi.small)
            }
            MapRoundButton("×", size = 36.dp, onClick = onClose)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MapChip("Enter ›", primary = true, onClick = onEnter)
            MapChip("Move", onClick = onMove)
            MapChip("Delete", onClick = onDelete)
        }
    }
}

@Composable
fun Toast(text: String, onDismiss: () -> Unit) {
    LaunchedEffect(text) { delay(4500); onDismiss() }
    Box(
        Modifier.shadow(6.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
            .background(MapUi.ink).clickable(onClick = onDismiss).padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        BasicText(text, style = MapUi.body.copy(color = MapUi.accentInk))
    }
}

/** Minimal slider (Compose Foundation has none without Material). */
@Composable
fun MapSlider(value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    fun fraction() = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    fun at(x: Float, width: Int) = range.start + (x / width).coerceIn(0f, 1f) * (range.endInclusive - range.start)
    Canvas(
        modifier.fillMaxWidth().height(32.dp)
            .pointerInput(range) { detectTapGestures { onChange(at(it.x, size.width)) } }
            .pointerInput(range) { detectDragGestures { change, _ -> onChange(at(change.position.x, size.width)) } },
    ) {
        val y = size.height / 2
        drawLine(MapUi.outline, Offset(0f, y), Offset(size.width, y), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
        val x = fraction() * size.width
        drawLine(MapUi.accent, Offset(0f, y), Offset(x, y), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(MapUi.accent, radius = 10.dp.toPx(), center = Offset(x, y))
        drawCircle(MapUi.surface, radius = 5.dp.toPx(), center = Offset(x, y))
    }
}
