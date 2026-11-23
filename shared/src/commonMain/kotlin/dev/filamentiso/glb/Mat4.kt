package dev.filamentiso.glb

import dev.filamentiso.Vec3
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull

/** Column-major 4x4 matrix (glTF convention), only what the validator needs. */
internal class Mat4(val m: FloatArray) {
    operator fun times(o: Mat4): Mat4 {
        val r = FloatArray(16)
        for (c in 0 until 4) for (row in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += m[k * 4 + row] * o.m[c * 4 + k]
            r[c * 4 + row] = s
        }
        return Mat4(r)
    }

    fun transform(p: Vec3) = Vec3(
        m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12],
        m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13],
        m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14],
    )

    companion object {
        val IDENTITY = Mat4(floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))

        /** A glTF node's local transform: `matrix`, or T * R * S. */
        fun ofNode(node: JsonObject): Mat4 {
            (node["matrix"] as? JsonArray)?.floats(16)?.let { return Mat4(it) }
            val t = (node["translation"] as? JsonArray)?.floats(3) ?: floatArrayOf(0f, 0f, 0f)
            val q = (node["rotation"] as? JsonArray)?.floats(4) ?: floatArrayOf(0f, 0f, 0f, 1f)
            val s = (node["scale"] as? JsonArray)?.floats(3) ?: floatArrayOf(1f, 1f, 1f)
            return trs(t, q, s)
        }

        fun trs(t: FloatArray, q: FloatArray, s: FloatArray): Mat4 {
            val (x, y, z, w) = q.toList()
            val r = floatArrayOf(
                1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w),
                2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w),
                2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y),
            )
            return Mat4(floatArrayOf(
                r[0] * s[0], r[1] * s[0], r[2] * s[0], 0f,
                r[3] * s[1], r[4] * s[1], r[5] * s[1], 0f,
                r[6] * s[2], r[7] * s[2], r[8] * s[2], 0f,
                t[0], t[1], t[2], 1f,
            ))
        }

        private fun JsonArray.floats(n: Int): FloatArray? {
            if (size != n) return null
            return FloatArray(n) { (this[it] as? JsonPrimitive)?.floatOrNull ?: return null }
        }
    }
}
