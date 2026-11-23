package dev.filamentiso.storage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** [BlobStore] on the app's private files dir. Writes go to a temp file, then an atomic rename. */
class FileBlobStore(context: Context) : BlobStore {
    private val root = File(context.filesDir, "world").apply { mkdirs() }

    private fun file(key: String): File {
        require(key.matches(Regex("[A-Za-z0-9_./-]+")) && ".." !in key) { "Bad key: $key" }
        return File(root, key)
    }

    override suspend fun put(key: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val f = file(key)
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw IOException("rename failed") }
        } catch (e: IOException) {
            tmp.delete()
            // ENOSPC surfaces as an IOException whose message mentions the free space.
            val full = e.message?.contains("ENOSPC") == true || e.message?.contains("No space", ignoreCase = true) == true ||
                root.usableSpace < bytes.size
            throw if (full) StorageException.full(e) else StorageException.failed(e)
        }
    }

    override suspend fun get(key: String): ByteArray? = withContext(Dispatchers.IO) {
        file(key).takeIf { it.isFile }?.readBytes()
    }

    override suspend fun delete(key: String) {
        withContext(Dispatchers.IO) { file(key).delete() }
    }

    override suspend fun keys(prefix: String): List<String> = withContext(Dispatchers.IO) {
        root.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter { it.startsWith(prefix) }
            .toList()
    }
}
