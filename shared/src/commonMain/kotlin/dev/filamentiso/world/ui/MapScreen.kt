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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.filamentiso.resources.Res
import dev.filamentiso.world.geo.LatLng
import dev.filamentiso.world.geo.Places
import dev.filamentiso.world.geo.toPosition
import dev.filamentiso.world.platform.rememberMyLocation
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.style.BaseStyle

private const val STYLE_PATH = "files/pastel-style.json"
private const val OSM_COPYRIGHT = "https://www.openstreetmap.org/copyright"

/** The world map: pastel MapLibre map + HUD. The 3D world overlay is added on top in later steps. */
@Composable
fun MapScreen(onOpenTwins: () -> Unit) {
    val styleJson by produceState<String?>(null) {
        value = Res.readBytes(STYLE_PATH).decodeToString()
    }
    val json = styleJson
    if (json == null) {
        Box(Modifier.fillMaxSize().background(MapUi.loading))
        return
    }

    val mapState = rememberMapState(
        baseStyle = BaseStyle.Json(json),
        initialCameraPosition = CameraPosition(center = Places.Algiers.toPosition(), zoom = 14.5, pitch = 45.0),
    )
    Box(Modifier.fillMaxSize()) {
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            state = mapState,
            overlay = {}, // our own HUD below, with an always-visible attribution
        )
        MapHud(mapState, onOpenTwins)
    }
}

@Composable
private fun MapHud(mapState: MapState, onOpenTwins: () -> Unit) {
    val scope = rememberCoroutineScope()
    val myLocation = rememberMyLocation()
    val uriHandler = LocalUriHandler.current

    fun flyTo(target: LatLng, zoom: Double = 16.0) = scope.launch {
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
            MapRoundButton("⌂") { flyTo(Places.Algiers, 14.5) }
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
