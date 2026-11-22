package dev.filamentiso.geometry

import dev.filamentiso.Rgb
import dev.filamentiso.Vec3
import dev.filamentiso.model.Edge
import dev.filamentiso.model.GridPoint
import dev.filamentiso.model.Level

/** Editor-only visuals drawn just above the floor: grid, previews, selection. Unlit, no shadows. */
data class OverlayState(
    val showGrid: Boolean = true,
    val previewCells: Set<GridPoint> = emptySet(),
    val previewColor: Rgb = Rgb(0.35f, 0.65f, 1f),
    val previewEdges: Set<Edge> = emptySet(),
    val selectedCells: Set<GridPoint> = emptySet(),
)

object OverlayBuilder {
    private val GRID = MaterialSpec("grid", Rgb(0.45f, 0.48f, 0.55f), unlit = true, alpha = 0.55f)
    private val PLOT = MaterialSpec("plot", Rgb(0.65f, 0.70f, 0.80f), unlit = true)
    private val EDGE = MaterialSpec("edge_preview", Rgb(1f, 0.62f, 0.15f), unlit = true)
    private val SELECT = MaterialSpec("selection", Rgb(1f, 0.85f, 0.2f), unlit = true, alpha = 0.55f)

    fun build(level: Level, state: OverlayState): List<MeshPart> {
        val mb = MeshBuilder()
        val cs = level.cellSize
        val w = level.widthMeters; val d = level.depthMeters
        val up = Vec3(0f, 1f, 0f)

        fun rect(m: MaterialSpec, x0: Float, z0: Float, x1: Float, z1: Float, y: Float) =
            mb.quad(m, Vec3(x0, y, z0), Vec3(x1, y, z0), Vec3(x1, y, z1), Vec3(x0, y, z1), up)

        if (state.showGrid) {
            val lw = 0.012f
            for (i in 0..level.plotWidth) { val x = i * cs; rect(GRID, x - lw, 0f, x + lw, d, 0.004f) }
            for (i in 0..level.plotDepth) { val z = i * cs; rect(GRID, 0f, z - lw, w, z + lw, 0.004f) }
            val ow = 0.04f // plot outline
            rect(PLOT, -ow, -ow, w + ow, 0f, 0.005f); rect(PLOT, -ow, d, w + ow, d + ow, 0.005f)
            rect(PLOT, -ow, 0f, 0f, d, 0.005f); rect(PLOT, w, 0f, w + ow, d, 0.005f)
        }

        if (state.previewCells.isNotEmpty()) {
            val m = MaterialSpec("cell_preview", state.previewColor, unlit = true, alpha = 0.5f)
            for (c in state.previewCells) rect(m, c.x * cs + 0.02f, c.z * cs + 0.02f, (c.x + 1) * cs - 0.02f, (c.z + 1) * cs - 0.02f, 0.01f)
        }
        for (c in state.selectedCells) rect(SELECT, c.x * cs, c.z * cs, (c.x + 1) * cs, (c.z + 1) * cs, 0.012f)

        for (e in state.previewEdges) {
            val hw = 0.07f
            if (e.horizontal) rect(EDGE, e.a.x * cs, e.a.z * cs - hw, e.b.x * cs, e.a.z * cs + hw, 0.015f)
            else rect(EDGE, e.a.x * cs - hw, e.a.z * cs, e.a.x * cs + hw, e.b.z * cs, 0.015f)
        }
        return mb.build()
    }
}
