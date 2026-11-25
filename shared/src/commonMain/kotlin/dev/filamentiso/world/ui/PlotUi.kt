package dev.filamentiso.world.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.filamentiso.scene.SceneGesture
import dev.filamentiso.world.TransitionLook
import dev.filamentiso.world.ViewModeMachine
import dev.filamentiso.world.WorldController
import dev.filamentiso.world.model.ModelTransform
import dev.filamentiso.world.overlay.MapView
import dev.filamentiso.world.overlay.ScreenPoint
import dev.filamentiso.world.scene.Ground
import dev.filamentiso.world.scene.Plots
import dev.filamentiso.world.scene.Scene
import dev.filamentiso.world.scene.SceneController
import dev.filamentiso.world.scene.SceneTool

/** Colours of plots on the map. */
private val PLOT_FILL = Color(0x5596CF8C)
private val PLOT_LINE = Color(0xFF7FB77A)

/** A plot's outline on screen this frame. */
class PlotOutline(val plot: Scene, val polygon: List<ScreenPoint>, val center: ScreenPoint)

/** The outlines of the last frame, for map taps (not state: it changes every frame). */
class PlotOutlineHolder {
    var value: List<PlotOutline> = emptyList()
    fun at(tap: ScreenPoint): String? = value.lastOrNull { Plots.inside(tap, it.polygon) }?.plot?.id
}

fun plotOutlines(view: MapView, plots: List<Scene>): List<PlotOutline> = plots.mapNotNull { p ->
    val center = view.project(p.anchor)
    val margin = maxOf(view.widthPx, view.heightPx)
    // Skips plots that are far away (or behind a tilted camera, which projects to huge numbers).
    if (center.x < -margin || center.y < -margin || center.x > view.widthPx + margin || center.y > view.heightPx + margin) null
    else PlotOutline(p, Plots.corners(p).map(view.project), center)
}

/** Soft outlined areas, drawn between the map and the 3D buildings. */
@Composable
fun PlotAreas(outlines: List<PlotOutline>, highlightId: String?) {
    Canvas(Modifier.fillMaxSize()) {
        for (o in outlines) {
            val path = Path().apply {
                o.polygon.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
                close()
            }
            val selected = o.plot.id == highlightId
            drawPath(path, PLOT_FILL)
            drawPath(path, if (selected) MapUi.accent else PLOT_LINE, style = Stroke((if (selected) 3 else 2).dp.toPx()))
        }
    }
}

@Composable
fun PlotIcons(outlines: List<PlotOutline>, view: MapView, onTap: (Scene) -> Unit) {
    val half = (15 * LocalDensity.current.density).toInt()
    Box(Modifier.fillMaxSize()) {
        for (o in outlines) {
            val p = o.center
            if (p.x < -half || p.y < -half || p.x > view.widthPx + half || p.y > view.heightPx + half) continue
            Box(
                Modifier.offset { IntOffset(p.x.toInt() - half, p.y.toInt() - half) }.size(30.dp).clip(CircleShape)
                    .background(MapUi.surface).border(2.dp, PLOT_LINE, CircleShape).clickable { onTap(o.plot) },
                contentAlignment = Alignment.Center,
            ) { BasicText("♣", style = MapUi.body) }
        }
    }
}

@Composable
fun ClaimPanel(world: WorldController, p: Scene, onDone: () -> Unit) {
    MapCard {
        BasicText("Drag the map to move your plot", style = MapUi.bodyStrong)
        if (!world.claimingExisting) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (m in Plots.sizes) MapChip("$m×$m m", primary = p.width == m) { world.setClaimSize(m) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MapChip("−15°") { world.rotateClaim(-ModelTransform.ROTATION_STEP) }
            BasicText("${p.headingDeg.toInt()}°", style = MapUi.body)
            MapChip("+15°") { world.rotateClaim(ModelTransform.ROTATION_STEP) }
            Box(Modifier.weight(1f))
            MapChip("Cancel") { world.cancelClaim() }
            MapChip(if (world.claimingExisting) "Done" else "Claim", primary = true, onClick = onDone)
        }
    }
}

@Composable
fun PlotSheet(p: Scene, onEnter: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    MapCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BasicText(p.name, style = MapUi.title)
                BasicText("Plot · ${p.width}×${p.depth} m", style = MapUi.small)
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

/** HUD of the plot's scene editor. Fades in at the end of the transition. */
@Composable
fun SceneHud(c: SceneController, world: WorldController) {
    val alpha = TransitionLook.isoHudAlpha(ViewModeMachine.isoAmount(world.viewMode))
    if (alpha <= 0f) return
    Box(Modifier.fillMaxSize().alpha(alpha).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)) {
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MapChip("‹ Map") { world.back() }
            Column(Modifier.weight(1f)) {
                BasicText(c.scene.name, style = MapUi.title)
                BasicText("${c.scene.width}×${c.scene.depth} m", style = MapUi.small)
            }
            MapChip("Undo") { if (c.canUndo) c.undo() }
            MapChip("Redo") { if (c.canRedo) c.redo() }
        }
        Column(Modifier.align(Alignment.CenterEnd), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MapRoundButton("⟳") { c.rotateView() }
            MapRoundButton("+") { c.onGesture(SceneGesture.Zoom(1.25f)) }
            MapRoundButton("−") { c.onGesture(SceneGesture.Zoom(0.8f)) }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).widthIn(max = 560.dp).padding(bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            world.message?.let { msg -> Toast(msg) { world.message = null } }
            BasicText(
                c.tool.hint, style = MapUi.small,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(MapUi.surface).padding(horizontal = 10.dp, vertical = 6.dp),
            )
            if (c.tool == SceneTool.PAINT) MapCard {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (g in Ground.outdoor) MapChip(g.label, primary = c.ground == g) { c.ground = g }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BasicText("Brush", style = MapUi.small)
                    for (b in listOf(1, 3)) MapChip("$b", primary = c.brush == b) { c.brush = b }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (t in SceneTool.entries) MapChip(t.label, primary = c.tool == t) { c.selectTool(t) }
            }
        }
    }
}
