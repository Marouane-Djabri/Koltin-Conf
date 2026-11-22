package dev.filamentiso

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IsoCameraTest {
    private val eps = 1e-4f
    private fun near(a: Float, b: Float, e: Float = eps) = assertTrue(abs(a - b) < e, "expected $b, was $a")
    private fun near(a: Double, b: Double, e: Double = 1e-4) = assertTrue(abs(a - b) < e, "expected $b, was $a")

    private val cube = Bounds(Vec3(0f, 0f, 0f), Vec3(1f, 1f, 1f))

    @Test
    fun defaultsAreTrueIsometric() {
        val d = IsoCamera.viewDirection(45f, IsoSceneConfig.ISO_PITCH_DEGREES)
        val k = 1f / sqrt(3f)
        near(d.x, k); near(d.y, k); near(d.z, k)
    }

    @Test
    fun isometricAxesProjectWithEqualLength() {
        // In a true isometric view, the three world axes have equal on-screen length.
        val s = IsoCamera.compute(IsoSceneConfig(), cube, 1f)
        val lengths = listOf(Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f), Vec3(0f, 0f, 1f)).map {
            val (x, y) = IsoCamera.projectToScreen(s, s.target + it)
            sqrt(x * x + y * y)
        }
        near(lengths[0], lengths[1]); near(lengths[1], lengths[2])
        near(lengths[0], sqrt(2f / 3f))
    }

    @Test
    fun verticalAxisStaysVerticalOnScreen() {
        for (yaw in listOf(0f, 30f, 45f, 200f)) {
            val s = IsoCamera.compute(IsoSceneConfig(yawDegrees = yaw), cube, 1.5f)
            val (x, y) = IsoCamera.projectToScreen(s, s.target + Vec3.UP)
            near(x, 0f); assertTrue(y > 0f)
        }
    }

    @Test
    fun eyeLooksAtCenterOfOffsetBox() {
        val box = Bounds(Vec3(3f, 2f, -1f), Vec3(2f, 1f, 2f))
        val s = IsoCamera.compute(IsoSceneConfig(), box, 1f)
        assertEquals(box.center, s.target)
        val dir = (s.eye - s.target).normalized()
        val expected = IsoCamera.viewDirection(45f, IsoSceneConfig.ISO_PITCH_DEGREES)
        near(dir.x, expected.x); near(dir.y, expected.y); near(dir.z, expected.z)
    }

    @Test
    fun allCornersFitInsideBoundsWithMargin() {
        val box = Bounds(Vec3(0f, 3f, 0f), Vec3(5.8f, 3.2f, 5.8f))
        for (aspect in listOf(0.45f, 1f, 2.2f)) for (yaw in listOf(0f, 17f, 45f, 90f, 133f)) {
            val cfg = IsoSceneConfig(yawDegrees = yaw)
            val s = IsoCamera.compute(cfg, box, aspect)
            for (i in 0 until 8) {
                val c = box.center + Vec3(
                    if (i and 1 != 0) box.halfExtent.x else -box.halfExtent.x,
                    if (i and 2 != 0) box.halfExtent.y else -box.halfExtent.y,
                    if (i and 4 != 0) box.halfExtent.z else -box.halfExtent.z,
                )
                val (x, y) = IsoCamera.projectToScreen(s, c)
                assertTrue(abs(x) <= s.right / (1 + cfg.fitMargin) + 1e-3, "x=$x right=${s.right} aspect=$aspect yaw=$yaw")
                assertTrue(abs(y) <= s.top / (1 + cfg.fitMargin) + 1e-3, "y=$y top=${s.top} aspect=$aspect yaw=$yaw")
                // and within near/far
                val depth = (c - s.eye) dot (s.target - s.eye).normalized()
                assertTrue(depth > s.near && depth < s.far, "depth $depth not in ${s.near}..${s.far}")
            }
        }
    }

    @Test
    fun boundsMatchViewportAspect() {
        for (aspect in listOf(0.5f, 1f, 1.8f)) {
            val s = IsoCamera.compute(IsoSceneConfig(), cube, aspect)
            near((s.right - s.left) / (s.top - s.bottom), aspect.toDouble())
            near(s.left, -s.right); near(s.bottom, -s.top)
        }
    }

    @Test
    fun fitIsTightOnTheLimitingAxis() {
        // Portrait: width is the limiting axis, so halfW = radiusXZ * (1 + margin).
        val cfg = IsoSceneConfig(fitMargin = 0.1f)
        val s = IsoCamera.compute(cfg, cube, 0.5f)
        near(s.right, sqrt(2.0) * 1.1, 1e-4)
    }

    @Test
    fun framingDoesNotChangeWithYaw() {
        val a = IsoCamera.compute(IsoSceneConfig(yawDegrees = 10f), cube, 0.6f)
        val b = IsoCamera.compute(IsoSceneConfig(yawDegrees = 250f), cube, 0.6f)
        near(a.right, b.right); near(a.top, b.top)
    }

    @Test
    fun zoomScalesBoundsInversely() {
        val one = IsoCamera.compute(IsoSceneConfig(zoom = 1f), cube, 1f)
        val two = IsoCamera.compute(IsoSceneConfig(zoom = 2f), cube, 1f)
        near(two.right, one.right / 2); near(two.top, one.top / 2)
    }

    @Test
    fun zoomAndYawAreClampedAndWrapped() {
        assertEquals(IsoSceneConfig.MAX_ZOOM, IsoSceneConfig().withZoom(100f).zoom)
        assertEquals(IsoSceneConfig.MIN_ZOOM, IsoSceneConfig().withZoom(0f).zoom)
        near(IsoSceneConfig().withYaw(-30f).yawDegrees, 330f)
        near(IsoSceneConfig().withYaw(725f).yawDegrees, 5f)
    }

    @Test
    fun rejectsBadAspect() {
        assertFailsWith<IllegalArgumentException> { IsoCamera.compute(IsoSceneConfig(), cube, 0f) }
    }
}
