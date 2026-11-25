package dev.filamentiso

import dev.filamentiso.model.GridPoint
import dev.filamentiso.scene.SceneGesture
import dev.filamentiso.storage.BlobAssetStore
import dev.filamentiso.storage.BlobBuildingRepository
import dev.filamentiso.storage.BlobPlotRepository
import dev.filamentiso.storage.InMemoryBlobStore
import dev.filamentiso.storage.WorldJson
import dev.filamentiso.world.DemoContent
import dev.filamentiso.world.ViewMode
import dev.filamentiso.world.WorldController
import dev.filamentiso.world.geo.LatLng
import dev.filamentiso.world.model.Building
import dev.filamentiso.world.overlay.ScreenPoint
import dev.filamentiso.world.scene.Ground
import dev.filamentiso.world.scene.GroundBuilder
import dev.filamentiso.world.scene.InteractionSpot
import dev.filamentiso.world.scene.Plots
import dev.filamentiso.world.scene.Scene
import dev.filamentiso.world.scene.SceneController
import dev.filamentiso.world.scene.SceneTool
import dev.filamentiso.world.scene.brushCells
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SceneTest {
    private val here = LatLng(36.0, 3.0)
    private fun g(x: Int, z: Int) = GridPoint(x, z)
    private fun world(blobs: InMemoryBlobStore) =
        WorldController(BlobBuildingRepository(blobs), BlobAssetStore(blobs), plotRepository = BlobPlotRepository(blobs))

    @Test
    fun oldSavesWithLevelsBecomeScenes() = runTest {
        val home = DemoContent.buildings().first { it.id == DemoContent.DEMO_HOME_ID }
        // Exactly what version 1 wrote: "floors" with levels and no "scenes".
        val old = home.copy(scenes = emptyList(), legacyFloors = home.floors, formatVersion = 1)
        val json = WorldJson.json.encodeToJsonElement(Building.serializer(), old).jsonObject
        val v1 = JsonObject(json - "scenes").toString()
        val blobs = InMemoryBlobStore()
        blobs.put("buildings/${home.id}.json", v1.encodeToByteArray())

        val loaded = assertNotNull(BlobBuildingRepository(blobs).get(home.id))
        assertEquals(home, loaded)
        assertEquals(home.floors, loaded.floors)
        assertTrue(loaded.legacyFloors.isEmpty())
    }

    @Test
    fun editingAFloorKeepsItsSpots() {
        val home = DemoContent.buildings().first { it.id == DemoContent.DEMO_HOME_ID }
        val spot = InteractionSpot("s", x = 1f, z = 1f, actionId = "SIT")
        val withSpot = home.copy(scenes = home.scenes.map { it.copy(spots = listOf(spot)) })
        assertEquals(listOf(spot), withSpot.withFloors(withSpot.floors).scenes[0].spots)
    }

    @Test
    fun paintStaysInsideAndIgnoresNoOps() {
        val p = Plots.new(here)
        val painted = p.paint(brushCells(g(0, 0), 3), Ground.WATER)
        assertEquals(4, painted.tiles.size, "a 3x3 brush in the corner covers 4 tiles")
        assertEquals(Ground.WATER, painted.groundAt(g(1, 1)))
        assertSame(painted, painted.paint(setOf(g(0, 0)), Ground.WATER))
        assertSame(p, p.paint(setOf(g(3, 3)), Ground.GRASS), "grass is the default")
        assertEquals(1, brushCells(g(5, 5), 1).size)
        assertFalse(Ground.WATER.walkable)
        assertTrue(GroundBuilder.build(painted).any { it.material.name.startsWith("ground_water") })
    }

    @Test
    fun plotSurvivesJson() {
        val p = Plots.new(here, 40).paint(setOf(g(2, 3)), Ground.SAND)
        assertEquals(p, WorldJson.json.decodeFromString(Scene.serializer(), WorldJson.json.encodeToString(Scene.serializer(), p)))
    }

    @Test
    fun cornersHaveTheRightSizeAndTurn() {
        val p = Plots.new(here, 40)
        val northWest = Plots.corners(p)[0]
        assertTrue(northWest.lat > here.lat && northWest.lng < here.lng)
        assertTrue(abs((northWest.lat - here.lat) * 111_320 - 20) < 0.01)
        // Turning 90° counter-clockwise (like buildings) brings the north-west corner to the south-west.
        val turned = Plots.corners(p.copy(headingDeg = 90f))[0]
        assertTrue(turned.lat < here.lat && turned.lng < here.lng)
    }

    @Test
    fun pointInPolygon() {
        val quad = listOf(ScreenPoint(0f, 0f), ScreenPoint(10f, 2f), ScreenPoint(12f, 10f), ScreenPoint(-2f, 8f))
        assertTrue(Plots.inside(ScreenPoint(5f, 5f), quad))
        assertFalse(Plots.inside(ScreenPoint(20f, 5f), quad))
        assertFalse(Plots.inside(ScreenPoint(5f, -1f), quad))
    }

    @Test
    fun claimSaveReloadOpenAndDelete() = runTest {
        val blobs = InMemoryBlobStore()
        val w = world(blobs)
        w.startClaim(here)
        w.setClaimSize(60)
        w.finishClaim()
        val p = w.plots.single()
        assertEquals(p.id, w.selectedPlot?.id)

        val again = world(blobs)
        again.load()
        assertEquals(p, again.plots.single())

        // Same transition as buildings.
        again.open(p)
        again.tick(1000f)
        assertIs<ViewMode.Iso>(again.viewMode)
        assertEquals(p, again.isoPlot)
        again.back()
        again.tick(1000f)
        assertEquals(ViewMode.Map, again.viewMode)
        assertEquals(p.id, again.selectedId)

        // An existing plot can move but not change size.
        again.startMovingPlot(p)
        again.setClaimSize(20)
        assertEquals(60, again.claiming?.width)
        again.cancelClaim()

        again.deletePlot(p)
        assertTrue(again.plots.isEmpty())
        assertTrue(BlobPlotRepository(blobs).list().isEmpty())
    }

    @Test
    fun editorPaintsWhereYouTapAndUndoes() {
        var saved: Scene? = null
        val c = SceneController(Plots.new(here)) { saved = it }
        c.setViewport(800, 600)
        c.ground = Ground.WATER
        val (x, y) = IsoCamera.worldToScreen(c.camera(), Vec3(5.5f, 0f, 7.5f), 800, 600)

        c.onGesture(SceneGesture.Tap(x, y))
        assertEquals(null, saved, "the View tool never paints")
        c.selectTool(SceneTool.PAINT)
        c.onGesture(SceneGesture.Tap(x, y))
        assertEquals(mapOf(g(5, 7) to Ground.WATER), saved?.tiles)
        c.undo()
        assertEquals(emptyMap(), saved?.tiles)
        c.redo()
        assertEquals(1, saved?.tiles?.size)

        // A cancelled stroke changes nothing.
        val before = saved
        c.onGesture(SceneGesture.DragStart(x + 40, y))
        c.onGesture(SceneGesture.DragMove(x + 80, y))
        c.onGesture(SceneGesture.DragCancel)
        assertSame(before, saved)
    }
}
