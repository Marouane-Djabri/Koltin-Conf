package dev.filamentiso

/** sRGB color with components in 0..1. Renderers convert to linear as needed. */
data class Rgb(val r: Float, val g: Float, val b: Float)

/**
 * Everything a renderer needs to know about the iso scene. Pure data, no engine types.
 *
 * Defaults give a *true* isometric view: yaw 45°, pitch atan(1/√2) ≈ 35.264°.
 */
data class IsoSceneConfig(
    val yawDegrees: Float = 45f,
    val pitchDegrees: Float = ISO_PITCH_DEGREES,
    /** 1 = model fits the viewport (with [fitMargin]); 2 = twice as large on screen. */
    val zoom: Float = 1f,
    val fitMargin: Float = 0.06f,
    val backgroundColor: Rgb = Rgb(0.11f, 0.12f, 0.15f),
    /** Direction the sunlight travels (from the sun toward the scene), world space, Y up. */
    // Asymmetric on purpose: the two visible inner walls (facing +X and +Z) get clearly
    // different brightness, which is what reads as depth in an iso view.
    val sunDirection: Vec3 = Vec3(-0.75f, -1f, -0.2f),
    /** Lux. Filament's default camera exposure is tuned for ~100k lux daylight. */
    val sunIntensity: Float = 60_000f,
    /** Constant ambient (single SH band) intensity. */
    val ambientIntensity: Float = 14_000f,
    /** Mesh nodes (by glTF node name) to drop from the scene and from the camera fit. */
    val hiddenNodes: Set<String> = emptySet(),
    /** Materials (by glTF name) that exported without PBR data; rendered as matte dielectrics. */
    val matteMaterials: Set<String> = emptySet(),
) {
    companion object {
        /** atan(1/√2) in degrees: the pitch of a true isometric projection. */
        const val ISO_PITCH_DEGREES = 35.264389f
        const val MIN_ZOOM = 0.3f
        const val MAX_ZOOM = 8f
    }

    fun withZoom(z: Float) = copy(zoom = z.coerceIn(MIN_ZOOM, MAX_ZOOM))

    /** Keeps yaw in [0, 360) so it doesn't grow forever while dragging. */
    fun withYaw(y: Float) = copy(yawDegrees = ((y % 360f) + 360f) % 360f)
}

/** Fix-ups for the isoroom.glb asset, found by inspecting the file (see README). */
object IsoRoomFixups {
    val hiddenNodes = setOf("Cube.009", "Cube.010")
    val matteMaterials = setOf(
        "wall", "furniture", "mirrior",
        "Material.002", "Material.005", "Material.006", "Material.007", "Material.010",
    )
}
