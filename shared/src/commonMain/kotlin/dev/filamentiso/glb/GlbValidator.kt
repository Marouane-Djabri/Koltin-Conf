package dev.filamentiso.glb

import dev.filamentiso.Vec3
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Size limits for an imported model. Tweak here. */
data class GlbLimits(
    val maxBytes: Long,
    val maxTriangles: Int,
    val maxTextureSize: Int,
    val maxMaterials: Int,
) {
    companion object {
        val Building = GlbLimits(maxBytes = 15L * 1024 * 1024, maxTriangles = 50_000, maxTextureSize = 2048, maxMaterials = 8)
        val Furniture = GlbLimits(maxBytes = 5L * 1024 * 1024, maxTriangles = 15_000, maxTextureSize = 2048, maxMaterials = 8)
    }
}

/** What we learned about a valid file. Bounding box is in glTF units (metres), Y up, node transforms applied. */
data class GlbInfo(
    val sizeBytes: Long,
    val triangleCount: Int,
    val materialCount: Int,
    val textureCount: Int,
    /** Largest texture side we could read from embedded PNG/JPEG headers; 0 if none or unknown. */
    val maxTextureSide: Int,
    val bboxMin: Vec3,
    val bboxMax: Vec3,
)

/** Why a file was refused. [message] is shown to the user as is. */
sealed class GlbProblem(val message: String) {
    class TooBig(val bytes: Long, val limit: Long) :
        GlbProblem("This file is ${mb(bytes)} MB. The limit is ${mb(limit)} MB.")
    object NotGlb : GlbProblem("This is not a .glb file. Export your model as glTF Binary (.glb).")
    object GltfText : GlbProblem("This is a .gltf (text) file. Please export as glTF Binary (.glb) so textures are inside the file.")
    class WrongVersion(val version: Int) : GlbProblem("This is glTF version $version. Only glTF 2.0 is supported.")
    class Corrupt(detail: String) : GlbProblem("The file looks damaged ($detail). Try exporting it again.")
    object Compressed : GlbProblem("This model uses mesh compression (Draco / meshopt). Please re-export without compression.")
    class UnsupportedExtension(val name: String) : GlbProblem("This model needs the glTF extension $name, which is not supported. Please re-export without it.")
    object ExternalFiles : GlbProblem("This model points to external files. Please export a self-contained .glb.")
    object NoGeometry : GlbProblem("This file has no 3D geometry in it.")
    class TooManyTriangles(val count: Int, val limit: Int) :
        GlbProblem("This model has about ${count / 1000}k triangles. The limit is ${limit / 1000}k. Try decimating it.")
    class TooManyMaterials(val count: Int, val limit: Int) :
        GlbProblem("This model has $count materials. The limit is $limit.")
    class TextureTooLarge(val side: Int, val limit: Int) :
        GlbProblem("A texture is $side px wide. The limit is $limit px. Please resize textures.")

    companion object {
        private fun mb(b: Long): String {
            val tenths = (b * 10 + 512 * 1024) / (1024 * 1024)
            return "${tenths / 10}.${tenths % 10}"
        }
    }
}

sealed interface GlbCheck {
    data class Valid(val info: GlbInfo) : GlbCheck
    data class Invalid(val problem: GlbProblem) : GlbCheck {
        val message get() = problem.message
    }
}

/**
 * Checks a .glb before any renderer sees it. Pure Kotlin, so Android and the Web refuse exactly
 * the same files with the same messages.
 *
 * Compression extensions are refused on purpose: three.js would need hosted Draco/meshopt
 * decoders and we want identical behavior on both platforms.
 */
object GlbValidator {
    private const val MAGIC = 0x46546C67 // "glTF"
    private const val CHUNK_JSON = 0x4E4F534A
    private const val CHUNK_BIN = 0x004E4942

    val compressionExtensions = setOf("KHR_draco_mesh_compression", "EXT_meshopt_compression")

    /** Required extensions both Filament gltfio and three.js GLTFLoader handle without extra decoders. */
    val supportedRequiredExtensions = setOf(
        "KHR_materials_unlit",
        "KHR_texture_transform",
        "KHR_mesh_quantization",
        "KHR_materials_emissive_strength",
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun validate(fileName: String, bytes: ByteArray, limits: GlbLimits): GlbCheck {
        val name = fileName.lowercase()
        if (name.endsWith(".gltf")) return GlbCheck.Invalid(GlbProblem.GltfText)
        return validate(bytes, limits)
    }

    fun validate(bytes: ByteArray, limits: GlbLimits): GlbCheck = try {
        check(bytes, limits)
    } catch (e: Invalid) {
        GlbCheck.Invalid(e.problem)
    } catch (e: Exception) {
        // Unexpected JSON shapes (e.g. a string where a number should be).
        GlbCheck.Invalid(GlbProblem.Corrupt("unreadable glTF data"))
    }

    private class Invalid(val problem: GlbProblem) : Exception(problem.message)
    private fun fail(p: GlbProblem): Nothing = throw Invalid(p)

    private fun check(bytes: ByteArray, limits: GlbLimits): GlbCheck {
        val size = bytes.size.toLong()
        if (size > limits.maxBytes) fail(GlbProblem.TooBig(size, limits.maxBytes))
        if (size >= 1 && bytes.firstNonSpace() == '{'.code.toByte()) fail(GlbProblem.GltfText)
        if (size < 12 || bytes.int32(0) != MAGIC) fail(GlbProblem.NotGlb)
        val version = bytes.int32(4)
        if (version != 2) fail(GlbProblem.WrongVersion(version))
        val declared = bytes.int32(8).toLong() and 0xFFFFFFFFL
        if (declared != size) fail(GlbProblem.Corrupt("length is $declared bytes in the header but the file has $size"))

        // Chunks: JSON first (required), then an optional BIN.
        var offset = 12
        var jsonText: String? = null
        var bin: ByteArray? = null
        while (offset < bytes.size) {
            if (offset + 8 > bytes.size) fail(GlbProblem.Corrupt("truncated chunk header"))
            val len = bytes.int32(offset)
            val type = bytes.int32(offset + 4)
            val start = offset + 8
            if (len < 0 || start + len > bytes.size) fail(GlbProblem.Corrupt("chunk runs past the end of the file"))
            when {
                jsonText == null && type != CHUNK_JSON -> fail(GlbProblem.Corrupt("first chunk is not JSON"))
                type == CHUNK_JSON && jsonText == null -> jsonText = bytes.decodeToString(start, start + len)
                type == CHUNK_BIN && bin == null -> bin = bytes.copyOfRange(start, start + len)
            }
            offset = start + len
        }
        val root = try {
            json.parseToJsonElement(jsonText ?: fail(GlbProblem.Corrupt("no JSON chunk"))).jsonObject
        } catch (e: Invalid) { throw e } catch (e: Exception) {
            fail(GlbProblem.Corrupt("invalid JSON"))
        }

        val assetVersion = root.obj("asset")?.str("version")
        if (assetVersion != null && !assetVersion.startsWith("2.")) fail(GlbProblem.WrongVersion(assetVersion.substringBefore('.').toIntOrNull() ?: 0))

        // Extensions
        val required = root.arr("extensionsRequired")?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        if (required.any { it in compressionExtensions }) fail(GlbProblem.Compressed)
        required.firstOrNull { it !in supportedRequiredExtensions }?.let { fail(GlbProblem.UnsupportedExtension(it)) }
        val used = root.arr("extensionsUsed")?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        if (used.any { it in compressionExtensions }) fail(GlbProblem.Compressed) // "optional" compression still needs a decoder

        // Self-contained: no external buffers or images.
        val buffers = root.arr("buffers") ?: JsonArray(emptyList())
        for (b in buffers) {
            val uri = b.jsonObject.str("uri")
            if (uri != null && !uri.startsWith("data:")) fail(GlbProblem.ExternalFiles)
        }
        val images = root.arr("images") ?: JsonArray(emptyList())
        for (img in images) {
            val uri = img.jsonObject.str("uri")
            if (uri != null && !uri.startsWith("data:")) fail(GlbProblem.ExternalFiles)
        }

        val accessors = root.arr("accessors") ?: JsonArray(emptyList())
        val bufferViews = root.arr("bufferViews") ?: JsonArray(emptyList())
        val meshes = root.arr("meshes") ?: JsonArray(emptyList())
        if (meshes.isEmpty()) fail(GlbProblem.NoGeometry)

        // Every buffer view must fit inside its buffer.
        for ((i, v) in bufferViews.withIndex()) {
            val o = v.jsonObject
            val buf = o.int("buffer") ?: fail(GlbProblem.Corrupt("buffer view $i has no buffer"))
            val bufLen = buffers.getOrNull(buf)?.jsonObject?.int("byteLength") ?: fail(GlbProblem.Corrupt("missing buffer $buf"))
            val end = (o.int("byteOffset") ?: 0).toLong() + (o.int("byteLength") ?: 0)
            if (end > bufLen) fail(GlbProblem.Corrupt("buffer view $i is out of range"))
            if (buf == 0 && buffers[0].jsonObject.str("uri") == null && end > (bin?.size ?: 0)) {
                fail(GlbProblem.Corrupt("binary chunk is too short"))
            }
        }

        // Materials and textures
        val materialCount = root.arr("materials")?.size ?: 0
        if (materialCount > limits.maxMaterials) fail(GlbProblem.TooManyMaterials(materialCount, limits.maxMaterials))
        var maxSide = 0
        for (img in images) {
            val view = img.jsonObject.int("bufferView") ?: continue
            val data = viewBytes(bufferViews, view, bin) ?: continue
            val side = ImageSize.maxSide(data) ?: continue
            if (side > maxSide) maxSide = side
        }
        if (maxSide > limits.maxTextureSize) fail(GlbProblem.TextureTooLarge(maxSide, limits.maxTextureSize))

        // Triangles + bounds, walking the scene graph so instanced meshes count once per instance.
        val nodes = root.arr("nodes") ?: JsonArray(emptyList())
        val meshTris = meshes.map { m -> m.jsonObject.arr("primitives").orEmpty().sumOf { primitiveTriangles(it.jsonObject, accessors) } }
        val meshBox = meshes.map { m -> meshBounds(m.jsonObject, accessors) }
        var triangles = 0L
        val box = BoxAcc()
        val roots = sceneRoots(root, nodes.size)
        val visiting = HashSet<Int>()
        fun walk(index: Int, parent: Mat4, depth: Int) {
            if (depth > 64 || !visiting.add(index)) fail(GlbProblem.Corrupt("node hierarchy has a cycle"))
            val node = nodes.getOrNull(index)?.jsonObject ?: fail(GlbProblem.Corrupt("missing node $index"))
            val world = parent * Mat4.ofNode(node)
            node.int("mesh")?.let { mi ->
                if (mi !in meshes.indices) fail(GlbProblem.Corrupt("missing mesh $mi"))
                triangles += meshTris[mi]
                meshBox[mi]?.let { (mn, mx) -> box.addTransformed(world, mn, mx) }
            }
            node.arr("children")?.forEach { walk(it.jsonPrimitive.intOrNull ?: fail(GlbProblem.Corrupt("bad child index")), world, depth + 1) }
            visiting.remove(index)
        }
        roots.forEach { walk(it, Mat4.IDENTITY, 0) }
        if (triangles == 0L) fail(GlbProblem.NoGeometry)
        if (triangles > limits.maxTriangles) fail(GlbProblem.TooManyTriangles(triangles.toInt(), limits.maxTriangles))
        val (mn, mx) = box.result() ?: fail(GlbProblem.NoGeometry)

        return GlbCheck.Valid(
            GlbInfo(
                sizeBytes = size,
                triangleCount = triangles.toInt(),
                materialCount = materialCount,
                textureCount = root.arr("textures")?.size ?: 0,
                maxTextureSide = maxSide,
                bboxMin = mn,
                bboxMax = mx,
            ),
        )
    }

    private fun sceneRoots(root: JsonObject, nodeCount: Int): List<Int> {
        val scenes = root.arr("scenes")
        if (scenes.isNullOrEmpty()) {
            // No scene: treat nodes that are nobody's child as roots.
            val children = root.arr("nodes").orEmpty()
                .flatMap { n -> n.jsonObject.arr("children").orEmpty().mapNotNull { it.jsonPrimitive.intOrNull } }.toSet()
            return (0 until nodeCount).filter { it !in children }
        }
        val sceneIndex = root.int("scene") ?: 0
        val scene = scenes.getOrNull(sceneIndex)?.jsonObject ?: fail(GlbProblem.Corrupt("missing scene $sceneIndex"))
        return scene.arr("nodes").orEmpty().map { it.jsonPrimitive.intOrNull ?: fail(GlbProblem.Corrupt("bad scene node")) }
    }

    private fun primitiveTriangles(p: JsonObject, accessors: JsonArray): Long {
        val count = p.int("indices")?.let { accessorCount(accessors, it) }
            ?: p.obj("attributes")?.int("POSITION")?.let { accessorCount(accessors, it) }
            ?: 0
        return when (p.int("mode") ?: 4) {
            4 -> count / 3L               // TRIANGLES
            5, 6 -> maxOf(0, count - 2).toLong() // STRIP, FAN
            else -> 0L                    // points / lines: no triangles
        }
    }

    private fun accessorCount(accessors: JsonArray, i: Int): Int =
        accessors.getOrNull(i)?.jsonObject?.int("count") ?: fail(GlbProblem.Corrupt("missing accessor $i"))

    /** Union of the POSITION min/max of every primitive (glTF requires min/max on POSITION). */
    private fun meshBounds(m: JsonObject, accessors: JsonArray): Pair<Vec3, Vec3>? {
        val acc = BoxAcc()
        for (p in m.arr("primitives").orEmpty()) {
            val pos = p.jsonObject.obj("attributes")?.int("POSITION") ?: continue
            val a = accessors.getOrNull(pos)?.jsonObject ?: fail(GlbProblem.Corrupt("missing accessor $pos"))
            val mn = a.arr("min")?.vec3() ?: continue
            val mx = a.arr("max")?.vec3() ?: continue
            acc.add(mn); acc.add(mx)
        }
        return acc.result()
    }

    private fun viewBytes(views: JsonArray, index: Int, bin: ByteArray?): ByteArray? {
        val v = views.getOrNull(index)?.jsonObject ?: return null
        if ((v.int("buffer") ?: 0) != 0 || bin == null) return null
        val start = v.int("byteOffset") ?: 0
        val len = minOf(v.int("byteLength") ?: 0, 64 * 1024) // headers are at the start
        if (start + len > bin.size) return null
        return bin.copyOfRange(start, start + len)
    }

    // --- tiny JSON helpers ---
    private fun JsonObject.obj(k: String) = this[k] as? JsonObject
    private fun JsonObject.arr(k: String) = this[k] as? JsonArray
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
    private fun JsonArray.vec3(): Vec3? {
        if (size < 3) return null
        val f = map { (it as? JsonPrimitive)?.floatOrNull ?: return null }
        return Vec3(f[0], f[1], f[2])
    }

    private fun ByteArray.firstNonSpace(): Byte? = firstOrNull { it != ' '.code.toByte() && it != '\n'.code.toByte() && it != '\r'.code.toByte() && it != '\t'.code.toByte() && it != 0xEF.toByte() && it != 0xBB.toByte() && it != 0xBF.toByte() }
}

internal fun ByteArray.int32(at: Int): Int =
    (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8) or
        ((this[at + 2].toInt() and 0xFF) shl 16) or ((this[at + 3].toInt() and 0xFF) shl 24)

/** Running min/max. */
internal class BoxAcc {
    private var mn: Vec3? = null
    private var mx: Vec3? = null

    fun add(p: Vec3) {
        val a = mn; val b = mx
        if (a == null || b == null) { mn = p; mx = p; return }
        mn = Vec3(minOf(a.x, p.x), minOf(a.y, p.y), minOf(a.z, p.z))
        mx = Vec3(maxOf(b.x, p.x), maxOf(b.y, p.y), maxOf(b.z, p.z))
    }

    fun addTransformed(m: Mat4, lo: Vec3, hi: Vec3) {
        for (i in 0 until 8) {
            add(m.transform(Vec3(if (i and 1 == 0) lo.x else hi.x, if (i and 2 == 0) lo.y else hi.y, if (i and 4 == 0) lo.z else hi.z)))
        }
    }

    fun result(): Pair<Vec3, Vec3>? { val a = mn ?: return null; val b = mx ?: return null; return a to b }
}

/** Width/height from PNG or JPEG headers, without decoding. */
internal object ImageSize {
    fun maxSide(b: ByteArray): Int? = png(b) ?: jpeg(b)

    private fun be32(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or
        ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
    private fun be16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun png(b: ByteArray): Int? {
        if (b.size < 24 || b[0] != 0x89.toByte() || b[1] != 'P'.code.toByte() || b[2] != 'N'.code.toByte()) return null
        return maxOf(be32(b, 16), be32(b, 20))
    }

    private fun jpeg(b: ByteArray): Int? {
        if (b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return null
        var i = 2
        while (i + 9 < b.size) {
            if (b[i] != 0xFF.toByte()) { i++; continue }
            val marker = b[i + 1].toInt() and 0xFF
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                return maxOf(be16(b, i + 5), be16(b, i + 7))
            }
            i += 2 + be16(b, i + 2)
        }
        return null
    }
}
