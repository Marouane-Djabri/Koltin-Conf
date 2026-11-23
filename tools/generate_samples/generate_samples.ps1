# Regenerates the bundled sample .glb files (shared/src/commonMain/composeResources/files/samples)
# from the Kotlin code in SampleBuildings / BuiltinCatalog, then validates them.
# Usage (Windows):  .\tools\generate_samples\generate_samples.ps1
$env:REGENERATE_SAMPLES = "1"
& "$PSScriptRoot\..\..\gradlew.bat" :shared:testAndroidHostTest --tests "dev.filamentiso.SampleFilesTest" --rerun
$env:REGENERATE_SAMPLES = $null
