package dev.filamentiso.geometry

import dev.filamentiso.Rgb
import dev.filamentiso.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Material description written into the generated glTF (gltfio picks a matching ubershader). */
data class MaterialSpec(
    val name: String,
    val color: Rgb,
    val alpha: Float = 1f,
    val roughness: Float = 0.85f,
    val metallic: Float = 0f,
    val unlit: Boolean = false,
    val emissive: Rgb? = null,
    val emissiveStrength: Float = 1f,
)

/** Triangles sharing one material. Positions/normals are xyz triples. */
class MeshPart(
    val material: MaterialSpec,
    val positions: FloatArray,
    val normals: FloatArray,
    val indices: IntArray,
) {
    val vertexCount get() = positions.size / 3
    val triangleCount get() = indices.size / 3
}

/** Accumulates flat-shaded geometry per material. */
class MeshBuilder {
    private class Acc {
        val pos = ArrayList<Float>()
        val nrm = ArrayList<Float>()
        val idx = ArrayList<Int>()
    }

    private val parts = LinkedHashMap<MaterialSpec, Acc>()
    private fun acc(m: MaterialSpec) = parts.getOrPut(m) { Acc() }

    private fun vertex(a: Acc, p: Vec3, n: Vec3): Int {
        a.pos += p.x; a.pos += p.y; a.pos += p.z
        a.nrm += n.x; a.nrm += n.y; a.nrm += n.z
        return a.pos.size / 3 - 1
    }

    /** Triangle facing [n]; winding is fixed automatically so it is front-facing along [n]. */
    fun tri(m: MaterialSpec, p0: Vec3, p1: Vec3, p2: Vec3, n: Vec3) {
        val a = acc(m)
        val flip = ((p1 - p0) cross (p2 - p0)) dot n < 0f
        val i0 = vertex(a, p0, n); val i1 = vertex(a, p1, n); val i2 = vertex(a, p2, n)
        if (flip) { a.idx += i0; a.idx += i2; a.idx += i1 } else { a.idx += i0; a.idx += i1; a.idx += i2 }
    }

    /** Planar quad p0-p1-p2-p3 (in order around the edge) facing [n]. */
    fun quad(m: MaterialSpec, p0: Vec3, p1: Vec3, p2: Vec3, p3: Vec3, n: Vec3) {
        val a = acc(m)
        val flip = ((p1 - p0) cross (p2 - p0)) dot n < 0f
        val i0 = vertex(a, p0, n); val i1 = vertex(a, p1, n); val i2 = vertex(a, p2, n); val i3 = vertex(a, p3, n)
        if (flip) { a.idx += listOf(i0, i2, i1, i0, i3, i2) } else { a.idx += listOf(i0, i1, i2, i0, i2, i3) }
    }

    /** Axis-aligned box. [top] lets wall caps use a different color. */
    fun box(
        m: MaterialSpec,
        x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float,
        top: MaterialSpec = m,
        bottom: Boolean = false,
    ) {
        if (x1 <= x0 || y1 <= y0 || z1 <= z0) return
        quad(top, Vec3(x0, y1, z0), Vec3(x1, y1, z0), Vec3(x1, y1, z1), Vec3(x0, y1, z1), Vec3(0f, 1f, 0f))
        if (bottom) quad(m, Vec3(x0, y0, z0), Vec3(x1, y0, z0), Vec3(x1, y0, z1), Vec3(x0, y0, z1), Vec3(0f, -1f, 0f))
        quad(m, Vec3(x1, y0, z0), Vec3(x1, y1, z0), Vec3(x1, y1, z1), Vec3(x1, y0, z1), Vec3(1f, 0f, 0f))
        quad(m, Vec3(x0, y0, z0), Vec3(x0, y1, z0), Vec3(x0, y1, z1), Vec3(x0, y0, z1), Vec3(-1f, 0f, 0f))
        quad(m, Vec3(x0, y0, z1), Vec3(x1, y0, z1), Vec3(x1, y1, z1), Vec3(x0, y1, z1), Vec3(0f, 0f, 1f))
        quad(m, Vec3(x0, y0, z0), Vec3(x1, y0, z0), Vec3(x1, y1, z0), Vec3(x0, y1, z0), Vec3(0f, 0f, -1f))
    }

    /** Vertical n-sided prism (pots, lamps, heaters). Flat-shaded sides, top cap. */
    fun prism(m: MaterialSpec, cx: Float, cz: Float, y0: Float, y1: Float, radius: Float, sides: Int = 10, top: MaterialSpec = m) {
        val pts = (0 until sides).map { i ->
            val a = 2 * PI * i / sides
            Vec3(cx + radius * cos(a).toFloat(), 0f, cz + radius * sin(a).toFloat())
        }
        for (i in 0 until sides) {
            val p = pts[i]; val q = pts[(i + 1) % sides]
            val mid = Vec3((p.x + q.x) / 2 - cx, 0f, (p.z + q.z) / 2 - cz).normalized()
            quad(m, Vec3(p.x, y0, p.z), Vec3(q.x, y0, q.z), Vec3(q.x, y1, q.z), Vec3(p.x, y1, p.z), mid)
        }
        val c = Vec3(cx, y1, cz)
        for (i in 0 until sides) {
            val p = pts[i]; val q = pts[(i + 1) % sides]
            tri(top, c, Vec3(p.x, y1, p.z), Vec3(q.x, y1, q.z), Vec3(0f, 1f, 0f))
        }
    }

    fun isEmpty() = parts.values.all { it.idx.isEmpty() }

    fun build(): List<MeshPart> = parts.filterValues { it.idx.isNotEmpty() }.map { (m, a) ->
        MeshPart(m, a.pos.toFloatArray(), a.nrm.toFloatArray(), a.idx.toIntArray())
    }
}
