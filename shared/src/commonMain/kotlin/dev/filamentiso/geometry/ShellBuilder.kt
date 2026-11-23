package dev.filamentiso.geometry

import dev.filamentiso.Rgb
import dev.filamentiso.model.Edge
import dev.filamentiso.model.GridPoint
import dev.filamentiso.model.Level
import dev.filamentiso.model.OpeningType

/**
 * Turns a [Level] into floor + wall geometry.
 *
 * Walls are stored as unit edges; here consecutive collinear edges with the same state
 * (opening type, cut or not) are merged into segments, and each segment becomes boxes.
 */
object ShellBuilder {
    const val SLAB_DEPTH = 0.2f
    const val CUT_HEIGHT = 0.35f
    const val DOOR_HEIGHT = 2.05f
    const val SILL_HEIGHT = 0.9f
    const val WINDOW_TOP = 2.05f

    /**
     * @param viewDirX/viewDirZ horizontal direction from the scene toward the camera.
     *        Only their signs matter (which side of each wall faces the viewer).
     */
    data class Options(val cutaway: Boolean, val viewDirX: Float, val viewDirZ: Float)

    private val SLAB = MaterialSpec("slab", Rgb(0.42f, 0.40f, 0.38f), roughness = 0.95f)
    private val CAP = MaterialSpec("wall_cap", Rgb(0.26f, 0.26f, 0.29f), roughness = 0.9f)
    private val GLASS = MaterialSpec("glass", Rgb(0.70f, 0.85f, 0.95f), alpha = 0.35f, roughness = 0.1f)
    private val FRAME = MaterialSpec("frame", Rgb(0.95f, 0.95f, 0.95f), roughness = 0.6f)

    /**
     * A wall is cut (drawn low) when it stands between the viewer and a room: the cell on
     * its far side, seen from the camera, belongs to a room. Back walls stay full height.
     */
    fun isCut(edge: Edge, level: Level, opts: Options): Boolean {
        if (!opts.cutaway) return false
        val (first, second) = edge.cellsBeside() // (north, south) or (west, east)
        val toward = if (edge.horizontal) opts.viewDirZ else opts.viewDirX
        if (toward == 0f) return false
        val far = if (toward > 0f) first else second // camera on the +side → far side is the -side cell
        return level.isRoomCell(far)
    }

    fun build(level: Level, opts: Options): List<MeshPart> {
        val mb = MeshBuilder()
        buildFloors(level, mb)
        buildWalls(level, opts, mb)
        return mb.build()
    }

    private fun buildFloors(level: Level, mb: MeshBuilder) {
        val cs = level.cellSize
        val allCells = level.rooms.flatMap { it.cells }.toSet()
        val painted = dev.filamentiso.model.FloorMaterial.entries.associateWith { MaterialSpec("floor_${it.name.lowercase()}", it.color, roughness = 0.9f) }
        for (room in level.rooms) {
            val roomMat = MaterialSpec("floor_${room.id}", room.floorColor, roughness = 0.9f)
            for (c in room.cells) {
                val mat = level.tiles[c]?.let { painted.getValue(it) } ?: roomMat
                val x0 = c.x * cs; val z0 = c.z * cs; val x1 = x0 + cs; val z1 = z0 + cs
                mb.quad(mat, dev.filamentiso.Vec3(x0, 0f, z0), dev.filamentiso.Vec3(x1, 0f, z0),
                    dev.filamentiso.Vec3(x1, 0f, z1), dev.filamentiso.Vec3(x0, 0f, z1), dev.filamentiso.Vec3(0f, 1f, 0f))
                // Slab sides where the floor ends: gives the classic "diorama" look.
                fun side(nx: Int, nz: Int) = GridPoint(c.x + nx, c.z + nz) !in allCells
                if (side(0, -1)) mb.box(SLAB, x0, -SLAB_DEPTH, z0, x1, 0f, z0 + 0.0001f, top = SLAB)
                if (side(0, 1)) mb.box(SLAB, x0, -SLAB_DEPTH, z1 - 0.0001f, x1, 0f, z1, top = SLAB)
                if (side(-1, 0)) mb.box(SLAB, x0, -SLAB_DEPTH, z0, x0 + 0.0001f, 0f, z1, top = SLAB)
                if (side(1, 0)) mb.box(SLAB, x1 - 0.0001f, -SLAB_DEPTH, z0, x1, 0f, z1, top = SLAB)
            }
        }
    }

    private data class Unit(val pos: Int, val opening: OpeningType?, val cut: Boolean)

    private fun buildWalls(level: Level, opts: Options, mb: MeshBuilder) {
        val wallMat = MaterialSpec("wall", level.wallColor, roughness = 0.85f)
        // Group edges by grid line.
        val horizontal = level.walls.filter { it.horizontal }.groupBy { it.a.z }
        val vertical = level.walls.filter { !it.horizontal }.groupBy { it.a.x }
        for ((line, edges) in horizontal) emitLine(level, opts, mb, wallMat, true, line, edges.map { it.a.x }.sorted())
        for ((line, edges) in vertical) emitLine(level, opts, mb, wallMat, false, line, edges.map { it.a.z }.sorted())
    }

    private fun edgeAt(horizontal: Boolean, line: Int, pos: Int) =
        if (horizontal) Edge.of(GridPoint(pos, line), GridPoint(pos + 1, line))
        else Edge.of(GridPoint(line, pos), GridPoint(line, pos + 1))

    private fun emitLine(
        level: Level, opts: Options, mb: MeshBuilder, wallMat: MaterialSpec,
        horizontal: Boolean, line: Int, positions: List<Int>,
    ) {
        // Split into runs of consecutive positions.
        val runs = mutableListOf<MutableList<Int>>()
        for (p in positions) {
            if (runs.isEmpty() || runs.last().last() != p - 1) runs += mutableListOf(p) else runs.last() += p
        }
        for (run in runs) {
            val units = run.map { p ->
                val e = edgeAt(horizontal, line, p)
                Unit(p, level.openings[e], isCut(e, level, opts))
            }
            // Merge units with the same state into segments.
            var i = 0
            while (i < units.size) {
                var j = i
                while (j + 1 < units.size && units[j + 1].opening == units[i].opening && units[j + 1].cut == units[i].cut) j++
                emitSegment(
                    level, mb, wallMat, horizontal, line,
                    start = units[i].pos, end = units[j].pos + 1,
                    extendStart = i == 0, extendEnd = j == units.size - 1,
                    opening = units[i].opening, cut = units[i].cut,
                )
                i = j + 1
            }
        }
    }

    private fun emitSegment(
        level: Level, mb: MeshBuilder, wallMat: MaterialSpec, horizontal: Boolean, line: Int,
        start: Int, end: Int, extendStart: Boolean, extendEnd: Boolean, opening: OpeningType?, cut: Boolean,
    ) {
        val cs = level.cellSize
        val t = level.wallThickness / 2
        val s = start * cs - if (extendStart) t else 0f
        val e = end * cs + if (extendEnd) t else 0f
        val l = line * cs
        val h = level.wallHeight

        fun wallBox(y0: Float, y1: Float, mat: MaterialSpec = wallMat, inset: Float = 0f, thin: Float = t) {
            if (horizontal) mb.box(mat, s + inset, y0, l - thin, e - inset, y1, l + thin, top = if (mat == wallMat) CAP else mat)
            else mb.box(mat, l - thin, y0, s + inset, l + thin, y1, e - inset, top = if (mat == wallMat) CAP else mat)
        }

        when (opening) {
            null -> wallBox(0f, if (cut) CUT_HEIGHT else h)
            OpeningType.DOOR -> if (!cut) {
                wallBox(DOOR_HEIGHT, h)
                // Thin white frame strip under the lintel for readability.
                wallBox(DOOR_HEIGHT - 0.04f, DOOR_HEIGHT, FRAME, thin = t + 0.01f)
            }
            OpeningType.WINDOW -> if (cut) {
                wallBox(0f, CUT_HEIGHT)
            } else {
                wallBox(0f, SILL_HEIGHT)
                wallBox(WINDOW_TOP, h)
                wallBox(SILL_HEIGHT, SILL_HEIGHT + 0.03f, FRAME, thin = t + 0.02f)
                wallBox(SILL_HEIGHT + 0.03f, WINDOW_TOP, GLASS, thin = 0.01f)
            }
        }
    }
}
