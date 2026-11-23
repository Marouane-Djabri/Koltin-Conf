package dev.filamentiso.world.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.filamentiso.editor.EditorController
import dev.filamentiso.editor.Tool
import dev.filamentiso.geometry.SampleFiles
import dev.filamentiso.glb.GlbLimits
import dev.filamentiso.model.FloorMaterial
import dev.filamentiso.model.Status
import dev.filamentiso.resources.Res
import dev.filamentiso.world.InteriorController
import dev.filamentiso.world.WorldController
import dev.filamentiso.world.platform.glbPickerType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.size
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Tools offered inside a building (the basement editor's set plus Paint). */
private val INTERIOR_TOOLS = listOf(Tool.VIEW, Tool.SELECT, Tool.FURNITURE, Tool.PAINT, Tool.WALL, Tool.DOOR, Tool.WINDOW, Tool.ROOM, Tool.ERASE)

/** Footprints offered for imported furniture, in 0.5 m cells. */
private val IMPORT_FOOTPRINTS = listOf(1 to 1, 2 to 2, 3 to 2, 4 to 2)

@Composable
fun InteriorHud(ic: InteriorController, world: WorldController) {
    val c = ic.editor
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)) {
        // Top bar: back, title, undo / redo.
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MapChip("‹ Outside") { world.back() }
            Column(Modifier.weight(1f)) {
                BasicText(ic.building.name, style = MapUi.title)
                BasicText("Floor ${ic.floorIndex + 1} of ${ic.floorCount}", style = MapUi.small)
            }
            MapChip("Undo", primary = false) { if (c.canUndo) c.undo() }
            MapChip("Redo") { if (c.canRedo) c.redo() }
        }

        // Right: view controls.
        Column(Modifier.align(Alignment.CenterEnd), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
            MapRoundButton("⟳") { c.rotateView() }
            MapRoundButton("+") { c.onGesture(dev.filamentiso.scene.SceneGesture.Zoom(1.25f)) }
            MapRoundButton("−") { c.onGesture(dev.filamentiso.scene.SceneGesture.Zoom(0.8f)) }
            MapChip(if (c.planView) "3D" else "Plan", primary = c.planView) { c.togglePlan() }
            MapChip(if (c.cutaway) "Walls low" else "Walls up", primary = c.cutaway) { c.toggleCutaway() }
        }

        // Left: the floor stack (top floor first).
        Column(Modifier.align(Alignment.CenterStart), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (ic.canAddFloor) MapChip("+") { ic.addFloor() }
            for (i in (ic.floorCount - 1) downTo 0) FloorButton("${i + 1}", selected = i == ic.floorIndex) { ic.selectFloor(i) }
            if (ic.canRemoveFloor) MapChip("−") { ic.removeTopFloor() }
        }

        // Bottom: hint, context panel, tools.
        Column(
            Modifier.align(Alignment.BottomCenter).widthIn(max = 560.dp).padding(bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val msg = c.message
            LaunchedEffect(msg) { if (msg != null) { delay(3000); if (c.message == msg) c.message = null } }
            BasicText(msg ?: c.tool.hint, style = if (msg != null) MapUi.bodyStrong else MapUi.small,
                modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).background(MapUi.surface).padding(horizontal = 10.dp, vertical = 6.dp))

            when (c.tool) {
                Tool.SELECT -> SelectionPanel(c)
                Tool.FURNITURE -> CatalogSheet(ic, c)
                Tool.PAINT -> PaintPanel(c)
                else -> Unit
            }

            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (t in INTERIOR_TOOLS) MapChip(t.label, primary = c.tool == t) { c.selectTool(t) }
            }
        }
    }
}

@Composable
private fun FloorButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(if (selected) MapUi.accent else MapUi.surface)
            .border(1.dp, MapUi.outline, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { BasicText(label, style = MapUi.bodyStrong.copy(color = if (selected) MapUi.accentInk else MapUi.ink)) }
}

@Composable
private fun SelectionPanel(c: EditorController) {
    val f = c.selectedFurniture
    val room = c.selectedRoom
    if (f == null && room == null) return
    MapCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(f?.let { c.catalog[it.catalogId]?.name } ?: room?.name ?: "", style = MapUi.bodyStrong, modifier = Modifier.weight(1f))
            if (f != null) MapChip("Rotate") { c.rotateSelected() }
            MapChip("Color") { c.recolorSelected() }
            MapChip("Delete") { c.deleteSelected() }
        }
        if (f != null) {
            BasicText("Zone (for avatars later): ${f.status?.label ?: "none"}", style = MapUi.small)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in Status.entries) MapChip(s.label, primary = f.status == s) { c.setStatusSelected(s) }
            }
        }
    }
}

@Composable
private fun PaintPanel(c: EditorController) {
    MapCard {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            for (m in FloorMaterial.entries) {
                val sel = c.paintMaterial == m
                Row(
                    Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                        .background(if (sel) MapUi.accent else MapUi.surface)
                        .border(1.dp, MapUi.outline, androidx.compose.foundation.shape.RoundedCornerShape(50))
                        .clickable { c.paintMaterial = m }.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(16.dp).clip(CircleShape).background(Color(m.color.r, m.color.g, m.color.b)).border(1.dp, MapUi.outline, CircleShape))
                    BasicText(m.label, style = MapUi.body.copy(color = if (sel) MapUi.accentInk else MapUi.ink))
                }
            }
        }
    }
}

@Composable
private fun CatalogSheet(ic: InteriorController, c: EditorController) {
    val scope = rememberCoroutineScope()
    var footprint by remember { mutableStateOf(2 to 2) }
    val picker = rememberFilePickerLauncher(type = glbPickerType) { file ->
        if (file == null) return@rememberFilePickerLauncher
        scope.launch {
            val size = runCatching { file.size() }.getOrNull()
            if (size != null && size > GlbLimits.Furniture.maxBytes) {
                c.message = dev.filamentiso.glb.GlbProblem.TooBig(size, GlbLimits.Furniture.maxBytes).message
            } else {
                ic.importFurniture(file.name, file.readBytes(), footprint.first, footprint.second)
            }
        }
    }
    MapCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val sel = c.catalogId?.let { c.catalog[it] }
            BasicText(sel?.let { "${it.name} · ${it.footprintW * 0.5f}×${it.footprintD * 0.5f} m" } ?: "Pick an item",
                style = MapUi.bodyStrong, modifier = Modifier.weight(1f))
            MapChip("Turn ${c.placeTurns * 90}°") { c.rotatePlacement() }
        }
        // One compact row, grouped by category (imported pieces first).
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (cat in c.catalog.categories) {
                BasicText(cat, style = MapUi.small)
                for (item in c.catalog.items.filter { it.category == cat }) {
                    MapChip(item.name, primary = c.catalogId == item.id) { c.catalogId = item.id }
                }
            }
        }
        // Import your own furniture (.glb, 5 MB / 15k triangles), auto-fitted to the chosen footprint.
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(if (ic.importing) "Importing…" else "Import", style = MapUi.small, modifier = Modifier.width(64.dp))
            for (fp in IMPORT_FOOTPRINTS) {
                MapChip("${fp.first * 0.5f}×${fp.second * 0.5f} m", primary = footprint == fp) { footprint = fp }
            }
            MapChip(".glb…", primary = true) { picker.launch() }
            for (s in SampleFiles.furniture) {
                MapChip(s.fileName) {
                    scope.launch { ic.importFurniture(s.fileName, Res.readBytes(SampleFiles.resourcePath(s.fileName)), footprint.first, footprint.second) }
                }
            }
        }
    }
}
