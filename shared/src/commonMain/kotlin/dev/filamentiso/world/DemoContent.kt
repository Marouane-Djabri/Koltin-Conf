package dev.filamentiso.world

import dev.filamentiso.editor.paintTiles
import dev.filamentiso.editor.placeFurniture
import dev.filamentiso.editor.rectCells
import dev.filamentiso.editor.setStatus
import dev.filamentiso.geometry.SampleBuildings
import dev.filamentiso.model.BuiltinCatalog
import dev.filamentiso.model.Catalog
import dev.filamentiso.model.FloorMaterial
import dev.filamentiso.model.FurnitureItem
import dev.filamentiso.model.GridPoint
import dev.filamentiso.model.Level
import dev.filamentiso.model.Status
import dev.filamentiso.storage.AssetStore
import dev.filamentiso.storage.BlobStore
import dev.filamentiso.world.geo.Places
import dev.filamentiso.world.model.Building
import dev.filamentiso.world.model.LOCAL_USER
import dev.filamentiso.world.model.ModelTransform
import dev.filamentiso.world.overlay.ModelProvider
import dev.filamentiso.world.overlay.OverlayProjector

/** A small demo street around the default map position, with a furnished cottage, so the first launch isn't empty. */
object DemoContent {
    const val DEMO_HOME_ID = "demo_home"

    fun buildings(): List<Building> {
        val c = Places.Algiers
        fun at(east: Double, north: Double) = OverlayProjector.offset(c, east, north)
        val home = Building(DEMO_HOME_ID, LOCAL_USER, "Demo cottage", at(0.0, 0.0), placeholderKind = SampleBuildings.Kind.COTTAGE,
            transform = ModelTransform(rotationDeg = 15f))
        return listOf(
            home.copy(floors = furnishedCottage(home)),
            Building("demo_mosque", LOCAL_USER, "Neighbourhood mosque", at(-38.0, 18.0), placeholderKind = SampleBuildings.Kind.MOSQUE),
            Building("demo_cafe", LOCAL_USER, "Corner café", at(30.0, -14.0), placeholderKind = SampleBuildings.Kind.CAFE,
                transform = ModelTransform(rotationDeg = -30f)),
            Building("demo_apartment", LOCAL_USER, "Apartment block", at(28.0, 30.0), placeholderKind = SampleBuildings.Kind.APARTMENT),
        )
    }

    /** Ground floor: living corner, kitchen, home office. Upstairs: a bedroom. Built with the editor's own edit functions. */
    fun furnishedCottage(home: Building): List<Level> {
        val model = ModelProvider(NoAssets).placeholder(home.placeholderKind!!)
        val (w, d) = InteriorController.footprintCellsOf(home, model)
        val catalog = Catalog(BuiltinCatalog.items)
        fun g(x: Int, z: Int) = GridPoint(x, z)

        var ground = InteriorController.newFloor(0, w, d)
        var n = 0
        fun put(id: String, x: Int, z: Int, turns: Int = 0, status: Status? = null) {
            val itemId = "demo_f${n++}"
            ground = ground.placeFurniture(FurnitureItem(itemId, id, g(x, z), turns), catalog)
            if (status != null) ground = ground.setStatus(itemId, status)
        }
        // Kitchen along the back wall, tiled.
        ground = ground.paintTiles(rectCells(g(w - 10, 0), g(w - 1, 7)), FloorMaterial.TILE)
        put("fridge", w - 9, 0)
        put("counter", w - 8, 0)
        put("coffee", w - 3, 0)
        put("table", w - 7, 3, status = Status.EATING)
        put("chair", w - 8, 4, turns = 1)
        put("chair", w - 3, 4, turns = 3)
        // Living corner at the front, wood floor.
        ground = ground.paintTiles(rectCells(g(0, d - 9), g(9, d - 1)), FloorMaterial.WOOD)
        put("rug", 2, d - 7)
        put("sofa", 2, d - 8, status = Status.RELAXING)
        put("tv", 2, d - 2, turns = 2)
        put("armchair", 7, d - 6, turns = 3, status = Status.RELAXING)
        put("lamp", 0, d - 8)
        put("plant", 9, d - 1)
        // Home office in the back-left corner.
        put("desk", 0, 0, status = Status.WORKING)
        put("chair", 1, 2, turns = 2)
        put("bookshelf", 4, 0)
        put("plant", 0, 3)

        var upstairs = InteriorController.newFloor(1, w, d).paintTiles(rectCells(g(0, 0), g(w - 1, d - 1)), FloorMaterial.CARPET)
        fun up(id: String, x: Int, z: Int, turns: Int = 0, status: Status? = null) {
            val itemId = "demo_u${n++}"
            upstairs = upstairs.placeFurniture(FurnitureItem(itemId, id, g(x, z), turns), catalog)
            if (status != null) upstairs = upstairs.setStatus(itemId, status)
        }
        up("bed", 2, 1, status = Status.SLEEPING)
        up("lamp", 0, 1)
        up("bookshelf", 7, 0)
        up("armchair", w - 4, d - 4, turns = 2, status = Status.RELAXING)
        up("plant", w - 1, 0)
        up("desk", w - 4, 0, status = Status.MEETING)
        return listOf(ground, upstairs)
    }

    /** True the first time it is called on this device, false afterwards. */
    fun firstRunFlag(blobs: BlobStore): suspend () -> Boolean = {
        val key = "meta/seeded_v1"
        if (blobs.get(key) != null) false else { blobs.put(key, byteArrayOf(1)); true }
    }

    /** Placeholders never touch the asset store. */
    private object NoAssets : AssetStore {
        override suspend fun save(assetId: String, bytes: ByteArray) = error("not used")
        override suspend fun load(storagePath: String): ByteArray? = null
        override suspend fun delete(storagePath: String) = Unit
    }
}
