package dev.filamentiso

import dev.filamentiso.editor.History
import dev.filamentiso.editor.PlaceProblem
import dev.filamentiso.editor.addRectRoom
import dev.filamentiso.editor.addWallLine
import dev.filamentiso.editor.openingGroup
import dev.filamentiso.editor.placeFurniture
import dev.filamentiso.editor.placementProblem
import dev.filamentiso.editor.removeRoomAt
import dev.filamentiso.editor.removeWall
import dev.filamentiso.editor.rotatedFurniture
import dev.filamentiso.editor.toggleOpening
import dev.filamentiso.model.BuiltinCatalog
import dev.filamentiso.model.Catalog
import dev.filamentiso.model.Edge
import dev.filamentiso.model.FurnitureItem
import dev.filamentiso.model.GridPoint
import dev.filamentiso.model.Level
import dev.filamentiso.model.OpeningType
import dev.filamentiso.model.cells
import dev.filamentiso.storage.HouseJson
import dev.filamentiso.storage.Templates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditsTest {
    private val catalog = Catalog(BuiltinCatalog.items)
    private fun g(x: Int, z: Int) = GridPoint(x, z)

    @Test
    fun rectRoomGetsCellsAndBorderWalls() {
        val l = Level().addRectRoom(g(0, 0), g(3, 2)) // 4 x 3 cells
        assertEquals(12, l.rooms.single().cells.size)
        assertEquals(2 * (4 + 3), l.walls.size)
    }

    @Test
    fun adjacentRoomsShareOneWall() {
        val l = Level().addRectRoom(g(0, 0), g(3, 2)).addRectRoom(g(4, 0), g(7, 2))
        assertEquals(14 + 14 - 3, l.walls.size) // the 3-edge shared wall exists once
    }

    @Test
    fun overlappingRoomTakesCellsAndClearsInnerWalls() {
        val l = Level().addRectRoom(g(0, 0), g(3, 3)).addRectRoom(g(2, 0), g(5, 3))
        assertEquals(8, l.rooms[0].cells.size)
        assertEquals(16, l.rooms[1].cells.size)
        // No wall left inside the new room's rectangle.
        val inside = Edge.of(g(3, 1), g(3, 2))
        assertFalse(inside in l.walls)
    }

    @Test
    fun wallLineFollowsDominantAxis() {
        val l = Level().addWallLine(g(2, 2), g(6, 3))
        assertEquals(4, l.walls.size)
        assertTrue(l.walls.all { it.horizontal && it.a.z == 2 })
    }

    @Test
    fun doorSpansTwoEdgesAndTogglesOff() {
        val base = Level().addRectRoom(g(0, 0), g(5, 3))
        val e = Edge.of(g(1, 0), g(2, 0))
        val withDoor = base.toggleOpening(e, OpeningType.DOOR)
        assertEquals(2, withDoor.openings.size)
        assertEquals(setOf(e, e.next()), withDoor.openingGroup(e))
        // Tapping the second half removes the whole door.
        assertTrue(withDoor.toggleOpening(e.next(), OpeningType.DOOR).openings.isEmpty())
        // Window on a door switches type.
        assertTrue(withDoor.toggleOpening(e, OpeningType.WINDOW).openings.values.all { it == OpeningType.WINDOW })
    }

    @Test
    fun doorAtWallEndExtendsBackward() {
        val l = Level().addRectRoom(g(0, 0), g(3, 3)).toggleOpening(Edge.of(g(3, 0), g(4, 0)), OpeningType.DOOR)
        assertEquals(setOf(Edge.of(g(2, 0), g(3, 0)), Edge.of(g(3, 0), g(4, 0))), l.openings.keys)
    }

    @Test
    fun removingWallRemovesItsOpening() {
        val e = Edge.of(g(1, 0), g(2, 0))
        val l = Level().addRectRoom(g(0, 0), g(5, 3)).toggleOpening(e, OpeningType.WINDOW).removeWall(e)
        assertFalse(e in l.walls)
        assertTrue(l.openings.isEmpty())
    }

    @Test
    fun furnitureRules() {
        val l = Level().addRectRoom(g(0, 0), g(5, 5))
        val sofa = FurnitureItem("a", "sofa", g(0, 0))
        val placed = l.placeFurniture(sofa, catalog)
        assertEquals(1, placed.furniture.size)
        assertEquals(PlaceProblem.OVERLAPS, placed.placementProblem(sofa.copy(id = "b"), catalog))
        assertEquals(PlaceProblem.OUTSIDE_ROOM, l.placementProblem(sofa.copy(cell = g(4, 0)), catalog))
        // Rugs live on the floor layer: they may go under a sofa.
        assertNull(placed.placementProblem(FurnitureItem("r", "rug", g(0, 0)), catalog))
    }

    @Test
    fun furnitureCannotCrossAWall() {
        val l = Level().addRectRoom(g(0, 0), g(3, 3)).addRectRoom(g(4, 0), g(7, 3))
        assertEquals(PlaceProblem.CROSSES_WALL, l.placementProblem(FurnitureItem("s", "sofa", g(2, 0)), catalog))
    }

    @Test
    fun rotationSwapsFootprintAroundCentre() {
        val l = Level().addRectRoom(g(0, 0), g(7, 7))
        val sofa = FurnitureItem("s", "sofa", g(2, 3)) // 4 x 2
        val r = assertNotNull(l.placeFurniture(sofa, catalog).rotatedFurniture(sofa, catalog))
        assertEquals(1, r.quarterTurns)
        assertEquals(8, r.cells(catalog).size)
        val xs = r.cells(catalog).map { it.x }.toSet(); val zs = r.cells(catalog).map { it.z }.toSet()
        assertEquals(2, xs.size); assertEquals(4, zs.size)
    }

    @Test
    fun removingRoomRemovesItsFurnitureAndOrphanWalls() {
        val l = Level().addRectRoom(g(0, 0), g(3, 3)).addRectRoom(g(4, 0), g(7, 3))
            .placeFurniture(FurnitureItem("c", "chair", g(5, 1)), catalog)
            .removeRoomAt(g(5, 1), catalog)
        assertEquals(1, l.rooms.size)
        assertTrue(l.furniture.isEmpty())
        assertEquals(16, l.walls.size) // the remaining 4x4 room keeps all its walls, incl. the shared one
    }

    @Test
    fun historyUndoRedo() {
        val h = History(Level())
        assertTrue(h.apply { it.addRectRoom(g(0, 0), g(2, 2)) })
        assertFalse(h.apply { it }) // no-op isn't an undo step
        assertTrue(h.undo()); assertTrue(h.current.rooms.isEmpty())
        assertTrue(h.redo()); assertEquals(1, h.current.rooms.size)
        assertFalse(h.redo())
    }

    @Test
    fun sampleBasementPlacesEverythingAndRoundTripsJson() {
        val house = Templates.sampleBasement("Sample", catalog)
        assertEquals(4, house.level.rooms.size)
        assertEquals(25, house.level.furniture.size, "every sample item should fit")
        assertEquals(16, house.level.openings.size)
        val back = HouseJson.decode(HouseJson.encode(house))
        assertEquals(house, back)
    }
}
