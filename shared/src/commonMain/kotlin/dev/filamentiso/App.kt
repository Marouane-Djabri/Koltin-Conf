package dev.filamentiso

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import dev.filamentiso.editor.EditorController
import dev.filamentiso.model.BuiltinCatalog
import dev.filamentiso.model.Catalog
import dev.filamentiso.model.CatalogItem
import dev.filamentiso.model.House
import dev.filamentiso.scene.ModelSource
import dev.filamentiso.scene.SceneContent
import dev.filamentiso.scene.SceneGesture
import dev.filamentiso.scene.SceneModel
import dev.filamentiso.storage.HouseStore
import dev.filamentiso.storage.Templates
import dev.filamentiso.ui.EditorScreen
import dev.filamentiso.ui.Pill
import dev.filamentiso.ui.Ui
import dev.filamentiso.world.ui.MapScreen
import dev.filamentiso.world.WorldController
import dev.filamentiso.world.DemoContent
import dev.filamentiso.storage.BlobStore
import dev.filamentiso.storage.BlobAssetStore
import dev.filamentiso.storage.BlobBuildingRepository
import dev.filamentiso.storage.BlobPlotRepository

/**
 * Shared app: world map → house list → editor, plus the original isoroom.glb viewer.
 * [extraCatalog] (e.g. Blender models listed in assets/furniture/catalog.json) is merged
 * over the built-in placeholders; an entry with the same id replaces the built-in one.
 */
@Composable
fun App(store: HouseStore, blobs: BlobStore, extraCatalog: List<CatalogItem> = emptyList()) {
    val world = remember(blobs) { WorldController(BlobBuildingRepository(blobs), BlobAssetStore(blobs), DemoContent.firstRunFlag(blobs), BlobPlotRepository(blobs)) }
    val catalog = remember(extraCatalog) { Catalog(extraCatalog + BuiltinCatalog.items) }
    var houses by remember { mutableStateOf(store.list()) }
    // "map", "home", "demo" or "edit:<houseId>" — a String so it survives rotation.
    var screen by rememberSaveable { mutableStateOf("map") }

    fun refresh() { houses = store.list() }

    PlatformBackHandler(enabled = screen != "map") { refresh(); screen = if (screen == "home") "map" else "home" }

    when {
        screen == "map" -> MapScreen(world, onOpenTwins = { refresh(); screen = "home" })
        screen == "demo" -> DemoScreen(onBack = { screen = "home" })
        screen.startsWith("edit:") -> {
            val id = screen.removePrefix("edit:")
            val house = remember(id) { store.list().firstOrNull { it.id == id } }
            if (house == null) { screen = "home"; return }
            val controller = remember(id) { EditorController(house, catalog, onSave = { store.save(it) }) }
            EditorScreen(controller, onBack = { refresh(); screen = "home" })
        }
        else -> HomeScreen(
            houses = houses,
            onOpen = { screen = "edit:${it.id}" },
            onNewEmpty = {
                val h = Templates.emptyBasement("Basement ${houses.size + 1}")
                store.save(h); refresh(); screen = "edit:${h.id}"
            },
            onNewSample = {
                val h = Templates.sampleBasement("Sample basement", catalog)
                store.save(h); refresh(); screen = "edit:${h.id}"
            },
            onDelete = { store.delete(it.id); refresh() },
            onDemo = { screen = "demo" },
            onMap = { screen = "map" },
        )
    }
}

@Composable
private fun HomeScreen(
    houses: List<House>,
    onOpen: (House) -> Unit,
    onNewEmpty: () -> Unit,
    onNewSample: () -> Unit,
    onDelete: (House) -> Unit,
    onDemo: () -> Unit,
    onMap: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().background(Ui.bg).windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Pill("‹ Map", onClick = onMap)
        BasicText("Digital twins", style = Ui.title)
        BasicText("Create an isometric twin of your basement: draw rooms, add doors and windows, furnish it.", style = Ui.small)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("+ New basement", selected = true, onClick = onNewEmpty)
            Pill("+ Sample basement", onClick = onNewSample)
        }
        if (houses.isEmpty()) BasicText("No twins yet. Start with the sample to see what's possible.", style = Ui.small)
        for (h in houses) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ui.panelSolid).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    BasicText(h.name, style = Ui.heading)
                    BasicText("${h.level.rooms.size} rooms · ${h.level.furniture.size} items", style = Ui.small)
                }
                if (confirmDelete == h.id) {
                    Pill("Delete?", selected = true, color = Ui.danger) { confirmDelete = null; onDelete(h) }
                    Pill("Keep") { confirmDelete = null }
                } else {
                    Pill("Delete") { confirmDelete = h.id }
                    Pill("Open", selected = true) { onOpen(h) }
                }
            }
        }
        Box(Modifier.padding(top = 12.dp)) {
            Pill("isoroom.glb demo (first test)", onClick = onDemo)
        }
    }
}

/** The original viewer: a Blender .glb from assets, drag = rotate, pinch = zoom, two fingers = pan. */
@Composable
private fun DemoScreen(onBack: () -> Unit) {
    var yaw by rememberSaveable { mutableFloatStateOf(45f) }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Vec3(0f, 0f, 0f)) }
    var lastX by remember { mutableFloatStateOf(0f) }
    var size by remember { mutableStateOf(1 to 1) }
    val content = remember {
        SceneContent(listOf(SceneModel(
            key = "isoroom",
            source = ModelSource.Asset("isoroom.glb"),
            hiddenNodes = IsoRoomFixups.hiddenNodes,
            matteMaterials = IsoRoomFixups.matteMaterials,
        )))
    }
    val config = IsoSceneConfig(yawDegrees = yaw, zoom = zoom, pan = pan)
    // Approximate bounds of isoroom.glb (measured in Step 1), only used to scale panning.
    val roomBounds = Bounds(Vec3(0f, 3.2f, 0f), Vec3(5.8f, 3.2f, 5.8f))

    Box(Modifier.fillMaxSize().background(Ui.bg)) {
        IsoSceneView(
            content = content,
            config = config,
            onGesture = { g ->
                when (g) {
                    is SceneGesture.DragStart -> lastX = g.x
                    is SceneGesture.DragMove -> { yaw = config.withYaw(yaw - (g.x - lastX) * 180f / size.first).yawDegrees; lastX = g.x }
                    is SceneGesture.Zoom -> zoom = config.withZoom(zoom * g.factor).zoom
                    is SceneGesture.Pan -> {
                        val s = IsoCamera.compute(config, roomBounds, size.first.toFloat() / size.second)
                        pan += IsoCamera.panDelta(s, g.dx, g.dy, size.first, size.second)
                    }
                    else -> Unit
                }
            },
            modifier = Modifier.fillMaxSize().onSizeChanged { size = it.width to it.height },
        )
        Row(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(10.dp)) { Pill("‹ Back", onClick = onBack) }
        BasicText(
            "yaw ${yaw.toInt()}°  ·  zoom ${(zoom * 100).toInt()}%",
            style = Ui.small,
            modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
        )
    }
}
