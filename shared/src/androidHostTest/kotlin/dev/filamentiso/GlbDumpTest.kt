package dev.filamentiso

import dev.filamentiso.geometry.GlbWriter
import dev.filamentiso.geometry.OverlayBuilder
import dev.filamentiso.geometry.OverlayState
import dev.filamentiso.geometry.ShellBuilder
import dev.filamentiso.model.BuiltinCatalog
import dev.filamentiso.model.Catalog
import dev.filamentiso.storage.Templates
import java.io.File
import kotlin.test.Test

/** Writes generated .glb files to build/glb-dump so they can be checked with the glTF validator. */
class GlbDumpTest {
    @Test
    fun dump() {
        val out = File("build/glb-dump").apply { mkdirs() }
        val level = Templates.sampleBasement("Sample", Catalog(BuiltinCatalog.items)).level
        File(out, "shell.glb").writeBytes(GlbWriter.write(ShellBuilder.build(level, ShellBuilder.Options(true, 0.7f, 0.7f))))
        File(out, "overlay.glb").writeBytes(GlbWriter.write(OverlayBuilder.build(level, OverlayState())))
        for (item in BuiltinCatalog.items) File(out, "${item.id}.glb").writeBytes(GlbWriter.write(BuiltinCatalog.mesh(item.id)!!, item.id))
    }
}
