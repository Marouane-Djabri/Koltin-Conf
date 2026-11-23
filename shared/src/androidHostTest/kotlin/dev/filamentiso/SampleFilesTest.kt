package dev.filamentiso

import dev.filamentiso.geometry.SampleFiles
import dev.filamentiso.glb.GlbCheck
import dev.filamentiso.glb.GlbLimits
import dev.filamentiso.glb.GlbValidator
import java.io.File
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Keeps the bundled sample .glb files in sync with the code that generates them.
 * Normal run: fails if a committed file differs. With REGENERATE_SAMPLES=1 (see tools/generate_samples):
 * writes them. Either way, every sample must pass our own validator.
 */
class SampleFilesTest {
    private val dir = File("src/commonMain/composeResources/files/samples")

    @Test
    fun samplesAreValidAndUpToDate() {
        val regenerate = System.getenv("REGENERATE_SAMPLES") == "1"
        if (regenerate) dir.mkdirs()
        for (s in SampleFiles.all) {
            val bytes = s.build()
            val limits = if (s.furniture) GlbLimits.Furniture else GlbLimits.Building
            assertIs<GlbCheck.Valid>(GlbValidator.validate(s.fileName, bytes, limits), s.fileName)
            assertTrue(bytes.size < 1_000_000, "${s.fileName} must stay under 1 MB")
            val f = File(dir, s.fileName)
            if (regenerate) {
                f.writeBytes(bytes)
                println("wrote ${f.path} (${bytes.size} bytes)")
            } else {
                assertTrue(f.isFile && f.readBytes().contentEquals(bytes), "${f.path} is missing or stale: run tools/generate_samples")
            }
        }
    }
}
