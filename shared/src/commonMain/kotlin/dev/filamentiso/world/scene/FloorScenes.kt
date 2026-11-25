package dev.filamentiso.world.scene

import dev.filamentiso.model.Catalog
import dev.filamentiso.model.FloorMaterial
import dev.filamentiso.model.FurnitureItem
import dev.filamentiso.model.GridPoint
import dev.filamentiso.model.Level
import dev.filamentiso.world.geo.LatLng
import kotlin.math.roundToInt

/*
 * Building floors are stored as Scenes. The wall / room editor works on a Level, so these two
 * functions convert between them without losing anything.
 */

fun Level.toScene(id: String, ownerId: String, name: String, anchor: LatLng, headingDeg: Float, catalog: Catalog) = Scene(
    id = id, ownerId = ownerId, name = name, kind = SceneKind.BUILDING_FLOOR,
    anchor = anchor, headingDeg = headingDeg,
    width = plotWidth, depth = plotDepth, tileSizeM = cellSize,
    tiles = tiles.mapValues { Ground.valueOf(it.value.name) },
    walls = walls,
    objects = furniture.map { f ->
        val def = catalog[f.catalogId]
        val (w, d) = def?.footprint(f.quarterTurns) ?: (1 to 1)
        PlacedObject(
            id = f.id, catalogId = f.catalogId, assetId = def?.assetId,
            x = (f.cell.x + w / 2f) * cellSize, z = (f.cell.z + d / 2f) * cellSize,
            rotationDeg = f.quarterTurns * 90f, footprint = TileSize(w, d),
            blocksWalking = def?.floorLayer != true, color = f.color, status = f.status,
        )
    },
    interior = InteriorInfo(rooms, openings, wallHeight, wallThickness, wallColor),
)

fun Scene.toLevel(): Level {
    val info = requireNotNull(interior) { "Scene $id is not a building floor" }
    return Level(
        plotWidth = width, plotDepth = depth, cellSize = tileSizeM,
        wallHeight = info.wallHeight, wallThickness = info.wallThickness, wallColor = info.wallColor,
        walls = walls, openings = info.openings, rooms = info.rooms,
        furniture = objects.map { o ->
            FurnitureItem(
                id = o.id, catalogId = o.catalogId.orEmpty(),
                cell = GridPoint(
                    (o.x / tileSizeM - o.footprint.width / 2f).roundToInt(),
                    (o.z / tileSizeM - o.footprint.depth / 2f).roundToInt(),
                ),
                quarterTurns = (o.rotationDeg / 90f).roundToInt(), color = o.color, status = o.status,
            )
        },
        tiles = tiles.mapValues { FloorMaterial.valueOf(it.value.name) },
    )
}
