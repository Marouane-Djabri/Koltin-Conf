# generate_samples

All sample models are generated from our own Kotlin code, so they are 100% ours (no licensing questions):

| File | Made by |
|---|---|
| `cottage.glb`, `mosque.glb`, `apartment.glb`, `cafe.glb` | `geometry/SampleBuildings.kt` |
| `armchair.glb`, `potted_plant.glb` | `model/BuiltinCatalog.kt` |

Run `generate_samples.ps1` (Windows) or `generate_samples.sh` to rewrite them into
`shared/src/commonMain/composeResources/files/samples/`. It runs `SampleFilesTest` with
`REGENERATE_SAMPLES=1`; the same test, run normally, fails if a committed file is stale and
checks every sample against our own `GlbValidator` (glTF 2.0 binary, no compression, Y-up, metres, < 1 MB).
