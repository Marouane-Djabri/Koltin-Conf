#!/usr/bin/env sh
# Regenerates the bundled sample .glb files (shared/src/commonMain/composeResources/files/samples)
# from the Kotlin code in SampleBuildings / BuiltinCatalog, then validates them.
# Usage (macOS/Linux):  ./tools/generate_samples/generate_samples.sh
cd "$(dirname "$0")/../.." && REGENERATE_SAMPLES=1 ./gradlew :shared:testAndroidHostTest --tests "dev.filamentiso.SampleFilesTest" --rerun
