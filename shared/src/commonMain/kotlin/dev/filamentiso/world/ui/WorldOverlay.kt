package dev.filamentiso.world.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.filamentiso.IsoSceneConfig
import dev.filamentiso.IsoSceneView
import dev.filamentiso.Vec3
import dev.filamentiso.scene.SceneContent
import dev.filamentiso.scene.SceneModel
import dev.filamentiso.world.WorldController
import dev.filamentiso.world.geo.toLatLng
import dev.filamentiso.world.geo.toPosition
import dev.filamentiso.world.model.Building
import dev.filamentiso.world.overlay.MapView
import dev.filamentiso.world.overlay.OverlayProjector
import dev.filamentiso.world.overlay.ScreenEntity
import dev.filamentiso.world.overlay.ScreenPoint
import org.maplibre.compose.map.MapState

/** Sunlight in ground space (same as the iso view), turned with the map each frame. */
private val GROUND_SUN = Vec3(-0.75f, -1f, -0.2f)

/** Reads the map camera (Compose state, so this recomposes while the map moves). Null until laid out. */
@Composable
fun rememberMapView(mapState: MapState, sizePx: IntSize): MapView? {
    if (sizePx.width == 0 || sizePx.height == 0) return null
    val cam = mapState.cameraPosition
    val density = LocalDensity.current.density
    return MapView(
        widthPx = sizePx.width, heightPx = sizePx.height,
        zoom = cam.zoom, bearingDeg = cam.bearing, tiltDeg = cam.pitch,
        project = { p ->
            // Null before the map has a viewport: treat as far off screen.
            val o = mapState.screenLocationFromPosition(p.toPosition()) ?: return@MapView ScreenPoint(-1e6f, -1e6f)
            ScreenPoint(o.x.value * density, o.y.value * density)
        },
    )
}

/** Which buildings get a full 3D model this frame: zoomed in enough, on screen, nearest first. */
fun placeEntities(controller: WorldController, view: MapView, buildings: List<Building>): List<ScreenEntity> {
    if (view.zoom < WorldController.FULL_MODEL_MIN_ZOOM) return emptyList()
    val cx = view.widthPx / 2f; val cy = view.heightPx / 2f
    return buildings.mapNotNull { b ->
        val model = controller.resolvedModel(b) ?: return@mapNotNull null
        OverlayProjector.place(b.id, b.position, b.transform, model, view)
    }
        .sortedBy { (it.anchor.x - cx) * (it.anchor.x - cx) + (it.anchor.y - cy) * (it.anchor.y - cy) }
        .take(WorldController.MAX_FULL_MODELS)
}

/** The transparent 3D layer glued to the map: one model per building, pixel-space camera. */
fun overlayScene(entities: List<ScreenEntity>, view: MapView, highlightId: String?): Pair<SceneContent, IsoSceneConfig> {
    val content = SceneContent(entities.map { e ->
        SceneModel(
            key = e.id,
            source = e.model.source,
            transform = e.matrix,
            castShadows = false,
            tint = if (e.id == highlightId) HIGHLIGHT else null,
        )
    })
    val config = IsoSceneConfig(
        camera = OverlayProjector.camera(view.widthPx, view.heightPx),
        transparent = true,
        shadows = false,
        sunDirection = OverlayProjector.sunDirection(GROUND_SUN, view.bearingDeg, view.tiltDeg),
    )
    return content to config
}

private val HIGHLIGHT = dev.filamentiso.Rgb(0.75f, 0.95f, 0.85f)

/** Zoomed out: each building is a round bubble with a glyph, cheap to draw and easy to tap. */
@Composable
fun BuildingBubbles(view: MapView, buildings: List<Building>, onTap: (Building) -> Unit) {
    if (view.zoom >= WorldController.FULL_MODEL_MIN_ZOOM) return
    val density = LocalDensity.current.density
    val sizeDp = 40
    val half = (sizeDp * density / 2).toInt()
    Box(Modifier.fillMaxSize()) {
        for (b in buildings) {
            val p = view.project(b.position)
            if (p.x < -half || p.y < -half || p.x > view.widthPx + half || p.y > view.heightPx + half) continue
            Box(
                Modifier
                    .offset { IntOffset(p.x.toInt() - half, p.y.toInt() - half) }
                    .size(sizeDp.dp)
                    .shadow(4.dp, CircleShape)
                    .clip(CircleShape)
                    .background(MapUi.surface)
                    .border(2.dp, MapUi.accent, CircleShape)
                    .clickable { onTap(b) },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(emojiFor(b), style = MapUi.body.copy(fontSize = 18.sp))
            }
        }
    }
}

/** Glyphs that the default fonts have on both platforms (no colour emoji on Compose for Web). */
fun emojiFor(b: Building): String = if (b.asset != null) "★" else "⌂"

fun MapState.centerLatLng() = cameraPosition.center.toLatLng()
