package dev.filamentiso.world.scene

import dev.filamentiso.Rgb
import dev.filamentiso.model.Edge
import dev.filamentiso.model.GridPoint
import dev.filamentiso.model.OpeningType
import dev.filamentiso.model.Room
import dev.filamentiso.model.Status
import dev.filamentiso.world.geo.LatLng
import kotlinx.serialization.Serializable

@Serializable
enum class SceneKind { OUTDOOR_PLOT, BUILDING_FLOOR }

/** What a tile is made of (sRGB, soft pastels). */
@Serializable
enum class Ground(val label: String, val color: Rgb, val walkable: Boolean = true) {
    GRASS("Grass", Rgb(0.70f, 0.85f, 0.62f)),
    SAND("Sand", Rgb(0.95f, 0.88f, 0.70f)),
    WATER("Water", Rgb(0.58f, 0.81f, 0.90f), walkable = false),
    PATH("Path", Rgb(0.87f, 0.80f, 0.72f)),
    WOOD("Wood", Rgb(0.82f, 0.66f, 0.49f)),
    TILE("Tile", Rgb(0.86f, 0.91f, 0.94f)),
    CARPET("Carpet", Rgb(0.86f, 0.75f, 0.87f)),
    CONCRETE("Concrete", Rgb(0.78f, 0.78f, 0.76f)),
    TERRAZZO("Terrazzo", Rgb(0.95f, 0.90f, 0.82f));

    companion object { val outdoor = listOf(GRASS, SAND, WATER, PATH) }
}

/** A size in tiles. */
@Serializable
data class TileSize(val width: Int, val depth: Int)

/** Something standing in a scene. Position is the footprint centre in metres; [footprint] is after rotation. */
@Serializable
data class PlacedObject(
    val id: String,
    val catalogId: String? = null,
    val assetId: String? = null,
    val x: Float,
    val z: Float,
    val rotationDeg: Float = 0f,
    val scale: Float = 1f,
    val footprint: TileSize = TileSize(1, 1),
    val blocksWalking: Boolean = true,
    val color: Rgb? = null,
    /** Activity zone set in the interior editor. */
    val status: Status? = null,
)

/** Where the avatar stands (or sits) to do an action. [objectId] null = a free spot on the ground. */
@Serializable
data class InteractionSpot(
    val id: String,
    val objectId: String? = null,
    val x: Float,
    val z: Float,
    val facingDeg: Float = 0f,
    val actionId: String,
)

/** The parts only building floors have. */
@Serializable
data class InteriorInfo(
    val rooms: List<Room>,
    val openings: Map<Edge, OpeningType>,
    val wallHeight: Float,
    val wallThickness: Float,
    val wallColor: Rgb,
)

/**
 * A place people spend time in: an outdoor plot or one floor of a building.
 * Local axes are metres from the north-west corner: x east, z south (before [headingDeg]).
 */
@Serializable
data class Scene(
    val id: String,
    val ownerId: String,
    val name: String,
    val kind: SceneKind,
    /** Centre of the scene on the map. */
    val anchor: LatLng,
    val headingDeg: Float = 0f,
    /** In tiles. */
    val width: Int,
    val depth: Int,
    /** 1 m outdoors; building floors keep the interior editor's 0.5 m tiles. */
    val tileSizeM: Float = 1f,
    /** Sparse: missing tiles are grass outdoors, or the room's floor colour indoors. */
    val tiles: Map<GridPoint, Ground> = emptyMap(),
    val walls: Set<Edge> = emptySet(),
    val objects: List<PlacedObject> = emptyList(),
    val spots: List<InteractionSpot> = emptyList(),
    val interior: InteriorInfo? = null,
    val formatVersion: Int = 1,
) {
    val widthM get() = width * tileSizeM
    val depthM get() = depth * tileSizeM

    fun contains(t: GridPoint) = t.x in 0 until width && t.z in 0 until depth

    fun groundAt(t: GridPoint): Ground = tiles[t] ?: if (kind == SceneKind.OUTDOOR_PLOT) Ground.GRASS else Ground.WOOD

    /** Paints the tiles that are inside the scene. Returns this same scene when nothing changes. */
    fun paint(cells: Set<GridPoint>, ground: Ground): Scene {
        val changed = cells.filter { contains(it) && groundAt(it) != ground }
        return if (changed.isEmpty()) this else copy(tiles = tiles + changed.associateWith { ground })
    }
}

/** Square brush of [size] (odd) tiles around [center]. */
fun brushCells(center: GridPoint, size: Int): Set<GridPoint> {
    val r = size / 2
    return buildSet { for (dx in -r..r) for (dz in -r..r) add(GridPoint(center.x + dx, center.z + dz)) }
}
