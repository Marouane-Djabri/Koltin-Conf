package dev.filamentiso

import android.content.Context
import android.util.Log
import dev.filamentiso.model.CatalogItem
import dev.filamentiso.model.House
import dev.filamentiso.storage.HouseJson
import dev.filamentiso.storage.HouseStore
import java.io.File

/** One JSON file per house in the app's private storage. */
class FileHouseStore(context: Context) : HouseStore {
    private val dir = File(context.filesDir, "houses").apply { mkdirs() }

    override fun list(): List<House> =
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .mapNotNull { f ->
                try {
                    HouseJson.decode(f.readText())
                } catch (e: Exception) {
                    Log.w("FilamentIso", "Skipping unreadable ${f.name}", e); null
                }
            }

    override fun save(house: House) {
        val tmp = File(dir, "${house.id}.json.tmp")
        tmp.writeText(HouseJson.encode(house))
        tmp.renameTo(File(dir, "${house.id}.json")) // atomic replace
    }

    override fun delete(id: String) {
        File(dir, "$id.json").delete()
    }
}

/** Blender furniture listed in assets/furniture/catalog.json (optional). */
fun loadAssetCatalog(context: Context): List<CatalogItem> = try {
    context.assets.open("furniture/catalog.json").use { HouseJson.decodeCatalog(it.readBytes().decodeToString()) }
} catch (e: java.io.FileNotFoundException) {
    emptyList()
} catch (e: Exception) {
    Log.e("FilamentIso", "furniture/catalog.json is invalid", e); emptyList()
}
