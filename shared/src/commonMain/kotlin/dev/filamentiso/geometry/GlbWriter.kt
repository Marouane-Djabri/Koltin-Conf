package dev.filamentiso.geometry

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Writes [MeshPart]s as a binary glTF 2.0 (.glb) in pure Kotlin.
 *
 * Layout: 12-byte header, JSON chunk, BIN chunk. One mesh with one primitive per part,
 * one material per distinct [MaterialSpec]. Colors are given in sRGB and stored linear,
 * as glTF requires.
 */
object GlbWriter {
    private const val GLB_MAGIC = 0x46546C67 // "glTF"
    private const val CHUNK_JSON = 0x4E4F534A
    private const val CHUNK_BIN = 0x004E4942
    private const val FLOAT = 5126
    private const val UINT32 = 5125
    private const val ARRAY_BUFFER = 34962
    private const val ELEMENT_ARRAY_BUFFER = 34963

    fun write(parts: List<MeshPart>, name: String = "generated"): ByteArray {
        require(parts.isNotEmpty()) { "Nothing to write" }
        val bin = ByteSink()
        val bufferViews = mutableListOf<JsonElement>()
        val accessors = mutableListOf<JsonElement>()
        val materials = parts.map { it.material }.distinct()
        val primitives = mutableListOf<JsonElement>()

        fun view(byteOffset: Int, byteLength: Int, target: Int): Int {
            bufferViews += buildJsonObject {
                put("buffer", 0); put("byteOffset", byteOffset); put("byteLength", byteLength); put("target", target)
            }
            return bufferViews.size - 1
        }

        for (part in parts) {
            // POSITION
            val posOffset = bin.size
            part.positions.forEach { bin.float(it) }
            val posView = view(posOffset, bin.size - posOffset, ARRAY_BUFFER)
            val (mn, mx) = minMax(part.positions)
            accessors += buildJsonObject {
                put("bufferView", posView); put("componentType", FLOAT); put("count", part.vertexCount); put("type", "VEC3")
                putJsonArray("min") { mn.forEach { add(it) } }
                putJsonArray("max") { mx.forEach { add(it) } }
            }
            val posAcc = accessors.size - 1
            // NORMAL
            val nrmOffset = bin.size
            part.normals.forEach { bin.float(it) }
            val nrmView = view(nrmOffset, bin.size - nrmOffset, ARRAY_BUFFER)
            accessors += buildJsonObject {
                put("bufferView", nrmView); put("componentType", FLOAT); put("count", part.vertexCount); put("type", "VEC3")
            }
            val nrmAcc = accessors.size - 1
            // indices
            val idxOffset = bin.size
            part.indices.forEach { bin.int(it) }
            val idxView = view(idxOffset, bin.size - idxOffset, ELEMENT_ARRAY_BUFFER)
            accessors += buildJsonObject {
                put("bufferView", idxView); put("componentType", UINT32); put("count", part.indices.size); put("type", "SCALAR")
            }
            val idxAcc = accessors.size - 1

            primitives += buildJsonObject {
                putJsonObject("attributes") { put("POSITION", posAcc); put("NORMAL", nrmAcc) }
                put("indices", idxAcc)
                put("material", materials.indexOf(part.material))
                put("mode", 4)
            }
        }

        val usesUnlit = materials.any { it.unlit }
        val usesEmissiveStrength = materials.any { it.emissive != null && it.emissiveStrength != 1f }
        val json = buildJsonObject {
            putJsonObject("asset") { put("version", "2.0"); put("generator", "FilamentIso GlbWriter") }
            if (usesUnlit || usesEmissiveStrength) putJsonArray("extensionsUsed") {
                if (usesUnlit) add("KHR_materials_unlit")
                if (usesEmissiveStrength) add("KHR_materials_emissive_strength")
            }
            put("scene", 0)
            putJsonArray("scenes") { addJsonObject { putJsonArray("nodes") { add(0) } } }
            putJsonArray("nodes") { addJsonObject { put("name", name); put("mesh", 0) } }
            putJsonArray("meshes") { addJsonObject { put("name", name); put("primitives", JsonArray(primitives)) } }
            putJsonArray("materials") {
                for (m in materials) addJsonObject {
                    put("name", m.name)
                    put("doubleSided", false)
                    putJsonObject("pbrMetallicRoughness") {
                        val c = m.color.toLinear()
                        putJsonArray("baseColorFactor") { add(c[0]); add(c[1]); add(c[2]); add(m.alpha) }
                        put("metallicFactor", m.metallic)
                        put("roughnessFactor", m.roughness)
                    }
                    if (m.alpha < 1f) put("alphaMode", "BLEND")
                    m.emissive?.let { e ->
                        val l = e.toLinear()
                        putJsonArray("emissiveFactor") { add(l[0]); add(l[1]); add(l[2]) }
                    }
                    if (m.unlit || (m.emissive != null && m.emissiveStrength != 1f)) putJsonObject("extensions") {
                        if (m.unlit) putJsonObject("KHR_materials_unlit") { }
                        if (m.emissive != null && m.emissiveStrength != 1f)
                            putJsonObject("KHR_materials_emissive_strength") { put("emissiveStrength", m.emissiveStrength) }
                    }
                }
            }
            putJsonArray("accessors") { accessors.forEach { add(it) } }
            putJsonArray("bufferViews") { bufferViews.forEach { add(it) } }
            putJsonArray("buffers") { addJsonObject { put("byteLength", bin.size) } }
        }

        val jsonBytes = pad(json.toString().encodeToByteArray(), 0x20)
        val binBytes = pad(bin.toByteArray(), 0)
        val out = ByteSink(12 + 8 + jsonBytes.size + 8 + binBytes.size)
        out.int(GLB_MAGIC); out.int(2); out.int(12 + 8 + jsonBytes.size + 8 + binBytes.size)
        out.int(jsonBytes.size); out.int(CHUNK_JSON); out.bytes(jsonBytes)
        out.int(binBytes.size); out.int(CHUNK_BIN); out.bytes(binBytes)
        return out.toByteArray()
    }

    private fun minMax(p: FloatArray): Pair<FloatArray, FloatArray> {
        val mn = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val mx = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in p.indices) {
            val k = i % 3
            if (p[i] < mn[k]) mn[k] = p[i]
            if (p[i] > mx[k]) mx[k] = p[i]
        }
        return mn to mx
    }

    private fun pad(b: ByteArray, fill: Int): ByteArray {
        val rem = b.size % 4
        if (rem == 0) return b
        return b + ByteArray(4 - rem) { fill.toByte() }
    }
}

/** Growable little-endian byte buffer. */
internal class ByteSink(initial: Int = 1024) {
    private var buf = ByteArray(initial.coerceAtLeast(16))
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + extra))
    }

    fun int(v: Int) {
        ensure(4)
        buf[size] = v.toByte(); buf[size + 1] = (v shr 8).toByte()
        buf[size + 2] = (v shr 16).toByte(); buf[size + 3] = (v shr 24).toByte()
        size += 4
    }

    fun float(f: Float) = int(f.toRawBits())

    fun bytes(b: ByteArray) {
        ensure(b.size); b.copyInto(buf, size); size += b.size
    }

    fun toByteArray() = buf.copyOf(size)
}
