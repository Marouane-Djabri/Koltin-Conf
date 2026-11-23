package dev.filamentiso.world.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import dev.filamentiso.IsoSceneView
import dev.filamentiso.scene.SceneContent
import dev.filamentiso.scene.SceneGesture
import dev.filamentiso.scene.SceneModel
import dev.filamentiso.world.IsoStage
import dev.filamentiso.world.IsoSub
import dev.filamentiso.world.TransitionLook
import dev.filamentiso.world.ViewMode
import dev.filamentiso.world.ViewModeMachine
import dev.filamentiso.world.WorldController
import dev.filamentiso.world.overlay.MapView
import dev.filamentiso.world.overlay.ScreenEntity
import kotlin.math.abs

/** Soft pastel sky → cream gradient behind the iso diorama. */
private val BACKDROP = Brush.verticalGradient(listOf(Color(0xFFDCEBFA), Color(0xFFF3E9F6), Color(0xFFFCF5EA)))

/** What the iso view adds on top of the exterior scene (the interior editor plugs in here). */
class IsoExtras(
    val models: List<SceneModel> = emptyList(),
    /** Hide the building shell (interior mode). */
    val hideShell: Boolean = false,
    /** Takes over gestures (interior editing). Return true when handled. */
    val onGesture: ((SceneGesture) -> Boolean)? = null,
)

/**
 * The 3D layer of the world screen, for every [ViewMode]. A single [IsoSceneView] instance is kept
 * for all modes, so the engine (Filament / three.js) is never re-created: on the map it draws the
 * map-anchored overlay, in the iso view the building diorama. Below it, the pastel backdrop fades
 * in over the map during the transition.
 */
@Composable
fun WorldScene(world: WorldController, view: MapView, entities: List<ScreenEntity>, extras: IsoExtras = IsoExtras()) {
    val mode = world.viewMode
    val t = ViewModeMachine.isoAmount(mode)
    val building = world.isoBuilding
    val model = building?.let { world.resolvedModel(it) }
    val yaw by animateFloatAsState(IsoStage.yawForStep(world.isoStep), tween(450))
    val zoom by animateFloatAsState(world.isoZoom, tween(120))

    if (t > 0f) Box(Modifier.fillMaxSize().alpha(TransitionLook.backdropAlpha(t)).background(BACKDROP))

    val iso = building != null && model != null && TransitionLook.showIsoScene(t)
    val content: SceneContent
    val config: dev.filamentiso.IsoSceneConfig
    if (iso) {
        val m = IsoStage.buildingMatrix(building!!, model!!)
        val bounds = IsoStage.bounds(m, model.bboxMin, model.bboxMax)
        val shell = SceneModel(key = "iso_building", source = model.source, transform = m)
        content = SceneContent(
            models = listOf(IsoStage.ground(bounds)) + (if (extras.hideShell) emptyList() else listOf(shell)) + extras.models,
            fitBounds = IsoStage.frame(bounds),
        )
        config = IsoStage.config(yaw, zoom * TransitionLook.isoZoomFactor(t))
    } else {
        val highlight = world.selectedId ?: world.placing?.id ?: mode.buildingIdOrNull
        val (c, cfg) = overlayScene(entities, view, highlight)
        content = c; config = cfg
    }

    // Swipe left/right orbits by 90°, pinch zooms (pinch out far enough = back to the map).
    var swipeStartX by remember { mutableFloatStateOf(0f) }
    val gestures: ((SceneGesture) -> Unit)? = if (mode is ViewMode.Iso) { g ->
        if (extras.onGesture?.invoke(g) != true) when (g) {
            is SceneGesture.DragStart -> swipeStartX = g.x
            is SceneGesture.DragEnd -> {
                val dx = g.x - swipeStartX
                if (abs(dx) > 60f) world.orbit(if (dx > 0) -1 else 1)
            }
            is SceneGesture.Zoom -> world.zoomIso(g.factor)
            else -> Unit
        }
    } else null

    IsoSceneView(content, config, onGesture = gestures, modifier = Modifier.fillMaxSize())
}

/** True when the map HUD should be visible (hidden while the iso view covers the map). */
fun mapHudVisible(mode: ViewMode) = mode == ViewMode.Map

fun isInterior(mode: ViewMode) = mode is ViewMode.Iso && mode.sub == IsoSub.INTERIOR
