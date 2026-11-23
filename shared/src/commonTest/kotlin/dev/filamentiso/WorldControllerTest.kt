package dev.filamentiso

import dev.filamentiso.geometry.SampleBuildings
import dev.filamentiso.storage.BlobAssetStore
import dev.filamentiso.storage.BlobBuildingRepository
import dev.filamentiso.storage.InMemoryBlobStore
import dev.filamentiso.world.WorldController
import dev.filamentiso.world.geo.LatLng
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldControllerTest {
    private fun world(blobs: InMemoryBlobStore = InMemoryBlobStore()) =
        WorldController(BlobBuildingRepository(blobs), BlobAssetStore(blobs))

    @Test
    fun importStoresAssetAndSavesBuilding() = runTest {
        val blobs = InMemoryBlobStore()
        val w = world(blobs)
        w.startNewBuilding(LatLng(36.0, 3.0))
        w.importModel("mosque.glb", SampleBuildings.glb(SampleBuildings.Kind.MOSQUE))
        val asset = assertNotNull(w.placing?.asset)
        assertEquals("mosque", w.placing?.name)
        assertNotNull(w.resolvedModel(w.placing!!), "model is cached for the overlay")
        w.finishPlacing()

        // A fresh controller on the same storage sees the building and can load its model.
        val again = world(blobs)
        again.load()
        val b = again.buildings.single()
        assertEquals(asset, b.asset)
        assertNull(again.resolvedModel(b), "not in memory yet")
        again.loadMissing(listOf(b))
        assertNotNull(again.resolvedModel(b))
    }

    @Test
    fun badFileShowsMessageAndKeepsPlaceholder() = runTest {
        val w = world()
        w.startNewBuilding(LatLng(36.0, 3.0))
        w.importModel("house.gltf", "{}".encodeToByteArray())
        assertTrue(w.message!!.contains(".gltf"))
        assertNull(w.placing?.asset)
    }

    @Test
    fun cancellingDeletesTheUnusedImport() = runTest {
        val blobs = InMemoryBlobStore()
        val w = world(blobs)
        w.startNewBuilding(LatLng(36.0, 3.0))
        w.importModel("cafe.glb", SampleBuildings.glb(SampleBuildings.Kind.CAFE))
        assertEquals(1, blobs.keys("assets/").size)
        w.cancelPlacing()
        assertEquals(0, blobs.keys("assets/").size)
    }
}
