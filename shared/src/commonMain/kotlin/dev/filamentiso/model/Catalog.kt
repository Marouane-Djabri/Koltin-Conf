package dev.filamentiso.model

import dev.filamentiso.Rgb
import kotlinx.serialization.Serializable

/**
 * One kind of furniture the user can place.
 *
 * - Built-in items have [file] = null and are generated in code (placeholders, see ProceduralFurniture).
 * - Blender items set [file] to a .glb path inside the Android assets (e.g. "furniture/sofa.glb").
 *   The model must be in metres, origin at the footprint centre on the floor, front facing +Z.
 */
@Serializable
data class CatalogItem(
    val id: String,
    val name: String,
    val category: String,
    /** Footprint in grid cells before rotation (width along X, depth along Z). */
    val footprintW: Int,
    val footprintD: Int,
    val height: Float,
    val file: String? = null,
    /** Material names that follow the item's chosen color. */
    val recolorable: List<String> = emptyList(),
    /** Palette the "Color" button cycles through. */
    val colors: List<Rgb> = emptyList(),
    /** Floor-layer items (rugs) only collide with other floor-layer items. */
    val floorLayer: Boolean = false,
) {
    fun footprint(quarterTurns: Int): Pair<Int, Int> =
        if (quarterTurns % 2 == 0) footprintW to footprintD else footprintD to footprintW
}

class Catalog(items: List<CatalogItem>) {
    val items: List<CatalogItem> = items.distinctBy { it.id }
    private val byId = this.items.associateBy { it.id }
    operator fun get(id: String): CatalogItem? = byId[id]
    val categories: List<String> get() = items.map { it.category }.distinct()
}

/** Cells covered by a placed item. Empty if its catalog entry is unknown. */
fun FurnitureItem.cells(catalog: Catalog): Set<GridPoint> {
    val c = catalog[catalogId] ?: return emptySet()
    val (w, d) = c.footprint(quarterTurns)
    return buildSet { for (dx in 0 until w) for (dz in 0 until d) add(GridPoint(cell.x + dx, cell.z + dz)) }
}
