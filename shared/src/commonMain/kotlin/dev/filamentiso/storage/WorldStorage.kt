package dev.filamentiso.storage

import dev.filamentiso.world.model.Building
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * The only storage a platform must provide: bytes by key, on the device.
 * Android: files in the app's private dir. Web: IndexedDB.
 * Keys look like "assets/abc.glb" (letters, digits, '_', '-', '.', '/').
 */
interface BlobStore {
    suspend fun put(key: String, bytes: ByteArray)
    suspend fun get(key: String): ByteArray?
    suspend fun delete(key: String)
    suspend fun keys(prefix: String): List<String>
}

/** Storage failures, with a message the UI can show. */
class StorageException(message: String, val outOfSpace: Boolean = false, cause: Throwable? = null) : Exception(message, cause) {
    companion object {
        fun full(cause: Throwable? = null) = StorageException("Your device is out of storage space. Free some space and try again.", outOfSpace = true, cause = cause)
        fun failed(cause: Throwable? = null) = StorageException("Could not save on this device (${cause?.message ?: "unknown error"}).", cause = cause)
    }
}

class InMemoryBlobStore : BlobStore {
    private val map = LinkedHashMap<String, ByteArray>()
    override suspend fun put(key: String, bytes: ByteArray) { map[key] = bytes.copyOf() }
    override suspend fun get(key: String) = map[key]?.copyOf()
    override suspend fun delete(key: String) { map.remove(key) }
    override suspend fun keys(prefix: String) = map.keys.filter { it.startsWith(prefix) }
}

/** Raw .glb bytes of imported models. Behind an interface so a backend can replace it later. */
interface AssetStore {
    suspend fun save(assetId: String, bytes: ByteArray): String
    suspend fun load(storagePath: String): ByteArray?
    suspend fun delete(storagePath: String)
}

/** Buildings (with their interiors). Behind an interface so a backend can replace it later. */
interface BuildingRepository {
    suspend fun list(): List<Building>
    suspend fun get(id: String): Building?
    suspend fun save(building: Building)
    suspend fun delete(id: String)
}

class BlobAssetStore(private val blobs: BlobStore) : AssetStore {
    override suspend fun save(assetId: String, bytes: ByteArray): String {
        val path = "assets/$assetId.glb"
        blobs.put(path, bytes)
        return path
    }

    override suspend fun load(storagePath: String) = blobs.get(storagePath)
    override suspend fun delete(storagePath: String) = blobs.delete(storagePath)
}

class BlobBuildingRepository(private val blobs: BlobStore) : BuildingRepository {
    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, Building>()
    private var loaded = false

    private suspend fun ensureLoaded() {
        if (loaded) return
        for (key in blobs.keys("buildings/")) {
            val text = blobs.get(key)?.decodeToString() ?: continue
            // A file from a newer/broken version should not break the whole app.
            runCatching { WorldJson.json.decodeFromString(Building.serializer(), text) }.getOrNull()?.let { cache[it.id] = it }
        }
        loaded = true
    }

    override suspend fun list(): List<Building> = mutex.withLock { ensureLoaded(); cache.values.toList() }
    override suspend fun get(id: String): Building? = mutex.withLock { ensureLoaded(); cache[id] }

    override suspend fun save(building: Building) = mutex.withLock {
        ensureLoaded()
        blobs.put(key(building.id), WorldJson.json.encodeToString(Building.serializer(), building).encodeToByteArray())
        cache[building.id] = building
    }

    override suspend fun delete(id: String) = mutex.withLock {
        ensureLoaded()
        blobs.delete(key(id))
        cache.remove(id)
        Unit
    }

    private fun key(id: String) = "buildings/$id.json"
}

object WorldJson {
    val json = Json {
        allowStructuredMapKeys = true // Level.openings: Map<Edge, OpeningType>
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}
