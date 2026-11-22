package dev.filamentiso

import dev.filamentiso.editor.addRectRoom
import dev.filamentiso.editor.toggleOpening
import dev.filamentiso.geometry.GlbWriter
import dev.filamentiso.geometry.OverlayBuilder
import dev.filamentiso.geometry.OverlayState
import dev.filamentiso.geometry.ShellBuilder
import dev.filamentiso.model.BuiltinCatalog
import dev.filamentiso.model.Edge
import dev.filamentiso.model.GridPoint
import dev.filamentiso.model.Level
import dev.filamentiso.model.OpeningType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GeometryTest {
    private fun g(x: Int, z: Int) = GridPoint(x, z)
    private val room = Level().addRectRoom(g(0, 0), g(3, 3))

    @Test
    fun cutawayLowersWallsFacingTheCamera() {
        // Camera toward +X and +Z (yaw 45°): south and east walls face the viewer.
        val opts = ShellBuilder.Options(cutaway = true, viewDirX = 0.7f, viewDirZ = 0.7f)
        val north = Edge.of(g(0, 0), g(1, 0)); val south = Edge.of(g(0, 4), g(1, 4))
        val west = Edge.of(g(0, 0), g(0, 1)); val east = Edge.of(g(4, 0), g(4, 1))
        assertFalse(ShellBuilder.isCut(north, room, opts))
        assertFalse(ShellBuilder.isCut(west, room, opts))
        assertTrue(ShellBuilder.isCut(south, room, opts))
        assertTrue(ShellBuilder.isCut(east, room, opts))
        // Turned around, the opposite walls are cut.
        val back = opts.copy(viewDirX = -0.7f, viewDirZ = -0.7f)
        assertTrue(ShellBuilder.isCut(north, room, back)); assertFalse(ShellBuilder.isCut(south, room, back))
        assertFalse(ShellBuilder.isCut(south, room, opts.copy(cutaway = false)))
    }

    @Test
    fun shellHasFloorWallsAndCaps() {
        val parts = ShellBuilder.build(room, ShellBuilder.Options(false, 1f, 1f))
        val names = parts.map { it.material.name }.toSet()
        assertTrue(names.any { it.startsWith("floor_") })
        assertTrue("wall" in names && "wall_cap" in names && "slab" in names)
        // Edges merge into 4 straight runs → 4 boxes: 4 side faces each in "wall", 1 top face each in "wall_cap".
        assertEquals(4 * 4 * 4, parts.first { it.material.name == "wall" }.vertexCount)
        assertEquals(4 * 1 * 4, parts.first { it.material.name == "wall_cap" }.vertexCount)
    }

    @Test
    fun windowAddsGlass() {
        val l = room.toggleOpening(Edge.of(g(1, 0), g(2, 0)), OpeningType.WINDOW)
        val parts = ShellBuilder.build(l, ShellBuilder.Options(false, 1f, 1f))
        assertTrue(parts.any { it.material.name == "glass" && it.material.alpha < 1f })
    }

    @Test
    fun glbIsValidAndReadable() {
        val parts = ShellBuilder.build(room, ShellBuilder.Options(true, 1f, 1f)) + OverlayBuilder.build(room, OverlayState())
        val glb = GlbWriter.write(parts)
        fun u32(o: Int) = (glb[o].toInt() and 0xFF) or ((glb[o + 1].toInt() and 0xFF) shl 8) or
            ((glb[o + 2].toInt() and 0xFF) shl 16) or ((glb[o + 3].toInt() and 0xFF) shl 24)
        assertEquals(0x46546C67, u32(0)); assertEquals(2, u32(4)); assertEquals(glb.size, u32(8))
        val jsonLen = u32(12)
        assertEquals(0x4E4F534A, u32(16))
        assertEquals(0, jsonLen % 4)
        val json = Json.parseToJsonElement(glb.copyOfRange(20, 20 + jsonLen).decodeToString()).jsonObject
        val prims = json["meshes"]!!.jsonArray[0].jsonObject["primitives"]!!.jsonArray
        assertEquals(parts.size, prims.size)
        assertEquals(parts.map { it.material }.distinct().size, json["materials"]!!.jsonArray.size)
        assertTrue(json["extensionsUsed"]!!.jsonArray.any { it.jsonPrimitive.content == "KHR_materials_unlit" })
        val binLen = u32(20 + jsonLen)
        assertEquals(0x004E4942, u32(24 + jsonLen))
        val declared = json["buffers"]!!.jsonArray[0].jsonObject["byteLength"]!!.jsonPrimitive.content.toInt()
        assertTrue(binLen >= declared && binLen - declared < 4, "BIN chunk $binLen vs buffer $declared")
        assertEquals(glb.size, 28 + jsonLen + binLen)
    }

    @Test
    fun everyBuiltinFurnitureBuildsAndFitsItsFootprint() {
        for (item in BuiltinCatalog.items) {
            val parts = assertNotNull(BuiltinCatalog.mesh(item.id), item.id)
            val halfW = item.footprintW * 0.25f + 0.051f; val halfD = item.footprintD * 0.25f + 0.051f
            for (p in parts) for (i in p.positions.indices step 3) {
                assertTrue(abs(p.positions[i]) <= halfW, "${item.id} x=${p.positions[i]} > $halfW")
                assertTrue(abs(p.positions[i + 2]) <= halfD, "${item.id} z=${p.positions[i + 2]} > $halfD")
                assertTrue(p.positions[i + 1] >= -0.001f, "${item.id} below floor")
            }
            GlbWriter.write(parts, item.id) // must not throw
        }
    }

    @Test
    fun pickingRoundTrips() {
        val bounds = Bounds(Vec3(6f, 1.1f, 6f), Vec3(6f, 1.3f, 6f))
        for (yaw in listOf(45f, 135f, 300f)) for (pitch in listOf(IsoSceneConfig.ISO_PITCH_DEGREES, 89.9f)) {
            val s = IsoCamera.compute(IsoSceneConfig(yawDegrees = yaw, pitchDegrees = pitch, zoom = 1.7f, pan = Vec3(1f, 0f, -2f)), bounds, 0.46f)
            val world = Vec3(3.3f, 0f, 7.9f)
            val (px, py) = IsoCamera.worldToScreen(s, world, 1200, 2600)
            val back = assertNotNull(IsoCamera.screenToPlane(s, px, py, 1200, 2600, 0f))
            assertTrue(abs(back.x - world.x) < 1e-2f && abs(back.z - world.z) < 1e-2f, "yaw=$yaw pitch=$pitch got $back")
        }
    }

    @Test
    fun panDeltaFollowsTheFinger() {
        val bounds = Bounds(Vec3(0f, 0f, 0f), Vec3(5f, 1f, 5f))
        val cfg = IsoSceneConfig()
        val s = IsoCamera.compute(cfg, bounds, 0.5f)
        val p0 = Vec3(1f, 0f, 1f)
        val before = IsoCamera.worldToScreen(s, p0, 1000, 2000)
        val d = IsoCamera.panDelta(s, 40f, -25f, 1000, 2000)
        val s2 = IsoCamera.compute(cfg.copy(pan = d), bounds, 0.5f)
        val after = IsoCamera.worldToScreen(s2, p0, 1000, 2000)
        assertTrue(abs(after.first - before.first - 40f) < 0.5f, "dx ${after.first - before.first}")
        assertTrue(abs(after.second - before.second + 25f) < 0.5f, "dy ${after.second - before.second}")
    }
}
