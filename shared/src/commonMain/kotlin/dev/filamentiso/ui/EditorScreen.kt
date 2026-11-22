package dev.filamentiso.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import dev.filamentiso.IsoSceneConfig
import dev.filamentiso.IsoSceneView
import dev.filamentiso.editor.EditorController
import dev.filamentiso.editor.Tool
import kotlinx.coroutines.delay

private val EDITOR_BASE_CONFIG = IsoSceneConfig()

@Composable
fun EditorScreen(c: EditorController, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Ui.bg)) {
        IsoSceneView(
            content = c.sceneContent(),
            config = c.cameraConfig(EDITOR_BASE_CONFIG),
            onGesture = c::onGesture,
            modifier = Modifier.fillMaxSize().onSizeChanged { c.setViewport(it.width, it.height) },
        )

        val insets = Modifier.windowInsetsPadding(WindowInsets.safeDrawing)

        // Top bar
        Row(
            insets.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Pill("‹ Back", onClick = onBack)
            BasicText(c.house.name, style = Ui.heading, modifier = Modifier.weight(1f).padding(start = 4.dp))
            Pill("Undo", enabled = c.canUndo, onClick = c::undo)
            Pill("Redo", enabled = c.canRedo, onClick = c::redo)
        }

        // View controls (top right, under the top bar — clear of the bottom panels)
        Column(
            insets.align(Alignment.TopEnd).padding(top = 60.dp, end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Pill("⟳ 90°", onClick = c::rotateView)
            Pill(if (c.planView) "3D view" else "Plan view", selected = c.planView, onClick = c::togglePlan)
            Pill(if (c.cutaway) "Walls: cut" else "Walls: up", selected = c.cutaway, onClick = c::toggleCutaway)
            Pill("Recenter", onClick = c::resetView)
        }

        // Bottom panel
        Column(
            insets.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val msg = c.message
            LaunchedEffect(msg) { if (msg != null) { delay(2500); if (c.message == msg) c.message = null } }
            Box(
                Modifier.clip(RoundedCornerShape(10.dp)).background(Ui.panel).padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                BasicText(msg ?: c.tool.hint, style = if (msg != null) Ui.body.copy(color = Color(0xFFFFD27A)) else Ui.small)
            }

            // Context actions
            if (c.tool == Tool.SELECT && (c.selectedFurniture != null || c.selectedRoom != null)) {
                val name = c.selectedFurniture?.let { c.catalog[it.catalogId]?.name } ?: c.selectedRoom?.name ?: ""
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(name, style = Ui.heading, modifier = Modifier.padding(end = 4.dp))
                    if (c.selectedFurniture != null) Pill("Rotate", onClick = c::rotateSelected)
                    Pill("Color", onClick = c::recolorSelected)
                    Pill("Delete", color = Ui.danger, selected = true, onClick = c::deleteSelected)
                }
            }

            if (c.tool == Tool.FURNITURE) CatalogBar(c)

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Tool.entries.forEach { t -> Pill(t.label, selected = c.tool == t) { c.selectTool(t) } }
            }
        }
    }
}

@Composable
private fun CatalogBar(c: EditorController) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ui.panel).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val sel = c.catalogId?.let { c.catalog[it] }
            BasicText(sel?.let { "${it.name}  ·  ${it.footprintW * 0.5f}×${it.footprintD * 0.5f} m" } ?: "Pick an item",
                style = Ui.body, modifier = Modifier.weight(1f))
            Pill("Rotate ${c.placeTurns * 90}°", onClick = c::rotatePlacement)
        }
        for (cat in c.catalog.categories) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(cat, style = Ui.small, modifier = Modifier.width(64.dp))
                c.catalog.items.filter { it.category == cat }.forEach { item ->
                    Pill(item.name, selected = c.catalogId == item.id) { c.catalogId = item.id }
                }
                Spacer(Modifier.width(4.dp))
            }
        }
    }
}
