package dev.filamentiso

import dev.filamentiso.editor.PlaceProblem
import dev.filamentiso.editor.cellLine
import dev.filamentiso.editor.paintTiles
import dev.filamentiso.editor.placeFurniture
import dev.filamentiso.editor.placementProblem
import dev.filamentiso.editor.removeRoomAt
import dev.filamentiso.editor.setStatus
import dev.filamentiso.editor.toggleOpening
import dev.filamentiso.editor.toggleWall
import dev.filamentiso.geometry.SampleBuildings
import dev.filamentiso.model.BuiltinCatalog
import dev.filamentiso.model.Catalog
import dev.filamentiso.model.CatalogItem
import dev.filamentiso.model.Edge
import dev.filamentiso.model.FloorMaterial
import dev.filamentiso.model.FurnitureItem
import dev.filamentiso.model.GridPoint
import dev.filamentiso.model.OpeningType
import dev.filamentiso.model.Status
import dev.filamentiso.storage.BlobAssetStore
import dev.filamentiso.storage.BlobBuildingRepository
import dev.filamentiso.storage.InMemoryBlobStore
import dev.filamentiso.storage.WorldJson
import dev.filamentiso.world.DemoContent
import dev.filamentiso.world.InteriorController
import dev.filamentiso.world.WorldController
import dev.filamentiso.world.model.Building
import dev.filamentiso.world.model.ImportedFurniture
import dev.filamentiso.world.model.ModelAsset
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InteriorTest {
    private fun g(x: Int, z: Int) = GridPoint(x, z)
    private val catalog = Catalog(BuiltinCatalog.items)
    private val floor = InteriorController.newFloor(0, 10, 8)

    @Test
    fun newFloorIsOneRoomWithWallsAndAFrontDoor() {
        assertEquals(1, floor.rooms.size)
        assertEquals(80, floor.rooms[0].cells.size)
        assertEquals(2 * (10 + 8), floor.walls.size)
        assertTrue(floor.openings.values.all { it == OpeningType.DOOR } && floor.openings.isNotEmpty())
        assertTrue(floor.openings.keys.all { it.a.z == 8 }, "door on the front (+Z) wall")
        assertTrue(InteriorController.newFloor(1, 10, 8).openings.isEmpty(), "no front door upstairs")
    }

    @Test
    fun plotSizeFollowsFootprintWithinLimits() {
        assertEquals(20 to 16, InteriorController.plotCells(10f, 8f))
        assertEquals(InteriorController.MIN_CELLS to InteriorController.MIN_CELLS, InteriorController.plotCells(1f, 0f))
        assertEquals(InteriorController.MAX_CELLS to 21, InteriorController.plotCells(500f, 10.2f))
    }

    @Test
    fun paintOnlyInsideRoomsAndNoOpIfSame() {
        val painted = floor.paintTiles(setOf(g(0, 0), g(1, 0), g(50, 50)), FloorMaterial.TILE)
        assertEquals(mapOf(g(0, 0) to FloorMaterial.TILE, g(1, 0) to FloorMaterial.TILE), painted.tiles)
        assertSame(painted, painted.paintTiles(setOf(g(0, 0)), FloorMaterial.TILE))
        // Removing the room forgets its paint.
        assertTrue(painted.removeRoomAt(g(0, 0), catalog).tiles.isEmpty())
    }

    @Test
    fun tapToggleWallAddsAndRemovesWithOpenings() {
        val e = Edge.of(g(3, 4), g(4, 4))
        val withWall = floor.toggleWall(e)
        assertTrue(e in withWall.walls)
        val withDoor = withWall.toggleOpening(e, OpeningType.WINDOW)
        assertTrue(withDoor.openings.isNotEmpty())
        val removed = withDoor.toggleWall(e)
        assertFalse(e in removed.walls)
        assertNull(removed.openings[e])
        // Outside the plot: ignored.
        assertSame(floor, floor.toggleWall(Edge.of(g(40, 0), g(41, 0))))
    }

    @Test
    fun placementCollisionsAndBounds() {
        val sofa = FurnitureItem("s", "sofa", g(2, 2))               // 4 x 2 cells
        val l = floor.placeFurniture(sofa, catalog)
        assertEquals(1, l.furniture.size)
        assertEquals(PlaceProblem.OVERLAPS, l.placementProblem(FurnitureItem("c", "chair", g(3, 3)), catalog))
        assertEquals(PlaceProblem.OUTSIDE_ROOM, l.placementProblem(FurnitureItem("c", "chair", g(10, 3)), catalog))
        assertEquals(PlaceProblem.OUTSIDE_ROOM, l.placementProblem(FurnitureItem("c", "chair", g(-1, 0)), catalog))
        assertNull(l.placementProblem(FurnitureItem("c", "chair", g(6, 2)), catalog))
        // A wall across the footprint blocks it.
        val walled = floor.toggleWall(Edge.of(g(3, 2), g(3, 3)))
        assertEquals(PlaceProblem.CROSSES_WALL, walled.placementProblem(sofa, catalog))
    }

    @Test
    fun statusIsStoredPerItem() {
        val l = floor.placeFurniture(FurnitureItem("d", "desk", g(0, 0)), catalog).setStatus("d", Status.WORKING)
        assertEquals(Status.WORKING, l.furniture.single().status)
        assertNull(l.setStatus("d", null).furniture.single().status)
    }

    @Test
    fun strokeCellsHaveNoGaps() {
        val line = cellLine(g(0, 0), g(5, 2))
        assertEquals(g(0, 0), line.first()); assertEquals(g(5, 2), line.last())
        for (i in 1 until line.size) {
            val a = line[i - 1]; val b = line[i]
            assertTrue(kotlin.math.abs(a.x - b.x) <= 1 && kotlin.math.abs(a.z - b.z) <= 1)
        }
    }

    @Test
    fun buildingWithInteriorSurvivesJson() {
        val f = floor.paintTiles(setOf(g(1, 1)), FloorMaterial.CARPET)
            .placeFurniture(FurnitureItem("t", "table", g(4, 4)), catalog).setStatus("t", Status.EATING)
        val asset = ModelAsset("a1", "local", "chair.glb", 1234, 100, Vec3(0f, 0f, 0f), Vec3(1f, 1f, 1f), "assets/a1.glb")
        val b = DemoContent.buildings().first().copy(
            floors = listOf(f, InteriorController.newFloor(1, 10, 8)),
            importedFurniture = listOf(ImportedFurniture(CatalogItem("asset:a1", "chair", "Imported", 2, 2, 0.9f, assetId = "a1"), asset)),
        )
        val text = WorldJson.json.encodeToString(Building.serializer(), b)
        assertEquals(b, WorldJson.json.decodeFromString(Building.serializer(), text))
    }

    @Test
    fun floorsCanBeAddedUpToFiveAndRemovedFromTheTop() = runTest {
        val blobs = InMemoryBlobStore()
        val world = WorldController(BlobBuildingRepository(blobs), BlobAssetStore(blobs))
        world.startNewBuilding(dev.filamentiso.world.geo.LatLng(36.0, 3.0), SampleBuildings.Kind.COTTAGE)
        world.finishPlacing()
        val ic = InteriorController(world, world.buildings.single().id, this)
        assertEquals(1, ic.floorCount)
        repeat(10) { ic.addFloor() }
        assertEquals(InteriorController.MAX_FLOORS, ic.floorCount)
        assertEquals(4, ic.floorIndex, "jumps to the new top floor")
        ic.removeTopFloor()
        assertEquals(4, ic.floorCount)
        assertEquals(3, ic.floorIndex)
        testScheduler.advanceUntilIdle()
        assertEquals(4, world.buildings.single().floors.size, "saved")
    }
}

class DemoContentTest {
    @Test
    fun demoCottageIsFullyFurnished() {
        val home = DemoContent.buildings().first { it.id == DemoContent.DEMO_HOME_ID }
        assertEquals(2, home.floors.size)
        // placeFurniture silently skips items that don't fit: every demo item must have made it.
        assertEquals(16, home.floors[0].furniture.size, "ground floor items")
        assertEquals(6, home.floors[1].furniture.size, "upstairs items")
        assertTrue(home.floors[0].furniture.any { it.status == Status.WORKING })
        assertTrue(home.floors[0].tiles.isNotEmpty())
    }
}
