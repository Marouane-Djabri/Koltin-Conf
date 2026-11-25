package dev.filamentiso.world.scene

import dev.filamentiso.Rgb
import dev.filamentiso.Vec3
import dev.filamentiso.geometry.MaterialSpec
import dev.filamentiso.geometry.MeshBuilder
import dev.filamentiso.geometry.MeshPart
import dev.filamentiso.model.GridPoint

/** Turns a scene's tiles into geometry: pastel quads with a subtle variation, sunken water, earth sides. */
object GroundBuilder {
    const val EARTH_DEPTH = 0.6f
    const val WATER_LEVEL = -0.14f
    private val EARTH = MaterialSpec("earth", Rgb(0.74f, 0.62f, 0.52f), roughness = 0.95f)
    private val BANK = MaterialSpec("bank", Rgb(0.86f, 0.77f, 0.62f), roughness = 0.95f)
    private val UP = Vec3(0f, 1f, 0f)
    private val SHADES = Ground.entries.associateWith { g ->
        listOf(1f, 0.97f).mapIndexed { i, k -> MaterialSpec("ground_${g.name.lowercase()}_$i", g.color.scaled(k), roughness = 0.95f) }
    }

    fun build(s: Scene): List<MeshPart> {
        val mb = MeshBuilder()
        val t = s.tileSizeM
        for (z in 0 until s.depth) for (x in 0 until s.width) {
            val g = s.groundAt(GridPoint(x, z))
            val y = height(g)
            val x0 = x * t; val z0 = z * t; val x1 = x0 + t; val z1 = z0 + t
            mb.quad(SHADES.getValue(g)[shade(x, z)], Vec3(x0, y, z0), Vec3(x1, y, z0), Vec3(x1, y, z1), Vec3(x0, y, z1), UP)
            if (g != Ground.WATER) continue
            // Banks: the sides of the pool, facing the water.
            fun dry(dx: Int, dz: Int) = GridPoint(x + dx, z + dz).let { s.contains(it) && s.groundAt(it) != Ground.WATER }
            fun bank(ax: Float, az: Float, bx: Float, bz: Float, n: Vec3) =
                mb.quad(BANK, Vec3(ax, y, az), Vec3(bx, y, bz), Vec3(bx, 0f, bz), Vec3(ax, 0f, az), n)
            if (dry(0, -1)) bank(x0, z0, x1, z0, Vec3(0f, 0f, 1f))
            if (dry(0, 1)) bank(x0, z1, x1, z1, Vec3(0f, 0f, -1f))
            if (dry(-1, 0)) bank(x0, z0, x0, z1, Vec3(1f, 0f, 0f))
            if (dry(1, 0)) bank(x1, z0, x1, z1, Vec3(-1f, 0f, 0f))
        }
        sides(s, mb)
        return mb.build()
    }

    /** Diorama sides, up to the surface of each border tile. */
    private fun sides(s: Scene, mb: MeshBuilder) {
        val t = s.tileSizeM
        val w = s.widthM
        val d = s.depthM
        fun top(x: Int, z: Int) = height(s.groundAt(GridPoint(x, z)))
        fun side(ax: Float, az: Float, bx: Float, bz: Float, y: Float, n: Vec3) =
            mb.quad(EARTH, Vec3(ax, -EARTH_DEPTH, az), Vec3(bx, -EARTH_DEPTH, bz), Vec3(bx, y, bz), Vec3(ax, y, az), n)
        for (x in 0 until s.width) {
            side(x * t, 0f, (x + 1) * t, 0f, top(x, 0), Vec3(0f, 0f, -1f))
            side(x * t, d, (x + 1) * t, d, top(x, s.depth - 1), Vec3(0f, 0f, 1f))
        }
        for (z in 0 until s.depth) {
            side(0f, z * t, 0f, (z + 1) * t, top(0, z), Vec3(-1f, 0f, 0f))
            side(w, z * t, w, (z + 1) * t, top(s.width - 1, z), Vec3(1f, 0f, 0f))
        }
    }

    private fun height(g: Ground) = if (g == Ground.WATER) WATER_LEVEL else 0f

    /** Stable pseudo-random 0 or 1 per tile. */
    private fun shade(x: Int, z: Int): Int = (((x * 7349) xor (z * 19349)) shr 3) and 1
}
