package dev.filamentiso.geometry

import dev.filamentiso.Rgb
import dev.filamentiso.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Cute low-poly buildings generated in code: map placeholders *and* the bundled sample .glb files
 * (written by `tools/generate_samples`). 100% ours, no licensing questions.
 *
 * Convention for every model: metres, Y up, origin at the centre of the footprint on the ground,
 * front door facing +Z.
 */
object SampleBuildings {
    enum class Kind(val id: String, val title: String) {
        COTTAGE("cottage", "Cottage house"),
        MOSQUE("mosque", "Small mosque"),
        APARTMENT("apartment", "Apartment block"),
        CAFE("cafe", "Café"),
    }

    fun mesh(kind: Kind): List<MeshPart> = when (kind) {
        Kind.COTTAGE -> cottage()
        Kind.MOSQUE -> mosque()
        Kind.APARTMENT -> apartment()
        Kind.CAFE -> cafe()
    }

    fun glb(kind: Kind): ByteArray = GlbWriter.write(mesh(kind), kind.id)

    // ------------------------------------------------------------------ palette (soft pastels)
    private fun mat(name: String, r: Int, g: Int, b: Int, rough: Float = 0.85f) =
        MaterialSpec(name, Rgb(r / 255f, g / 255f, b / 255f), roughness = rough)

    private val CREAM = mat("wall_cream", 250, 240, 222)
    private val WHITE = mat("wall_white", 246, 246, 243)
    private val PEACH = mat("wall_peach", 255, 214, 190)
    private val LAVENDER = mat("wall_lavender", 214, 204, 240)
    private val ROOF_PINK = mat("roof_pink", 242, 150, 160)
    private val MINT = mat("mint", 170, 225, 205)
    private val SKY = mat("glass", 175, 215, 245, rough = 0.3f)
    private val WOOD = mat("wood", 196, 150, 110)
    private val TRIM = mat("trim", 255, 252, 246)
    private val GOLD = mat("gold", 245, 205, 120, rough = 0.5f)
    private val STRIPE = mat("awning_red", 245, 130, 140)
    private val GRAY = mat("roof_gray", 190, 185, 200)

    /** Window on the +Z wall at z = [z] centred on x = [x]; with [facingZ] = false, on the +X wall at x = [z] centred on z = [x]. */
    private fun MeshBuilder.window(x: Float, y: Float, z: Float, w: Float, h: Float, facingZ: Boolean = true) {
        // Glass pane slightly in front of the wall plus a little sill.
        if (facingZ) {
            box(TRIM, x - w / 2 - 0.08f, y - 0.08f, z - 0.02f, x + w / 2 + 0.08f, y + h + 0.08f, z + 0.04f)
            box(SKY, x - w / 2, y, z, x + w / 2, y + h, z + 0.07f)
            box(TRIM, x - w / 2 - 0.12f, y - 0.14f, z, x + w / 2 + 0.12f, y - 0.06f, z + 0.18f)
        } else {
            box(TRIM, z - 0.02f, y - 0.08f, x - w / 2 - 0.08f, z + 0.04f, y + h + 0.08f, x + w / 2 + 0.08f)
            box(SKY, z, y, x - w / 2, z + 0.07f, y + h, x + w / 2)
        }
    }

    private fun cottage(): List<MeshPart> = MeshBuilder().apply {
        val hw = 4f; val hd = 3.2f; val wallH = 3f
        box(TRIM, -hw - 0.15f, 0f, -hd - 0.15f, hw + 0.15f, 0.25f, hd + 0.15f)
        box(CREAM, -hw, 0.25f, -hd, hw, wallH, hd)
        gableRoof(ROOF_PINK, -hw - 0.5f, -hd - 0.5f, hw + 0.5f, hd + 0.5f, wallH, wallH + 2.6f, ridgeAlongX = true, gable = CREAM)
        // Chimney
        box(ROOF_PINK, 1.8f, wallH + 0.6f, -1.8f, 2.6f, wallH + 3.0f, -1.0f, top = TRIM)
        // Door + small canopy
        box(WOOD, -0.6f, 0.25f, hd, 0.6f, 2.3f, hd + 0.08f)
        box(GOLD, 0.35f, 1.2f, hd + 0.08f, 0.45f, 1.3f, hd + 0.14f)
        gableRoof(ROOF_PINK, -1.0f, hd, 1.0f, hd + 0.9f, 2.5f, 3.0f, ridgeAlongX = false)
        // Windows
        window(-2.4f, 1.2f, hd, 1.3f, 1.1f)
        window(2.4f, 1.2f, hd, 1.3f, 1.1f)
        window(0f, 1.2f, hw, 1.2f, 1.1f, facingZ = false)
        // Flower boxes
        box(MINT, -3.1f, 0.9f, hd + 0.1f, -1.7f, 1.05f, hd + 0.35f)
        box(MINT, 1.7f, 0.9f, hd + 0.1f, 3.1f, 1.05f, hd + 0.35f)
    }.build()

    private fun mosque(): List<MeshPart> = MeshBuilder().apply {
        val h = 5.5f
        box(TRIM, -6.5f, 0f, -6.5f, 6.5f, 0.4f, 6.5f)
        box(WHITE, -5.5f, 0.4f, -5.5f, 5.5f, h, 5.5f)
        // Drum + dome
        prism(WHITE, 0f, 0f, h, h + 1.2f, 3.4f, sides = 16)
        dome(MINT, 0f, 0f, h + 1.2f, 3.4f, sides = 16, rings = 5, heightScale = 1.1f)
        prism(GOLD, 0f, 0f, h + 1.2f + 3.6f, h + 1.2f + 4.4f, 0.12f, sides = 6)
        dome(GOLD, 0f, 0f, h + 1.2f + 4.4f, 0.28f, sides = 8, rings = 2)
        // Corner mini domes
        for (sx in listOf(-1f, 1f)) for (sz in listOf(-1f, 1f)) {
            prism(WHITE, sx * 4.4f, sz * 4.4f, h, h + 0.4f, 0.8f, sides = 10)
            dome(MINT, sx * 4.4f, sz * 4.4f, h + 0.4f, 0.8f, sides = 10, rings = 3)
        }
        // Minaret
        val mx = 4.8f; val mz = 6.6f
        prism(WHITE, mx, mz, 0f, 13f, 0.85f, sides = 10)
        prism(MINT, mx, mz, 10.4f, 10.9f, 1.25f, sides = 10) // balcony
        prism(WHITE, mx, mz, 13f, 13.6f, 1.0f, sides = 10)
        dome(MINT, mx, mz, 13.6f, 1.0f, sides = 10, rings = 3, heightScale = 1.6f)
        prism(GOLD, mx, mz, 15.1f, 15.8f, 0.08f, sides = 6)
        // Arched entrance: door + pointed top
        box(WOOD, -1.0f, 0.4f, 5.5f, 1.0f, 3.0f, 5.6f)
        gableRoof(WOOD, -1.0f, 5.5f, 1.0f, 5.6f, 3.0f, 3.8f, ridgeAlongX = false)
        // Arched windows
        for (x in listOf(-3.6f, 3.6f)) {
            window(x, 1.8f, 5.5f, 1.0f, 1.8f)
            gableRoof(SKY, x - 0.5f, 5.5f, x + 0.5f, 5.57f, 3.6f, 4.1f, ridgeAlongX = false)
        }
        for (z in listOf(-2.5f, 2.5f)) window(z, 1.8f, 5.5f, 1.0f, 1.8f, facingZ = false)
    }.build()

    private fun apartment(): List<MeshPart> = MeshBuilder().apply {
        val hw = 6f; val hd = 5f; val floors = 5; val fh = 3f
        val top = floors * fh
        box(LAVENDER, -hw, 0f, -hd, hw, top, hd, top = GRAY)
        // Parapet
        box(TRIM, -hw - 0.1f, top, -hd - 0.1f, hw + 0.1f, top + 0.5f, -hd + 0.2f)
        box(TRIM, -hw - 0.1f, top, hd - 0.2f, hw + 0.1f, top + 0.5f, hd + 0.1f)
        box(TRIM, -hw - 0.1f, top, -hd, -hw + 0.2f, top + 0.5f, hd)
        box(TRIM, hw - 0.2f, top, -hd, hw + 0.1f, top + 0.5f, hd)
        // Rooftop water tank
        prism(SKY, 2.5f, -2f, top, top + 1.6f, 0.9f, sides = 10)
        // Floor bands + windows
        for (f in 0 until floors) {
            val y = f * fh
            if (f > 0) box(TRIM, -hw - 0.05f, y - 0.1f, -hd - 0.05f, hw + 0.05f, y + 0.1f, hd + 0.05f)
            for (i in 0 until 4) {
                val x = -4.5f + i * 3f
                if (f == 0 && (i == 1 || i == 2)) continue // entrance
                window(x, y + 0.9f, hd, 1.4f, 1.4f)
            }
            for (i in 0 until 3) window(-3f + i * 3f, y + 0.9f, hw, 1.4f, 1.4f, facingZ = false)
            // Little balconies on upper floors
            if (f > 0) box(MINT, -0.9f, y + 0.1f, hd, 0.9f, y + 0.25f, hd + 0.9f)
        }
        // Entrance + canopy
        box(SKY, -1.4f, 0f, hd, 1.4f, 2.6f, hd + 0.06f)
        box(PEACH, -2f, 2.8f, hd, 2f, 3.0f, hd + 1.4f)
    }.build()

    private fun cafe(): List<MeshPart> = MeshBuilder().apply {
        val hw = 4.5f; val hd = 3.5f; val h = 3.6f
        box(PEACH, -hw, 0f, -hd, hw, h, hd, top = CREAM)
        box(TRIM, -hw - 0.15f, h, -hd - 0.15f, hw + 0.15f, h + 0.35f, hd + 0.15f)
        // Shop window + door
        box(SKY, -3.8f, 0.6f, hd, -0.9f, 2.6f, hd + 0.06f)
        box(SKY, 1.8f, 0.6f, hd, 3.8f, 2.6f, hd + 0.06f)
        box(WOOD, -0.6f, 0f, hd, 1.2f, 2.5f, hd + 0.08f)
        // Striped awning: alternating sloped strips
        val stripes = 9
        val w = 2 * hw / stripes
        for (i in 0 until stripes) {
            val x0 = -hw + i * w; val x1 = x0 + w
            val m = if (i % 2 == 0) STRIPE else TRIM
            quad(m, Vec3(x0, 3.2f, hd), Vec3(x1, 3.2f, hd), Vec3(x1, 2.7f, hd + 1.4f), Vec3(x0, 2.7f, hd + 1.4f), Vec3(0f, 0.94f, 0.34f))
            box(m, x0, 2.5f, hd + 1.38f, x1, 2.72f, hd + 1.45f)
        }
        // Sign
        box(TRIM, -2.2f, 3.35f, hd + 0.05f, 2.2f, 4.2f, hd + 0.2f)
        box(STRIPE, -1.8f, 3.6f, hd + 0.2f, 1.8f, 3.95f, hd + 0.25f)
        // Tables on the terrace
        for (x in listOf(-3f, 3f)) {
            prism(TRIM, x, hd + 2.6f, 0f, 0.75f, 0.08f, sides = 6)
            prism(MINT, x, hd + 2.6f, 0.72f, 0.78f, 0.5f, sides = 12)
            for (a in 0 until 2) {
                val ang = PI * a + PI / 2
                prism(STRIPE, x + 0.8f * cos(ang).toFloat(), hd + 2.6f + 0.8f * sin(ang).toFloat(), 0f, 0.45f, 0.22f, sides = 8)
            }
        }
    }.build()
}

