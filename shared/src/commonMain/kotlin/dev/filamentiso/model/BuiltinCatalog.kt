package dev.filamentiso.model

import dev.filamentiso.Rgb
import dev.filamentiso.geometry.MaterialSpec
import dev.filamentiso.geometry.MeshBuilder
import dev.filamentiso.geometry.MeshPart

/**
 * Placeholder furniture generated from boxes and prisms, so the editor works before any
 * Blender models exist. Each model: metres, origin at footprint centre on the floor, front +Z.
 * Replace any of these by adding a catalog entry with the same id and a `file` (see README).
 */
object BuiltinCatalog {
    private val FABRICS = listOf(
        Rgb(0.33f, 0.42f, 0.58f), Rgb(0.62f, 0.30f, 0.28f), Rgb(0.35f, 0.52f, 0.40f),
        Rgb(0.80f, 0.72f, 0.55f), Rgb(0.30f, 0.30f, 0.32f), Rgb(0.72f, 0.52f, 0.68f),
    )

    private fun item(id: String, name: String, cat: String, w: Int, d: Int, h: Float, recolor: Boolean = false, floor: Boolean = false) =
        CatalogItem(id, name, cat, w, d, h,
            recolorable = if (recolor) listOf("fabric") else emptyList(),
            colors = if (recolor) FABRICS else emptyList(), floorLayer = floor)

    val items: List<CatalogItem> = listOf(
        item("sofa", "Sofa", "Living", 4, 2, 0.85f, recolor = true),
        item("armchair", "Armchair", "Living", 2, 2, 0.85f, recolor = true),
        item("tv", "TV stand", "Living", 3, 1, 1.15f),
        item("rug", "Rug", "Living", 4, 3, 0.02f, recolor = true, floor = true),
        item("lamp", "Floor lamp", "Living", 1, 1, 1.6f),
        item("plant", "Plant", "Living", 1, 1, 1.1f),
        item("bed", "Bed", "Bedroom", 3, 4, 0.95f, recolor = true),
        item("table", "Table", "Dining", 3, 2, 0.76f),
        item("chair", "Chair", "Dining", 1, 1, 0.9f),
        item("desk", "Desk", "Office", 3, 2, 1.2f),
        item("bookshelf", "Bookshelf", "Office", 2, 1, 1.8f),
        item("workbench", "Workbench", "Basement", 4, 2, 1.6f),
        item("shelving", "Metal shelving", "Basement", 2, 1, 1.8f),
        item("boxes", "Storage boxes", "Basement", 1, 1, 0.75f),
        item("heater", "Water heater", "Basement", 1, 1, 1.5f),
        item("washer", "Washing machine", "Basement", 1, 1, 0.85f),
        item("fridge", "Fridge", "Basement", 1, 1, 1.8f),
        item("pingpong", "Ping-pong table", "Basement", 6, 3, 0.9f),
        item("stairs", "Stairs", "Basement", 2, 6, 2.4f),
    )

    // ------------------------------------------------------------------ materials
    private val WOOD = MaterialSpec("wood", Rgb(0.60f, 0.42f, 0.27f), roughness = 0.7f)
    private val DARK_WOOD = MaterialSpec("dark_wood", Rgb(0.36f, 0.25f, 0.17f), roughness = 0.7f)
    private val WHITE = MaterialSpec("white", Rgb(0.93f, 0.93f, 0.92f), roughness = 0.6f)
    private val METAL = MaterialSpec("metal", Rgb(0.55f, 0.57f, 0.60f), roughness = 0.45f)
    private val BLACK = MaterialSpec("black", Rgb(0.08f, 0.08f, 0.09f), roughness = 0.5f)
    private val SCREEN = MaterialSpec("screen", Rgb(0.05f, 0.06f, 0.08f), roughness = 0.2f, emissive = Rgb(0.25f, 0.40f, 0.85f))
    private val LEAF = MaterialSpec("leaf", Rgb(0.25f, 0.52f, 0.28f), roughness = 0.8f)
    private val POT = MaterialSpec("pot", Rgb(0.72f, 0.40f, 0.28f), roughness = 0.8f)
    private val CARDBOARD = MaterialSpec("cardboard", Rgb(0.74f, 0.57f, 0.38f), roughness = 0.9f)
    private val TABLE_GREEN = MaterialSpec("table_green", Rgb(0.12f, 0.40f, 0.30f), roughness = 0.6f)
    private val LAMP_SHADE = MaterialSpec("shade", Rgb(0.98f, 0.92f, 0.78f), roughness = 0.8f, emissive = Rgb(1f, 0.85f, 0.6f))
    private fun fabric(c: Rgb) = MaterialSpec("fabric", c, roughness = 0.9f)
    private val BOOKS = listOf(Rgb(0.65f, 0.2f, 0.2f), Rgb(0.2f, 0.35f, 0.6f), Rgb(0.85f, 0.7f, 0.3f), Rgb(0.25f, 0.5f, 0.35f))
        .mapIndexed { i, c -> MaterialSpec("book$i", c, roughness = 0.8f) }

    /** Geometry for a built-in item, or null if [id] isn't built in. */
    fun mesh(id: String): List<MeshPart>? {
        val b = MeshBuilder()
        val f = fabric(FABRICS[0])
        when (id) {
            "sofa" -> {
                b.box(f, -0.95f, 0.12f, -0.45f, 0.95f, 0.45f, 0.45f)           // seat
                b.box(f, -0.95f, 0.12f, -0.45f, 0.95f, 0.85f, -0.22f)          // back
                b.box(f, -1.0f, 0.12f, -0.45f, -0.8f, 0.62f, 0.45f)            // arms
                b.box(f, 0.8f, 0.12f, -0.45f, 1.0f, 0.62f, 0.45f)
                legs(b, DARK_WOOD, 0.92f, 0.4f, 0.12f)
            }
            "armchair" -> {
                b.box(f, -0.42f, 0.12f, -0.42f, 0.42f, 0.45f, 0.42f)
                b.box(f, -0.42f, 0.12f, -0.42f, 0.42f, 0.85f, -0.2f)
                b.box(f, -0.48f, 0.12f, -0.42f, -0.32f, 0.62f, 0.42f)
                b.box(f, 0.32f, 0.12f, -0.42f, 0.48f, 0.62f, 0.42f)
                legs(b, DARK_WOOD, 0.4f, 0.38f, 0.12f)
            }
            "tv" -> {
                b.box(DARK_WOOD, -0.72f, 0f, -0.22f, 0.72f, 0.45f, 0.22f)
                b.box(BLACK, -0.06f, 0.45f, -0.08f, 0.06f, 0.55f, 0.04f)
                b.box(BLACK, -0.62f, 0.55f, -0.06f, 0.62f, 1.15f, 0f)
                b.box(SCREEN, -0.59f, 0.58f, 0f, 0.59f, 1.12f, 0.005f)
            }
            "rug" -> b.box(f, -0.95f, 0f, -0.7f, 0.95f, 0.02f, 0.7f)
            "lamp" -> {
                b.prism(BLACK, 0f, 0f, 0f, 0.03f, 0.16f)
                b.box(BLACK, -0.015f, 0.03f, -0.015f, 0.015f, 1.3f, 0.015f)
                b.prism(LAMP_SHADE, 0f, 0f, 1.25f, 1.6f, 0.17f)
            }
            "plant" -> {
                b.prism(POT, 0f, 0f, 0f, 0.35f, 0.16f)
                b.box(LEAF, -0.18f, 0.35f, -0.18f, 0.18f, 0.8f, 0.18f)
                b.box(LEAF, -0.12f, 0.6f, -0.12f, 0.12f, 1.1f, 0.12f)
                b.box(LEAF, -0.22f, 0.45f, -0.08f, 0.22f, 0.7f, 0.08f)
            }
            "bed" -> {
                b.box(WOOD, -0.75f, 0f, -1f, 0.75f, 0.32f, 1f)
                b.box(WOOD, -0.75f, 0f, -1f, 0.75f, 0.95f, -0.92f)             // headboard (back)
                b.box(WHITE, -0.7f, 0.32f, -0.92f, 0.7f, 0.52f, 0.95f)          // mattress
                b.box(WHITE, -0.62f, 0.52f, -0.88f, -0.08f, 0.62f, -0.6f)       // pillows
                b.box(WHITE, 0.08f, 0.52f, -0.88f, 0.62f, 0.62f, -0.6f)
                b.box(f, -0.72f, 0.40f, -0.45f, 0.72f, 0.56f, 0.97f)            // blanket
            }
            "table" -> {
                b.box(WOOD, -0.72f, 0.70f, -0.46f, 0.72f, 0.76f, 0.46f)
                legs(b, WOOD, 0.66f, 0.40f, 0.70f, 0.04f)
            }
            "chair" -> {
                b.box(WOOD, -0.2f, 0.42f, -0.2f, 0.2f, 0.46f, 0.2f)
                b.box(WOOD, -0.2f, 0.46f, -0.2f, 0.2f, 0.9f, -0.16f)
                legs(b, WOOD, 0.17f, 0.17f, 0.42f, 0.025f)
            }
            "desk" -> {
                b.box(WHITE, -0.72f, 0.72f, -0.45f, 0.72f, 0.76f, 0.45f)
                b.box(METAL, -0.70f, 0f, -0.40f, -0.64f, 0.72f, 0.40f)
                b.box(METAL, 0.64f, 0f, -0.40f, 0.70f, 0.72f, 0.40f)
                b.box(BLACK, -0.05f, 0.76f, -0.32f, 0.05f, 0.86f, -0.24f)
                b.box(BLACK, -0.35f, 0.86f, -0.3f, 0.35f, 1.2f, -0.26f)
                b.box(SCREEN, -0.33f, 0.88f, -0.26f, 0.33f, 1.18f, -0.255f)
                b.box(BLACK, -0.22f, 0.76f, 0.0f, 0.22f, 0.78f, 0.14f)          // keyboard
            }
            "bookshelf" -> {
                b.box(DARK_WOOD, -0.48f, 0f, -0.22f, -0.45f, 1.8f, 0.22f)
                b.box(DARK_WOOD, 0.45f, 0f, -0.22f, 0.48f, 1.8f, 0.22f)
                b.box(DARK_WOOD, -0.48f, 0f, -0.22f, 0.48f, 1.8f, -0.19f)
                for (s in 0..4) {
                    val y = s * 0.44f
                    b.box(DARK_WOOD, -0.45f, y, -0.19f, 0.45f, y + 0.03f, 0.22f)
                    if (s < 4) {
                        var x = -0.43f; var k = s
                        while (x < 0.38f) {
                            val bw = 0.05f + (k % 3) * 0.02f
                            b.box(BOOKS[k % BOOKS.size], x, y + 0.03f, -0.16f, x + bw, y + 0.30f + (k % 2) * 0.06f, 0.15f)
                            x += bw + 0.01f; k++
                        }
                    }
                }
            }
            "workbench" -> {
                b.box(WOOD, -0.98f, 0.85f, -0.45f, 0.98f, 0.92f, 0.45f)
                legs(b, METAL, 0.92f, 0.40f, 0.85f, 0.035f)
                b.box(WOOD, -0.92f, 0.2f, -0.40f, 0.92f, 0.23f, 0.40f)          // lower shelf
                b.box(CARDBOARD, -0.98f, 0.92f, -0.48f, 0.98f, 1.6f, -0.45f)    // pegboard
                b.box(METAL, -0.6f, 1.15f, -0.45f, -0.5f, 1.35f, -0.42f)
                b.box(BLACK, -0.3f, 1.2f, -0.45f, -0.25f, 1.4f, -0.42f)
                b.box(METAL, 0.1f, 0.92f, -0.1f, 0.35f, 1.0f, 0.1f)             // vice
            }
            "shelving" -> {
                for (x in listOf(-0.47f, 0.44f)) for (z in listOf(-0.22f, 0.19f)) b.box(METAL, x, 0f, z, x + 0.03f, 1.8f, z + 0.03f)
                for (s in 0..3) {
                    val y = 0.1f + s * 0.55f
                    b.box(METAL, -0.47f, y, -0.22f, 0.47f, y + 0.03f, 0.22f)
                    if (s < 3) b.box(CARDBOARD, -0.35f + (s % 2) * 0.3f, y + 0.03f, -0.15f, 0.05f + (s % 2) * 0.3f, y + 0.33f, 0.15f)
                }
            }
            "boxes" -> {
                b.box(CARDBOARD, -0.22f, 0f, -0.22f, 0.22f, 0.4f, 0.22f)
                b.box(CARDBOARD, -0.17f, 0.4f, -0.15f, 0.15f, 0.72f, 0.17f)
            }
            "heater" -> {
                b.prism(WHITE, 0f, 0f, 0.05f, 1.5f, 0.22f, 14)
                b.box(METAL, -0.03f, 1.5f, -0.03f, 0.03f, 1.62f, 0.03f)
                b.prism(METAL, 0f, 0f, 0f, 0.05f, 0.2f, 14)
            }
            "washer" -> {
                b.box(WHITE, -0.24f, 0f, -0.24f, 0.24f, 0.85f, 0.24f)
                b.box(BLACK, -0.14f, 0.25f, 0.24f, 0.14f, 0.55f, 0.245f)
                b.box(METAL, -0.2f, 0.72f, 0.24f, 0.05f, 0.8f, 0.245f)
            }
            "fridge" -> {
                b.box(WHITE, -0.24f, 0f, -0.24f, 0.24f, 1.8f, 0.24f)
                b.box(METAL, 0.16f, 0.9f, 0.24f, 0.19f, 1.3f, 0.27f)
                b.box(METAL, 0.16f, 0.3f, 0.24f, 0.19f, 0.6f, 0.27f)
                b.box(BLACK, -0.24f, 0.74f, 0.24f, 0.24f, 0.76f, 0.245f)
            }
            "pingpong" -> {
                b.box(TABLE_GREEN, -1.37f, 0.72f, -0.76f, 1.37f, 0.76f, 0.76f)
                b.box(WHITE, -1.37f, 0.76f, -0.01f, 1.37f, 0.765f, 0.01f)
                b.box(WHITE, -0.02f, 0.76f, -0.8f, 0.02f, 0.9f, 0.8f)           // net
                legs(b, METAL, 1.25f, 0.65f, 0.72f, 0.04f)
            }
            "stairs" -> {
                val steps = 12; val rise = 2.4f / steps; val run = 3.0f / steps
                for (i in 0 until steps) {
                    val zFront = 1.5f - i * run
                    b.box(WOOD, -0.48f, 0f, zFront - run, 0.48f, (i + 1) * rise, zFront)
                }
            }
            else -> return null
        }
        return b.build()
    }

    /** Four square legs inside ±[hx], ±[hz]. */
    private fun legs(b: MeshBuilder, m: MaterialSpec, hx: Float, hz: Float, height: Float, size: Float = 0.05f) {
        for (sx in listOf(-1f, 1f)) for (sz in listOf(-1f, 1f)) {
            val x = sx * hx; val z = sz * hz
            b.box(m, x - size, 0f, z - size, x + size, height, z + size)
        }
    }
}
