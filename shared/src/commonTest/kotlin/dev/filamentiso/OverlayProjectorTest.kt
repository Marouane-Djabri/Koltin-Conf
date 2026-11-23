package dev.filamentiso

import dev.filamentiso.geometry.SampleBuildings
import dev.filamentiso.glb.GlbCheck
import dev.filamentiso.glb.GlbLimits
import dev.filamentiso.glb.GlbValidator
import dev.filamentiso.glb.Normalization
import dev.filamentiso.scene.ModelSource
import dev.filamentiso.storage.WorldJson
import dev.filamentiso.world.DemoContent
import dev.filamentiso.world.geo.LatLng
import dev.filamentiso.world.model.Building
import dev.filamentiso.world.model.ModelTransform
import dev.filamentiso.world.overlay.OverlayProjector
import dev.filamentiso.world.overlay.ResolvedModel
import dev.filamentiso.world.overlay.ScreenEntity
import dev.filamentiso.world.overlay.ScreenPoint
import dev.filamentiso.world.overlay.ScreenRect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OverlayProjectorTest {
    private fun near(a: Float, b: Float, eps: Float = 1e-3f) = assertTrue(abs(a - b) < eps, "expected $b, got $a")

    @Test
    fun groundScaleTopDownAndTilted() {
        val a = ScreenPoint(100f, 100f)
        // Top-down, 3 px per metre: 10 m east = +30 px x, 10 m north = -30 px y.
        near(OverlayProjector.groundScale(a, ScreenPoint(130f, 100f), ScreenPoint(100f, 70f)), 3f)
        // Tilted 60°: north is foreshortened by cos(60°) = 0.5, east is not.
        near(OverlayProjector.groundScale(a, ScreenPoint(130f, 100f), ScreenPoint(100f, 85f)), 3f)
        // Rotated map (bearing 90°): east points up, north points left.
        near(OverlayProjector.groundScale(a, ScreenPoint(100f, 70f), ScreenPoint(70f, 100f)), 3f)
    }

    @Test
    fun topDownViewPutsNorthUpAndModelUpTowardCamera() {
        val r = OverlayProjector.viewRotation(bearingDeg = 0.0, tiltDeg = 0.0)
        val north = r.rotate(Vec3(0f, 0f, -1f))
        near(north.y, 1f) // screen up (pixel space y up)
        val up = r.rotate(Vec3(0f, 1f, 0f))
        near(up.z, 1f) // toward the camera
        // Bearing 90°: the camera faces east, so east is up on screen.
        val east = OverlayProjector.viewRotation(90.0, 0.0).rotate(Vec3(1f, 0f, 0f))
        near(east.y, 1f)
    }

    @Test
    fun modelMatrixPutsPivotOnAnchor() {
        val norm = Normalization(pivot = Vec3(2f, 0.5f, 0f), fitScale = 1f)
        val m = OverlayProjector.modelMatrix(ScreenPoint(300f, 200f), 4f, 0.0, 0.0, ModelTransform(), norm, viewHeightPx = 800)
        val p = m.transform(Vec3(2f, 0.5f, 0f))
        near(p.x, 300f); near(-p.y, 200f)
        // 1 m east of the pivot lands 4 px to the right.
        near(m.transform(Vec3(3f, 0.5f, 0f)).x, 304f)
    }

    @Test
    fun lowerOnScreenIsCloser() {
        val norm = Normalization(Vec3(0f, 0f, 0f), 1f)
        fun depth(y: Float) = OverlayProjector.modelMatrix(ScreenPoint(0f, y), 1f, 0.0, 45.0, ModelTransform(), norm, 800).m[14]
        assertTrue(depth(600f) > depth(200f))
    }

    @Test
    fun offsetMovesByMetres() {
        val p = LatLng(36.0, 3.0)
        val q = OverlayProjector.offset(p, eastM = 111_320.0 * cos(36.0 * PI / 180), northM = 111_320.0)
        assertTrue(abs(q.lat - 37.0) < 1e-9 && abs(q.lng - 4.0) < 1e-9)
    }

    @Test
    fun hitTestPicksNearestContaining() {
        val model = ResolvedModel(ModelSource.Bytes("x", ByteArray(0)), Normalization(Vec3(0f, 0f, 0f), 1f), Vec3(0f, 0f, 0f), Vec3(1f, 1f, 1f))
        fun e(id: String, depth: Float, rect: ScreenRect) = ScreenEntity(id, model, Mat4.IDENTITY, rect.center, rect, depth)
        val far = e("far", -100f, ScreenRect(0f, 0f, 100f, 100f))
        val near = e("near", 50f, ScreenRect(50f, 50f, 150f, 150f))
        assertEquals("near", OverlayProjector.hitTest(listOf(far, near), ScreenPoint(60f, 60f))?.id)
        assertEquals("far", OverlayProjector.hitTest(listOf(far, near), ScreenPoint(10f, 10f))?.id)
        assertEquals(null, OverlayProjector.hitTest(listOf(far, near), ScreenPoint(500f, 10f)))
    }

    @Test
    fun rotationSnapsTo15Degrees() {
        assertEquals(15f, ModelTransform.snapRotation(9f))
        assertEquals(345f, ModelTransform.snapRotation(-14f))
        assertEquals(0f, ModelTransform().rotatedBy(-15f).rotatedBy(15f).rotationDeg)
        assertEquals(ModelTransform.MAX_SCALE, ModelTransform().withScale(99f).scale)
    }

    @Test
    fun buildingSurvivesJson() {
        for (b in DemoContent.buildings()) {
            val text = WorldJson.json.encodeToString(Building.serializer(), b)
            assertEquals(b, WorldJson.json.decodeFromString(Building.serializer(), text))
        }
    }

    @Test
    fun sampleBuildingsPassOurValidator() {
        for (k in SampleBuildings.Kind.entries) {
            val bytes = SampleBuildings.glb(k)
            val info = assertIs<GlbCheck.Valid>(GlbValidator.validate(bytes, GlbLimits.Building), k.id).info
            assertTrue(bytes.size < 1_000_000, "${k.id} is ${bytes.size} bytes")
            assertTrue(info.bboxMin.y >= -0.01f, "${k.id} goes below ground")
        }
    }
}
