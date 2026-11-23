package dev.filamentiso.storage

import kotlinx.browser.window
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [BlobStore] on IndexedDB: one object store, key → bytes. Holds many MB of binary data,
 * unlike localStorage. Kotlin's ByteArray is an Int8Array in JS, which IndexedDB stores as is.
 */
class IndexedDbBlobStore(private val dbName: String = "filamentiso-world") : BlobStore {
    private val storeName = "blobs"
    private var db: dynamic = null

    private suspend fun open(): dynamic {
        if (db != null) return db
        val idb: dynamic = window.asDynamic().indexedDB ?: throw StorageException("This browser has no IndexedDB, so nothing can be saved.")
        return suspendCancellableCoroutine { cont ->
            val req = idb.open(dbName, 1)
            req.onupgradeneeded = { _: dynamic -> req.result.createObjectStore(storeName); Unit }
            req.onsuccess = { _: dynamic -> db = req.result; cont.resume(req.result); Unit }
            req.onerror = { _: dynamic -> cont.resumeWithException(StorageException.failed(Exception(req.error?.message as? String))); Unit }
        }
    }

    private suspend fun <T> request(mode: String, block: (store: dynamic) -> dynamic): T {
        val d = open()
        return suspendCancellableCoroutine { cont ->
            val tx = d.transaction(storeName, mode)
            val req = block(tx.objectStore(storeName))
            var result: dynamic = null
            req.onsuccess = { _: dynamic -> result = req.result; Unit }
            tx.oncomplete = { _: dynamic -> cont.resume(result.unsafeCast<T>()); Unit }
            val fail = { _: dynamic ->
                val err = tx.error ?: req.error
                val name = err?.name as? String
                cont.resumeWithException(
                    if (name == "QuotaExceededError") StorageException.full() else StorageException.failed(Exception(err?.message as? String)),
                )
                Unit
            }
            tx.onerror = fail
            tx.onabort = fail
        }
    }

    override suspend fun put(key: String, bytes: ByteArray) {
        request<Any?>("readwrite") { it.put(bytes, key) }
    }

    override suspend fun get(key: String): ByteArray? {
        val v: dynamic = request<dynamic>("readonly") { it.get(key) }
        if (v == null || v == undefined) return null
        // Stored as Int8Array (= ByteArray). Accept other typed arrays too.
        return if (js("v instanceof Int8Array") as Boolean) v.unsafeCast<ByteArray>()
        else js("new Int8Array(v.buffer || v)").unsafeCast<ByteArray>()
    }

    override suspend fun delete(key: String) {
        request<Any?>("readwrite") { it.delete(key) }
    }

    override suspend fun keys(prefix: String): List<String> {
        val all: Array<dynamic> = request("readonly") { it.getAllKeys() }
        return all.map { it.toString() }.filter { it.startsWith(prefix) }
    }
}
