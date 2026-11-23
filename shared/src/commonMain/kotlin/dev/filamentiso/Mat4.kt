package dev.filamentiso

import kotlin.math.cos
import kotlin.math.sin

/**
 * Column-major 4x4 matrix (glTF / Filament / three.js convention). Immutable.
 * Shared code builds every model matrix with it, renderers only copy the 16 numbers.
 */
class Mat4(val m: FloatArray) {
    init { require(m.size == 16) }

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

    /** Direction only (no translation). */
    fun rotate(v: Vec3) = Vec3(
        m[0] * v.x + m[4] * v.y + m[8] * v.z,
        m[1] * v.x + m[5] * v.y + m[9] * v.z,
        m[2] * v.x + m[6] * v.y + m[10] * v.z,
    )

    override fun equals(other: Any?) = other is Mat4 && m.contentEquals(other.m)
    override fun hashCode() = m.contentHashCode()
    override fun toString() = m.joinToString(prefix = "Mat4[", postfix = "]")

    companion object {
        val IDENTITY = Mat4(floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))

        fun translation(x: Float, y: Float, z: Float) =
            Mat4(floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, x, y, z, 1f))

        fun translation(v: Vec3) = translation(v.x, v.y, v.z)

        fun scale(s: Float) = Mat4(floatArrayOf(s, 0f, 0f, 0f, 0f, s, 0f, 0f, 0f, 0f, s, 0f, 0f, 0f, 0f, 1f))

        /** Right-handed rotation about +X. */
        fun rotationX(degrees: Float): Mat4 {
            val r = degrees.toRadians(); val c = cos(r); val s = sin(r)
            return Mat4(floatArrayOf(1f, 0f, 0f, 0f, 0f, c, s, 0f, 0f, -s, c, 0f, 0f, 0f, 0f, 1f))
        }

        /** Right-handed rotation about +Y: +X turns toward -Z. */
        fun rotationY(degrees: Float): Mat4 {
            val r = degrees.toRadians(); val c = cos(r); val s = sin(r)
            return Mat4(floatArrayOf(c, 0f, -s, 0f, 0f, 1f, 0f, 0f, s, 0f, c, 0f, 0f, 0f, 0f, 1f))
        }

        /** glTF node transform: T * R(quaternion xyzw) * S. */
        fun trs(t: FloatArray, q: FloatArray, s: FloatArray): Mat4 {
            val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
            return Mat4(floatArrayOf(
                (1 - 2 * (y * y + z * z)) * s[0], (2 * (x * y + z * w)) * s[0], (2 * (x * z - y * w)) * s[0], 0f,
                (2 * (x * y - z * w)) * s[1], (1 - 2 * (x * x + z * z)) * s[1], (2 * (y * z + x * w)) * s[1], 0f,
                (2 * (x * z + y * w)) * s[2], (2 * (y * z - x * w)) * s[2], (1 - 2 * (x * x + y * y)) * s[2], 0f,
                t[0], t[1], t[2], 1f,
            ))
        }

        private fun Float.toRadians() = (this * kotlin.math.PI / 180.0).toFloat()
    }
}
