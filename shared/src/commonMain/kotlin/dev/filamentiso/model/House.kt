package dev.filamentiso.model

import dev.filamentiso.Rgb
import dev.filamentiso.Vec3
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.random.Random

/** A grid cell (or a grid vertex, depending on context). World metres = index * cellSize. */
@Serializable
data class GridPoint(val x: Int, val z: Int) {
    operator fun plus(o: GridPoint) = GridPoint(x + o.x, z + o.z)
}

/**
 * One grid-cell-long piece of wall on a grid line. Always normalised so that [a] < [b],
 * which makes a `Set<Edge>` merge the shared wall between two rooms automatically.
 *
 * Horizontal edge (a.z == b.z) at line z separates cell (x, z-1) [north] from (x, z) [south].
 * Vertical edge (a.x == b.x) at line x separates cell (x-1, z) [west] from (x, z) [east].
 */
@Serializable
data class Edge(val a: GridPoint, val b: GridPoint) {
    init {
        require(abs(a.x - b.x) + abs(a.z - b.z) == 1) { "An edge is exactly one cell long: $a-$b" }
        require(a.x < b.x || a.z < b.z) { "Use Edge.of() so the edge is normalised: $a-$b" }
    }

    val horizontal: Boolean get() = a.z == b.z

    /** The two cells on either side: (north, south) or (west, east). */
    fun cellsBeside(): Pair<GridPoint, GridPoint> =
        if (horizontal) GridPoint(a.x, a.z - 1) to GridPoint(a.x, a.z)
        else GridPoint(a.x - 1, a.z) to GridPoint(a.x, a.z)

    /** The next edge along the same line (east for horizontal, south for vertical). */
    fun next(): Edge = if (horizontal) of(b, GridPoint(b.x + 1, b.z)) else of(b, GridPoint(b.x, b.z + 1))
    fun previous(): Edge = if (horizontal) of(GridPoint(a.x - 1, a.z), a) else of(GridPoint(a.x, a.z - 1), a)

    fun midpoint(cellSize: Float) = Vec3((a.x + b.x) * 0.5f * cellSize, 0f, (a.z + b.z) * 0.5f * cellSize)

    companion object {
        fun of(p: GridPoint, q: GridPoint): Edge = if (p.x < q.x || p.z < q.z) Edge(p, q) else Edge(q, p)
    }
}

@Serializable
enum class OpeningType { DOOR, WINDOW }

@Serializable
data class Room(
    val id: String,
    val name: String,
    val cells: Set<GridPoint>,
    val floorColor: Rgb,
)

@Serializable
data class FurnitureItem(
    val id: String,
    val catalogId: String,
    /** Minimum (north-west) cell of the footprint after rotation. */
    val cell: GridPoint,
    /** 0..3, counter-clockwise seen from above, in 90° steps. */
    val quarterTurns: Int = 0,
    /** Applied to the catalog item's recolorable materials; null = model default. */
    val color: Rgb? = null,
)

/** One floor of a house. For now a house has a single level: the basement. */
@Serializable
data class Level(
    val plotWidth: Int = 24,
    val plotDepth: Int = 24,
    val cellSize: Float = 0.5f,
    val wallHeight: Float = 2.4f,
    val wallThickness: Float = 0.12f,
    val wallColor: Rgb = Rgb(0.92f, 0.90f, 0.86f),
    val walls: Set<Edge> = emptySet(),
    val openings: Map<Edge, OpeningType> = emptyMap(),
    val rooms: List<Room> = emptyList(),
    val furniture: List<FurnitureItem> = emptyList(),
) {
    fun roomAt(cell: GridPoint): Room? = rooms.firstOrNull { cell in it.cells }
    fun isRoomCell(cell: GridPoint) = rooms.any { cell in it.cells }
    fun inPlot(cell: GridPoint) = cell.x in 0 until plotWidth && cell.z in 0 until plotDepth
    val widthMeters get() = plotWidth * cellSize
    val depthMeters get() = plotDepth * cellSize
}

@Serializable
data class House(
    val id: String,
    val name: String,
    val level: Level = Level(),
    val formatVersion: Int = 1,
)

fun newId(prefix: String = ""): String = prefix + Random.nextLong(0, Long.MAX_VALUE).toString(36)
