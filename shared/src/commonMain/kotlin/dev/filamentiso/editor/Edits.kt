package dev.filamentiso.editor

import dev.filamentiso.Rgb
import dev.filamentiso.model.Catalog
import dev.filamentiso.model.Edge
import dev.filamentiso.model.FurnitureItem
import dev.filamentiso.model.GridPoint
import dev.filamentiso.model.Level
import dev.filamentiso.model.OpeningType
import dev.filamentiso.model.Room
import dev.filamentiso.model.cells
import dev.filamentiso.model.newId
import kotlin.math.max
import kotlin.math.min

/*
 * Every edit is a pure function Level -> Level. No side effects, so undo is just keeping
 * the previous Level, and all of this is unit-testable without a renderer.
 */

val ROOM_COLORS = listOf(
    Rgb(0.80f, 0.72f, 0.60f), // oak
    Rgb(0.62f, 0.66f, 0.70f), // concrete
    Rgb(0.70f, 0.78f, 0.70f), // sage
    Rgb(0.82f, 0.70f, 0.66f), // terracotta
    Rgb(0.68f, 0.72f, 0.84f), // blue
    Rgb(0.86f, 0.82f, 0.68f), // sand
)

fun rectCells(from: GridPoint, to: GridPoint): Set<GridPoint> {
    val x0 = min(from.x, to.x); val x1 = max(from.x, to.x)
    val z0 = min(from.z, to.z); val z1 = max(from.z, to.z)
    return buildSet { for (x in x0..x1) for (z in z0..z1) add(GridPoint(x, z)) }
}

/** Edges on the boundary of a set of cells (between a member and a non-member). */
fun boundaryEdges(cells: Set<GridPoint>): Set<Edge> = buildSet {
    for (c in cells) {
        if (GridPoint(c.x, c.z - 1) !in cells) add(Edge.of(GridPoint(c.x, c.z), GridPoint(c.x + 1, c.z)))
        if (GridPoint(c.x, c.z + 1) !in cells) add(Edge.of(GridPoint(c.x, c.z + 1), GridPoint(c.x + 1, c.z + 1)))
        if (GridPoint(c.x - 1, c.z) !in cells) add(Edge.of(GridPoint(c.x, c.z), GridPoint(c.x, c.z + 1)))
        if (GridPoint(c.x + 1, c.z) !in cells) add(Edge.of(GridPoint(c.x + 1, c.z), GridPoint(c.x + 1, c.z + 1)))
    }
}

/** Edges strictly inside a set of cells (both neighbours are members). */
fun interiorEdges(cells: Set<GridPoint>): Set<Edge> = buildSet {
    for (c in cells) {
        if (GridPoint(c.x + 1, c.z) in cells) add(Edge.of(GridPoint(c.x + 1, c.z), GridPoint(c.x + 1, c.z + 1)))
        if (GridPoint(c.x, c.z + 1) in cells) add(Edge.of(GridPoint(c.x, c.z + 1), GridPoint(c.x + 1, c.z + 1)))
    }
}

/**
 * Adds a rectangular room. Cells it covers are taken from other rooms, walls inside the
 * rectangle are removed, and its border gets walls (shared walls merge automatically).
 */
fun Level.addRectRoom(from: GridPoint, to: GridPoint, name: String? = null, color: Rgb? = null): Level {
    val cells = rectCells(from, to).filter { inPlot(it) }.toSet()
    if (cells.isEmpty()) return this
    val inside = interiorEdges(cells)
    val others = rooms.map { it.copy(cells = it.cells - cells) }.filter { it.cells.isNotEmpty() }
    val room = Room(
        id = newId("room_"),
        name = name ?: "Room ${rooms.size + 1}",
        cells = cells,
        floorColor = color ?: ROOM_COLORS[rooms.size % ROOM_COLORS.size],
    )
    return copy(
        rooms = others + room,
        walls = (walls - inside) + boundaryEdges(cells),
        openings = openings - inside,
    )
}

/** Adds a straight wall between two grid vertices (forced horizontal or vertical). */
fun Level.addWallLine(from: GridPoint, to: GridPoint): Level {
    val edges = wallLineEdges(from, to).filter { e -> e.cellsBeside().toList().any { inPlot(it) } }
    return if (edges.isEmpty()) this else copy(walls = walls + edges)
}

/** Edges along the dominant axis from [from] to [to]. */
fun wallLineEdges(from: GridPoint, to: GridPoint): List<Edge> {
    val dx = to.x - from.x; val dz = to.z - from.z
    return if (kotlin.math.abs(dx) >= kotlin.math.abs(dz)) {
        (min(from.x, to.x) until max(from.x, to.x)).map { Edge.of(GridPoint(it, from.z), GridPoint(it + 1, from.z)) }
    } else {
        (min(from.z, to.z) until max(from.z, to.z)).map { Edge.of(GridPoint(from.x, it), GridPoint(from.x, it + 1)) }
    }
}

fun Level.removeWall(edge: Edge): Level =
    if (edge !in walls) this else copy(walls = walls - edge, openings = openings - openingGroup(edge))

/** The run of consecutive edges sharing one opening (a 1 m door is two 0.5 m edges). */
fun Level.openingGroup(edge: Edge): Set<Edge> {
    val type = openings[edge] ?: return emptySet()
    val group = mutableSetOf(edge)
    var e = edge.next(); while (openings[e] == type && e in walls) { group += e; e = e.next() }
    e = edge.previous(); while (openings[e] == type && e in walls) { group += e; e = e.previous() }
    return group
}

/**
 * Tap on a wall with the Door/Window tool:
 * - same opening there → remove it,
 * - other opening there → switch its type,
 * - plain wall → add an opening [widthCells] wide, starting at [edge] and extending along the wall.
 */
fun Level.toggleOpening(edge: Edge, type: OpeningType, widthCells: Int = 2): Level {
    if (edge !in walls) return this
    val existing = openings[edge]
    if (existing != null) {
        val group = openingGroup(edge)
        return if (existing == type) copy(openings = openings - group)
        else copy(openings = openings + group.associateWith { type })
    }
    // Prefer extending forward; fall back to backward if the wall ends.
    fun span(step: (Edge) -> Edge): List<Edge> {
        val out = mutableListOf(edge)
        var e = edge
        while (out.size < widthCells) {
            e = step(e)
            if (e !in walls || e in openings) break
            out += e
        }
        return out
    }
    val forward = span { it.next() }
    val chosen = if (forward.size == widthCells) forward else span { it.previous() }.takeIf { it.size > forward.size } ?: forward
    return copy(openings = openings + chosen.associateWith { type })
}

/** Removes the room containing [cell], furniture standing in it, and walls that only bounded it. */
fun Level.removeRoomAt(cell: GridPoint, catalog: Catalog): Level {
    val room = roomAt(cell) ?: return this
    val remaining = rooms - room
    val remainingCells = remaining.flatMap { it.cells }.toSet()
    val orphanWalls = boundaryEdges(room.cells).filter { e ->
        e.cellsBeside().toList().none { it in remainingCells }
    }.toSet()
    return copy(
        rooms = remaining,
        walls = walls - orphanWalls,
        openings = openings - orphanWalls,
        furniture = furniture.filter { f -> f.cells(catalog).none { it in room.cells } },
    )
}

fun Level.recolorRoom(roomId: String, color: Rgb): Level =
    copy(rooms = rooms.map { if (it.id == roomId) it.copy(floorColor = color) else it })

// ------------------------------------------------------------------ furniture

enum class PlaceProblem { UNKNOWN_ITEM, OUTSIDE_ROOM, OVERLAPS, CROSSES_WALL }

/** Why [item] can't stand where it is, or null if it can. [ignoreId] = the item being moved. */
fun Level.placementProblem(item: FurnitureItem, catalog: Catalog, ignoreId: String? = item.id): PlaceProblem? {
    val def = catalog[item.catalogId] ?: return PlaceProblem.UNKNOWN_ITEM
    val cells = item.cells(catalog)
    if (cells.any { !isRoomCell(it) }) return PlaceProblem.OUTSIDE_ROOM
    val occupied = furniture.asSequence()
        .filter { it.id != ignoreId }
        .filter { catalog[it.catalogId]?.floorLayer == def.floorLayer }
        .flatMap { it.cells(catalog) }
        .toSet()
    if (cells.any { it in occupied }) return PlaceProblem.OVERLAPS
    if (interiorEdges(cells).any { it in walls }) return PlaceProblem.CROSSES_WALL
    return null
}

fun Level.placeFurniture(item: FurnitureItem, catalog: Catalog): Level =
    if (placementProblem(item, catalog, ignoreId = null) != null) this else copy(furniture = furniture + item)

fun Level.updateFurniture(item: FurnitureItem, catalog: Catalog): Level =
    if (placementProblem(item, catalog) != null) this
    else copy(furniture = furniture.map { if (it.id == item.id) item else it })

fun Level.removeFurniture(id: String): Level = copy(furniture = furniture.filterNot { it.id == id })

/** Rotates 90° around (roughly) the footprint centre. Returns null if it wouldn't fit. */
fun Level.rotatedFurniture(item: FurnitureItem, catalog: Catalog): FurnitureItem? {
    val def = catalog[item.catalogId] ?: return null
    val (w, d) = def.footprint(item.quarterTurns)
    val turns = (item.quarterTurns + 1) % 4
    val (w2, d2) = def.footprint(turns)
    val cx2 = item.cell.x * 2 + w; val cz2 = item.cell.z * 2 + d // doubled centre, stays integer
    val candidate = item.copy(quarterTurns = turns, cell = GridPoint((cx2 - w2) / 2, (cz2 - d2) / 2))
    return candidate.takeIf { placementProblem(it, catalog) == null }
}

/** Anchor cell so that the footprint is centred under [center] (the finger's cell). */
fun anchorFor(center: GridPoint, def: dev.filamentiso.model.CatalogItem, quarterTurns: Int): GridPoint {
    val (w, d) = def.footprint(quarterTurns)
    return GridPoint(center.x - (w - 1) / 2, center.z - (d - 1) / 2)
}
