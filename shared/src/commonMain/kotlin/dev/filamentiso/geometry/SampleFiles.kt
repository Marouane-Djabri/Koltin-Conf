package dev.filamentiso.geometry

import dev.filamentiso.model.BuiltinCatalog

/**
 * The sample .glb files bundled with the app (composeResources/files/samples/), generated from
 * our own code by `tools/generate_samples`. Judges can try the import flow without their own files.
 */
object SampleFiles {
    class Sample(val fileName: String, val title: String, val furniture: Boolean, val build: () -> ByteArray)

    val all: List<Sample> = SampleBuildings.Kind.entries.map { k ->
        Sample("${k.id}.glb", k.title, furniture = false) { SampleBuildings.glb(k) }
    } + listOf(
        Sample("armchair.glb", "Armchair", furniture = true) { GlbWriter.write(BuiltinCatalog.mesh("armchair")!!, "armchair") },
        Sample("potted_plant.glb", "Potted plant", furniture = true) { GlbWriter.write(BuiltinCatalog.mesh("plant")!!, "potted_plant") },
    )

    val buildings get() = all.filter { !it.furniture }
    val furniture get() = all.filter { it.furniture }

    /** Path inside compose resources (Res.readBytes). */
    fun resourcePath(fileName: String) = "files/samples/$fileName"
}
