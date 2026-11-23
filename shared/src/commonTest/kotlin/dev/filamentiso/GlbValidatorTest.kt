package dev.filamentiso

import dev.filamentiso.geometry.GlbWriter
import dev.filamentiso.geometry.MaterialSpec
import dev.filamentiso.geometry.MeshBuilder
import dev.filamentiso.glb.GlbCheck
import dev.filamentiso.glb.GlbLimits
import dev.filamentiso.glb.GlbProblem
import dev.filamentiso.glb.GlbValidator
import dev.filamentiso.glb.Normalization
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GlbValidatorTest {
    private val limits = GlbLimits.Building

    /** A 2 x 1 x 4 m box from (1, 0.5, -2) to (3, 1.5, 2): 12 triangles. */
    private fun boxGlb(): ByteArray {
        val b = MeshBuilder()
        b.box(MaterialSpec("m", Rgb(1f, 0.5f, 0.5f)), 1f, 0.5f, -2f, 3f, 1.5f, 2f, bottom = true)
        return GlbWriter.write(b.build())
    }

    /** Hand-built GLB: our own JSON + an optional BIN chunk. */
    private fun glb(json: String, bin: ByteArray = ByteArray(0)): ByteArray {
        fun pad(b: ByteArray, fill: Byte) = if (b.size % 4 == 0) b else b + ByteArray(4 - b.size % 4) { fill }
        val j = pad(json.encodeToByteArray(), 0x20)
        val bn = pad(bin, 0)
        val total = 12 + 8 + j.size + (if (bn.isEmpty()) 0 else 8 + bn.size)
        val out = ArrayList<Byte>()
        fun int(v: Int) { for (k in 0 until 4) out += (v shr (8 * k)).toByte() }
        int(0x46546C67); int(2); int(total)
        int(j.size); int(0x4E4F534A); out += j.toList()
        if (bn.isNotEmpty()) { int(bn.size); int(0x004E4942); out += bn.toList() }
        return out.toByteArray()
    }

    /** One triangle primitive (3 vertices, no indices), referenced by [nodesJson]. */
    private fun triangleJson(nodesJson: String, extra: String = "", materials: Int = 1, mode: Int = 4, count: Int = 3) = """
        {"asset":{"version":"2.0"},$extra
         "scene":0,"scenes":[{"nodes":[0]}],"nodes":$nodesJson,
         "meshes":[{"primitives":[{"attributes":{"POSITION":0},"mode":$mode}]}],
         "materials":[${(0 until materials).joinToString(",") { "{}" }}],
         "accessors":[{"bufferView":0,"componentType":5126,"count":$count,"type":"VEC3","min":[0,0,0],"max":[1,1,0]}],
         "bufferViews":[{"buffer":0,"byteLength":${count * 12}}],
         "buffers":[{"byteLength":${count * 12}}]}
    """.trimIndent()

    private fun valid(bytes: ByteArray, l: GlbLimits = limits) =
        assertIs<GlbCheck.Valid>(GlbValidator.validate(bytes, l), "expected valid").info

    private inline fun <reified P : GlbProblem> invalid(bytes: ByteArray, l: GlbLimits = limits): P {
        val r = assertIs<GlbCheck.Invalid>(GlbValidator.validate(bytes, l), "expected invalid")
        return assertIs<P>(r.problem, "got: ${r.message}")
    }

    @Test
    fun acceptsOurOwnGlbAndReadsBounds() {
        val info = valid(boxGlb())
        assertEquals(12, info.triangleCount)
        assertEquals(1, info.materialCount)
        assertEquals(Vec3(1f, 0.5f, -2f), info.bboxMin)
        assertEquals(Vec3(3f, 1.5f, 2f), info.bboxMax)
    }

    @Test
    fun rejectsWrongMagicAndGltfText() {
        invalid<GlbProblem.NotGlb>(ByteArray(40) { 7 })
        invalid<GlbProblem.NotGlb>(ByteArray(3))
        invalid<GlbProblem.GltfText>("""{"asset":{"version":"2.0"}}""".encodeToByteArray())
        val r = GlbValidator.validate("house.gltf", boxGlb(), limits)
        assertIs<GlbProblem.GltfText>((r as GlbCheck.Invalid).problem)
    }

    @Test
    fun rejectsVersion1() {
        val b = boxGlb().copyOf()
        b[4] = 1
        invalid<GlbProblem.WrongVersion>(b)
    }

    @Test
    fun rejectsBadLengths() {
        val good = boxGlb()
        // Truncated file: header length no longer matches.
        invalid<GlbProblem.Corrupt>(good.copyOf(good.size - 8))
        // Header length patched to match, but the BIN chunk now runs past the end.
        val cut = good.copyOf(good.size - 8)
        val n = cut.size
        cut[8] = n.toByte(); cut[9] = (n shr 8).toByte(); cut[10] = (n shr 16).toByte(); cut[11] = (n shr 24).toByte()
        invalid<GlbProblem.Corrupt>(cut)
    }

    @Test
    fun rejectsBrokenJson() {
        invalid<GlbProblem.Corrupt>(glb("{not json"))
    }

    @Test
    fun rejectsTooBig() {
        val small = GlbLimits(maxBytes = 100, maxTriangles = 10, maxTextureSize = 2048, maxMaterials = 8)
        val p = invalid<GlbProblem.TooBig>(boxGlb(), small)
        assertTrue(p.message.contains("MB"))
    }

    @Test
    fun countsTrianglesAgainstLimit() {
        val tight = limits.copy(maxTriangles = 11)
        val p = invalid<GlbProblem.TooManyTriangles>(boxGlb(), tight)
        assertEquals(12, p.count)
    }

    @Test
    fun countsInstancedMeshesPerNodeAndStrips() {
        // Two nodes share the mesh: 2 triangles.
        assertEquals(2, valid(glb(triangleJson("""[{"children":[1,2]},{"mesh":0},{"mesh":0,"translation":[5,0,0]}]"""), ByteArray(36))).triangleCount)
        // Triangle strip with 5 vertices: 3 triangles.
        assertEquals(3, valid(glb(triangleJson("""[{"mesh":0}]""", mode = 5, count = 5), ByteArray(60))).triangleCount)
    }

    @Test
    fun appliesNodeTransformsToBounds() {
        // Scale 2 and move +10 on X.
        val info = valid(glb(triangleJson("""[{"mesh":0,"translation":[10,0,0],"scale":[2,2,2]}]"""), ByteArray(36)))
        assertEquals(Vec3(10f, 0f, 0f), info.bboxMin)
        assertEquals(Vec3(12f, 2f, 0f), info.bboxMax)
        // 90° around Y (quaternion [0, sin45, 0, cos45]): +X goes to -Z.
        val s = 0.70710677f
        val rot = valid(glb(triangleJson("""[{"mesh":0,"rotation":[0,$s,0,$s]}]"""), ByteArray(36)))
        assertTrue(abs(rot.bboxMin.z + 1f) < 1e-5f && abs(rot.bboxMax.x) < 1e-5f, "got ${rot.bboxMin}..${rot.bboxMax}")
    }

    @Test
    fun rejectsCompression() {
        val draco = triangleJson("""[{"mesh":0}]""", extra = """"extensionsUsed":["KHR_draco_mesh_compression"],"extensionsRequired":["KHR_draco_mesh_compression"],""")
        invalid<GlbProblem.Compressed>(glb(draco, ByteArray(36)))
        val meshopt = triangleJson("""[{"mesh":0}]""", extra = """"extensionsUsed":["EXT_meshopt_compression"],""")
        invalid<GlbProblem.Compressed>(glb(meshopt, ByteArray(36)))
    }

    @Test
    fun rejectsUnknownRequiredExtensionButAllowsUnlit() {
        val basis = triangleJson("""[{"mesh":0}]""", extra = """"extensionsRequired":["KHR_texture_basisu"],""")
        assertEquals("KHR_texture_basisu", invalid<GlbProblem.UnsupportedExtension>(glb(basis, ByteArray(36))).name)
        val unlit = triangleJson("""[{"mesh":0}]""", extra = """"extensionsRequired":["KHR_materials_unlit"],""")
        valid(glb(unlit, ByteArray(36)))
    }

    @Test
    fun rejectsExternalBuffers() {
        val ext = triangleJson("""[{"mesh":0}]""").replace(""""buffers":[{"byteLength":36}]""", """"buffers":[{"byteLength":36,"uri":"model.bin"}]""")
        invalid<GlbProblem.ExternalFiles>(glb(ext))
    }

    @Test
    fun rejectsTooManyMaterials() {
        invalid<GlbProblem.TooManyMaterials>(glb(triangleJson("""[{"mesh":0}]""", materials = 9), ByteArray(36)))
    }

    @Test
    fun rejectsShortBinChunk() {
        invalid<GlbProblem.Corrupt>(glb(triangleJson("""[{"mesh":0}]"""), ByteArray(12)))
    }

    @Test
    fun rejectsNodeCycles() {
        invalid<GlbProblem.Corrupt>(glb(triangleJson("""[{"children":[1]},{"mesh":0,"children":[0]}]"""), ByteArray(36)))
    }

    @Test
    fun readsPngTextureSize() {
        // Minimal PNG header: signature + IHDR with width 4096, height 16.
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10,
            0, 0, 0, 13, 'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(),
            0, 0, 0x10, 0, 0, 0, 0, 16)
        val bin = ByteArray(36) + png
        val json = triangleJson("""[{"mesh":0}]""")
            .replace(""""bufferViews":[{"buffer":0,"byteLength":36}]""", """"bufferViews":[{"buffer":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":24}]""")
            .replace(""""buffers":[{"byteLength":36}]""", """"buffers":[{"byteLength":60}],"images":[{"bufferView":1,"mimeType":"image/png"}],"textures":[{"source":0}]""")
        val p = invalid<GlbProblem.TextureTooLarge>(glb(json, bin))
        assertEquals(4096, p.side)
        assertEquals(4096, valid(glb(json, bin), limits.copy(maxTextureSize = 4096)).maxTextureSide)
    }

    @Test
    fun normalizationCentresGroundsAndFits() {
        val n = Normalization.of(Vec3(1f, 0.5f, -2f), Vec3(3f, 1.5f, 2f), targetFootprint = 20f)
        assertEquals(Vec3(2f, 0.5f, 0f), n.pivot)
        assertEquals(5f, n.fitScale) // largest horizontal side is 4 m (Z)
        assertEquals(Vec3(10f, 5f, 20f), n.fittedSize(Vec3(1f, 0.5f, -2f), Vec3(3f, 1.5f, 2f)))
        // Degenerate footprint keeps its scale.
        assertEquals(1f, Normalization.of(Vec3(0f, 0f, 0f), Vec3(0f, 5f, 0f), 20f).fitScale)
        // Furniture: fit 4 x 2 model into a 2 x 2 footprint -> limited by width.
        assertEquals(0.5f, Normalization.fitInto(Vec3(0f, 0f, 0f), Vec3(4f, 1f, 2f), 2f, 2f).fitScale)
    }
}
