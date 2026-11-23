package dev.filamentiso.world

import dev.filamentiso.geometry.SampleBuildings
import dev.filamentiso.storage.BlobStore
import dev.filamentiso.world.geo.Places
import dev.filamentiso.world.model.Building
import dev.filamentiso.world.model.LOCAL_USER
import dev.filamentiso.world.model.ModelTransform
import dev.filamentiso.world.overlay.OverlayProjector

/** A small demo street around the default map position, so the first launch isn't empty. */
object DemoContent {
    const val DEMO_HOME_ID = "demo_home"

    fun buildings(): List<Building> {
        val c = Places.Algiers
        fun at(east: Double, north: Double) = OverlayProjector.offset(c, east, north)
        return listOf(
            Building(DEMO_HOME_ID, LOCAL_USER, "Demo cottage", at(0.0, 0.0), placeholderKind = SampleBuildings.Kind.COTTAGE,
                transform = ModelTransform(rotationDeg = 15f)),
            Building("demo_mosque", LOCAL_USER, "Neighbourhood mosque", at(-38.0, 18.0), placeholderKind = SampleBuildings.Kind.MOSQUE),
            Building("demo_cafe", LOCAL_USER, "Corner café", at(30.0, -14.0), placeholderKind = SampleBuildings.Kind.CAFE,
                transform = ModelTransform(rotationDeg = -30f)),
            Building("demo_apartment", LOCAL_USER, "Apartment block", at(28.0, 30.0), placeholderKind = SampleBuildings.Kind.APARTMENT),
        )
    }

    /** True the first time it is called on this device, false afterwards. */
    fun firstRunFlag(blobs: BlobStore): suspend () -> Boolean = {
        val key = "meta/seeded_v1"
        if (blobs.get(key) != null) false else { blobs.put(key, byteArrayOf(1)); true }
    }
}
