package dev.filamentiso.storage

import dev.filamentiso.editor.addRectRoom
import dev.filamentiso.editor.placeFurniture
import dev.filamentiso.editor.toggleOpening
import dev.filamentiso.model.Catalog
import dev.filamentiso.model.CatalogItem
import dev.filamentiso.model.Edge
import dev.filamentiso.model.FurnitureItem
import dev.filamentiso.model.GridPoint
import dev.filamentiso.model.House
import dev.filamentiso.model.Level
import dev.filamentiso.model.OpeningType
import dev.filamentiso.model.newId
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Where houses are kept. Android: one JSON file per house in app storage. */
interface HouseStore {
    fun list(): List<House>
    fun save(house: House)
    fun delete(id: String)
}

class InMemoryHouseStore : HouseStore {
    private val houses = LinkedHashMap<String, House>()
    override fun list() = houses.values.toList()
    override fun save(house: House) { houses[house.id] = house }
    override fun delete(id: String) { houses.remove(id) }
}

object HouseJson {
    val json = Json {
        allowStructuredMapKeys = true // openings: Map<Edge, OpeningType>
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun encode(house: House): String = json.encodeToString(House.serializer(), house)
    fun decode(text: String): House = json.decodeFromString(House.serializer(), text)
    fun decodeCatalog(text: String): List<CatalogItem> = json.decodeFromString(ListSerializer(CatalogItem.serializer()), text)
}

object Templates {
    fun emptyBasement(name: String) = House(newId("house_"), name)

    /** A furnished 4-room basement, built with the same edit functions the editor uses. */
    fun sampleBasement(name: String, catalog: Catalog): House {
        var l = Level()
        fun g(x: Int, z: Int) = GridPoint(x, z)
        l = l.addRectRoom(g(0, 0), g(11, 9), "Living")
        l = l.addRectRoom(g(12, 0), g(19, 9), "Workshop")
        l = l.addRectRoom(g(0, 10), g(7, 15), "Laundry")
        l = l.addRectRoom(g(8, 10), g(13, 15), "Office")

        fun door(e: Edge) { l = l.toggleOpening(e, OpeningType.DOOR) }
        fun window(e: Edge) { l = l.toggleOpening(e, OpeningType.WINDOW) }
        door(Edge.of(g(12, 4), g(12, 5)))   // living ↔ workshop
        door(Edge.of(g(3, 10), g(4, 10)))   // living ↔ laundry
        door(Edge.of(g(12, 10), g(13, 10))) // office ↔ workshop
        door(Edge.of(g(8, 12), g(8, 13)))   // laundry ↔ office
        window(Edge.of(g(2, 0), g(3, 0)))
        window(Edge.of(g(8, 0), g(9, 0)))
        window(Edge.of(g(15, 0), g(16, 0)))
        window(Edge.of(g(20, 6), g(20, 7)))

        fun put(id: String, x: Int, z: Int, turns: Int = 0) {
            l = l.placeFurniture(FurnitureItem(newId("f_"), id, g(x, z), turns), catalog)
        }
        // Living
        put("stairs", 0, 3)
        put("rug", 4, 6)
        put("sofa", 4, 5)
        put("tv", 4, 9, 2)
        put("armchair", 9, 5)
        put("lamp", 8, 5)
        put("plant", 11, 0)
        put("table", 5, 1)
        put("chair", 4, 1)
        put("chair", 8, 2, 2)
        // Workshop
        put("workbench", 14, 0)
        put("shelving", 19, 3, 1)
        put("pingpong", 13, 5)
        put("boxes", 19, 8)
        put("boxes", 18, 9)
        put("boxes", 19, 9)
        // Laundry
        put("washer", 0, 12)
        put("washer", 0, 13)
        put("heater", 0, 15)
        put("fridge", 7, 15)
        put("shelving", 5, 15, 2)
        // Office
        put("desk", 9, 10)
        put("chair", 10, 12, 2)
        put("bookshelf", 11, 15, 2)
        put("plant", 13, 15)
        return House(newId("house_"), name, l)
    }
}
