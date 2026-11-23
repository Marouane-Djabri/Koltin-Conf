package dev.filamentiso.world.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.filamentiso.resources.Res
import dev.filamentiso.world.WorldController
import dev.filamentiso.world.ViewMode
import dev.filamentiso.world.IsoSub
import dev.filamentiso.world.InteriorController
import dev.filamentiso.PlatformBackHandler
import androidx.compose.runtime.withFrameNanos
import dev.filamentiso.world.geo.LatLng
import dev.filamentiso.world.geo.Places
import dev.filamentiso.world.geo.toPosition
import dev.filamentiso.world.model.Building
import dev.filamentiso.world.overlay.OverlayProjector
import dev.filamentiso.world.overlay.ScreenPoint
import dev.filamentiso.world.platform.rememberMyLocation
import dev.filamentiso.world.platform.rememberOnline
import dev.filamentiso.world.platform.OFFLINE_MESSAGE
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.style.BaseStyle

private const val STYLE_PATH = "files/pastel-style.json"
private const val OSM_COPYRIGHT = "https://www.openstreetmap.org/copyright"

/**
 * The world map: pastel MapLibre map, the transparent 3D overlay with the buildings, and the HUD.
 * Tapping a building's "Enter" switches to the isometric view (see [WorldScene], [ViewModeMachine]).
 */
@Composable
fun MapScreen(world: WorldController, onOpenTwins: () -> Unit) {
    val styleJson by produceState<String?>(null) {
        value = Res.readBytes(STYLE_PATH).decodeToString()
    }
    LaunchedEffect(world) { world.load() }
    val json = styleJson
    if (json == null) {
        Box(Modifier.fillMaxSize().background(MapUi.loading))
        return
    }

    val mapState = rememberMapState(
        baseStyle = BaseStyle.Json(json),
        initialCameraPosition = CameraPosition(center = Places.Algiers.toPosition(), zoom = 18.3, pitch = 50.0),
    )
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current.density
    val view = rememberMapView(mapState, sizePx)

    // While placing, the building sits at the map centre: pan the map to move it.
    val placing = world.placing
    if (placing != null) {
        val center = mapState.centerLatLng()
        if (center != placing.position) world.moveTo(center)
    }
    val shown = world.buildings.filter { it.id != placing?.id } + listOfNotNull(placing)
    val entities = view?.let { placeEntities(world, it, shown) } ?: emptyList()
    LaunchedEffect(shown.mapNotNull { it.asset?.id }.toSet()) { world.loadMissing(shown) }

    val interactions = remember(world) {
        MapInteractions(MapInteractions.Standard) {
            callbacks {
                click {
                    onEvent { e ->
                        if (world.placing != null) return@onEvent ClickResult.Pass
                        val tap = ScreenPoint(e.screenOffset.x.value * density, e.screenOffset.y.value * density)
                        val hit = OverlayProjector.hitTest(world.lastEntities, tap)
                        world.selectedId = hit?.id
                        if (hit != null) ClickResult.Consume else ClickResult.Pass
                    }
                }
            }
        }
    }
    world.lastEntities = entities

    // ---- Map ↔ iso transition: shared state machine, driven by real frame time.
    val mode = world.viewMode
    val transitioning = mode is ViewMode.TransitionToIso || mode is ViewMode.TransitionToMap
    LaunchedEffect(transitioning) {
        if (!transitioning) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (world.viewMode.let { it is ViewMode.TransitionToIso || it is ViewMode.TransitionToMap }) {
            val now = withFrameNanos { it }
            world.tick((now - last) / 1_000_000f)
            last = now
        }
    }
    // The map camera centres and zooms on the building, and returns exactly where it was afterwards.
    var returnCamera by remember { mutableStateOf<CameraPosition?>(null) }
    LaunchedEffect(mode::class) {
        val ease = CameraAnimation.Ease(CameraAnimation.Ease.Standard) { duration = 360.milliseconds }
        when (mode) {
            is ViewMode.TransitionToIso -> {
                val b = world.isoBuilding ?: return@LaunchedEffect
                if (returnCamera == null) returnCamera = mapState.cameraPosition
                val zoom = maxOf(mapState.cameraPosition.zoom, 18.8)
                mapState.animateCamera(CameraUpdate(center = b.position.toPosition(), zoom = zoom), ease)
            }
            is ViewMode.TransitionToMap -> {
                returnCamera?.let { mapState.animateCamera(it.toCameraUpdate(), ease) }
                returnCamera = null
            }
            else -> Unit
        }
    }
    PlatformBackHandler(enabled = mode != ViewMode.Map) { world.back() }

    // Inside a building: the interior editor takes over the iso view (scene, camera, gestures).
    val interiorScope = rememberCoroutineScope()
    val interiorId = (mode as? ViewMode.Iso)?.takeIf { it.sub == IsoSub.INTERIOR }?.buildingId
    val interior = interiorId?.let { id -> remember(id) { InteriorController(world, id, interiorScope) } }
    val extras = interior?.let { ic -> IsoExtras(ic.sceneContent(), ic.config(), ic.editor::onGesture, ic.editor::setViewport) } ?: IsoExtras()

    Box(Modifier.fillMaxSize().onSizeChanged { sizePx = it }) {
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            state = mapState,
            interactions = interactions,
            overlay = {}, // our own HUD below, with an always-visible attribution
        )
        if (view != null) {
            WorldScene(world, view, entities, extras)
            if (mapHudVisible(mode)) BuildingBubbles(view, shown) { world.selectedId = it.id }
        }
        when {
            mapHudVisible(mode) -> MapHud(mapState, world, onOpenTwins)
            interior != null -> InteriorHud(interior, world)
            else -> IsoHud(world)
        }
    }
}

@Composable
private fun MapHud(mapState: MapState, world: WorldController, onOpenTwins: () -> Unit) {
    val scope = rememberCoroutineScope()
    val myLocation = rememberMyLocation()
    val online = rememberOnline()
    LaunchedEffect(online) { if (!online) world.message = OFFLINE_MESSAGE }
    val uriHandler = LocalUriHandler.current

    fun flyTo(target: LatLng, zoom: Double = 17.0) = scope.launch {
        mapState.animateCamera(CameraUpdate(center = target.toPosition(), zoom = zoom), CameraAnimation.Fly.Standard)
    }

    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)) {
        Row(Modifier.align(Alignment.TopStart), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MapChip("My twins", onClick = onOpenTwins)
        }

        Column(Modifier.align(Alignment.CenterEnd), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Compass: shows north, tap to face north again.
            MapRoundButton("N") {
                scope.launch {
                    mapState.animateCamera(CameraUpdate(bearing = 0.0), CameraAnimation.Ease(CameraAnimation.Ease.Standard) { duration = 400.milliseconds })
                }
            }
            MapRoundButton("◎") { myLocation.request { it?.let(::flyTo) } }
            MapRoundButton("⌂") { flyTo(Places.Algiers, 18.3) }
        }

        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            world.message?.let { msg -> Toast(msg) { world.message = null } }
            val placing = world.placing
            val selected = world.selected
            when {
                placing != null -> PlacementPanel(world, placing, onDone = { scope.launch { world.finishPlacing() } })
                selected != null -> BuildingSheet(
                    selected,
                    onEnter = { world.open(selected) },
                    onMove = {
                        world.startPlacing(selected)
                        flyTo(selected.position, mapState.cameraPosition.zoom.coerceAtLeast(17.0))
                    },
                    onDelete = { scope.launch { world.delete(selected) } },
                    onClose = { world.selectedId = null },
                )
                else -> MapChip("+ Build here", primary = true) {
                    val z = mapState.cameraPosition.zoom
                    if (z < WorldController.FULL_MODEL_MIN_ZOOM) flyTo(mapState.centerLatLng(), 17.0)
                    world.startNewBuilding(mapState.centerLatLng())
                }
            }
        }

        // OSM attribution: always visible (ODbL requirement), tap for the copyright page.
        BasicText(
            "© OpenStreetMap contributors · OpenFreeMap · MapLibre",
            style = MapUi.tiny,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .clip(RoundedCornerShape(6.dp))
                .background(MapUi.surface)
                .clickable { uriHandler.openUri(OSM_COPYRIGHT) }
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}
