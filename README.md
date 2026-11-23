# FilamentIso World

A cute, real-world map with a 3D isometric world on top, built with **Kotlin Multiplatform** for
**Android** and the **Web**. Map, data, state, math, validation and editing logic are shared Kotlin;
only rendering, file picking and file storage are platform code. The 3D layer is rendered by
**Filament** on Android and **three.js** on the Web, from the same shared scene description.

Build a home on the map (or import your own `.glb`), tap it to fly into an isometric diorama, then
step inside and furnish it floor by floor.

> Kotlin Multiplatform Contest entry. Work in progress: see the feature list below.

<!-- Screenshots / GIF: map (Android + Web), build flow, iso view, interior -->
_Screenshots coming soon._

## Features

| | Status |
|---|---|
| Pastel real-world map (MapLibre Compose + OpenFreeMap), pan / zoom / rotate / tilt, my location | ✅ |
| 3D buildings glued to the map (Filament on Android, three.js on the Web) | ✅ |
| Build here: 4 cute built-in buildings, size slider, 15° rotation, move by dragging the map | ✅ |
| Import your own `.glb` (validated in shared code, auto-fitted, stored on the device) | ✅ |
| Tap a building → info sheet → **Enter** → animated zoom into an isometric view | ✅ |
| Interior editor: up to 5 floors, paint, walls / doors / windows, furniture, zones, undo | ✅ |
| Import a `.glb` as furniture | ✅ |
| Demo street with a furnished cottage on first launch, sample `.glb` files to try | ✅ |
| Real landmarks from OpenStreetMap (Overpass), search and fly | ⏳ |
| Avatars and real-time presence | ⏳ |

## Run it

Requirements: JDK 17+ (`JAVA_HOME`), Android SDK for the Android app. The Web app needs only the
JDK (Gradle downloads Node.js and the npm packages itself).

```powershell
# Web (opens http://localhost:8080)
.\gradlew.bat :webApp:jsBrowserDevelopmentRun
# Web, optimized static build in webApp\build\dist\js\productionExecutable
.\gradlew.bat :webApp:jsBrowserDistribution

# Android (phone connected with USB debugging)
.\gradlew.bat :androidApp:installDebug

# Shared unit tests (commonTest on the JVM)
.\gradlew.bat :shared:testAndroidHostTest
```

On macOS/Linux use `./gradlew` instead of `.\gradlew.bat`.

The app opens on a small demo street in Algiers. Drag to pan, pinch or scroll to zoom, two-finger
rotate / tilt (Ctrl or right-drag on the Web). **N** faces north, **◎** flies to your location,
**⌂** goes back to the demo street, and **My twins** opens the original basement editor.

## Using the world

### Build and import

1. Tap **+ Build here**. A building appears at the centre of the map; **drag the map** to move it.
2. Pick a built-in model (cottage, mosque, apartment block, café), or **Import .glb…**, or
   **Try a sample ▾** to import one of the bundled sample files through the same pipeline.
3. Adjust **Size** (25–300 %) and turn in **15°** steps, then **Done**. Everything is saved on the
   device (Android app storage, browser IndexedDB). No account, no network needed.

Zoomed out (below zoom 16) buildings show as round bubbles; zoomed in, the full 3D models load
(at most 15 at once, nearest to the centre first). Tap a building for its sheet: **Enter**, **Move**, **Delete**.

### Supported files and limits

| | Building | Furniture |
|---|---|---|
| Format | glTF 2.0 **binary** (`.glb`), self-contained | same |
| Max file size | 15 MB | 5 MB |
| Max triangles (estimated from accessors, per instance) | 50 000 | 15 000 |
| Max texture size (read from embedded PNG/JPEG headers) | 2048 px | 2048 px |
| Max materials | 8 | 8 |

Refused with a clear message: `.gltf` text files ("export as .glb"), files that aren't glTF,
glTF 1.0, damaged files (bad header, chunk lengths, JSON, buffer ranges, node cycles), external
buffers or images, **Draco / meshopt compression** ("please re-export without compression"), other
required extensions that both renderers can't handle, and running out of storage. Going offline
shows a notice: only the map tiles need the network.

Compression is refused on purpose: three.js would need hosted Draco/meshopt decoders, and the two
platforms could then behave differently. All limits are constants in `glb/GlbValidator.kt`.

The file is never modified. Its bounding box (from accessor min/max, with node transforms) is used
to centre it, put its base on the ground and scale its largest side to 20 m. Size, rotation and
position are stored next to it as data (`ModelTransform`).

### Isometric view and interior

- **Enter ›** on a building's sheet: the map camera flies to it while a pastel backdrop fades in,
  then the building appears on a ground disc in a true isometric view (800 ms, eased).
- **◀ Turn / Turn ▶** or a swipe orbits in 90° steps; pinch or **+ / −** zooms. Pinching far out,
  **‹ Map** or the Android back button plays the animation in reverse and puts the map camera back
  where it was.
- **Enter ›** again goes inside. An imported GLB is only a shell, so the interior is our own grid,
  sized from the shell's footprint (0.5 m tiles, 4–40 m). The shell is hidden while inside, on both
  platforms: Filament can't switch opaque materials to see-through at runtime.
- **Floors:** the stack on the left. **+** adds a floor (up to 5, 3 m apart) and **−** removes the
  top one. Lower floors are drawn underneath the current one.
- **Tools:**
  - **Select:** tap an item, then Rotate, Color, Delete, or give it a **zone** (Working, Relaxing,
    Eating, Sleeping, Meeting) for the avatars to come.
  - **Furniture:** pick an item, then tap or drag. The preview is green when it fits and red when
    it doesn't (outside a room, overlapping, or crossing a wall).
  - **Paint:** wood, tile, carpet, concrete or terrazzo; tap or drag.
  - **Wall:** tap a tile edge to add or remove a wall; drag for long walls.
  - **Door**, **Window**, **Room** and **Erase**.
  - Also **Undo / Redo**, a ⟳ 90° view turn, Plan view and low walls.
- **Furniture catalog:** couch, armchair, TV, rug, lamp, plant, bed, table, chair, desk, bookshelf,
  kitchen counter, coffee machine, fridge and more, each with a footprint and 4 rotations.
  **Import** a `.glb` (or the sample `armchair.glb` / `potted_plant.glb`) as furniture: choose its
  footprint first, and the model is fitted to it.

### Sample models

All sample models are generated from our own Kotlin code, so there are no licensing questions.
[`tools/generate_samples`](tools/generate_samples/README.md) writes them into
`shared/src/commonMain/composeResources/files/samples/`:

- `cottage.glb`, `mosque.glb`, `apartment.glb`, `cafe.glb`
- `armchair.glb`, `potted_plant.glb`

Each is 5–66 KB, Y-up, in metres, with no compression. A test fails if the committed files are stale
and runs every one through our validator. The same code draws the built-in buildings on the map.

## Architecture

```
                         commonMain (shared Kotlin, Compose)
 ┌──────────────────────────────────────────────────────────────────────────────┐
 │ MapLibre Compose map + HUD        WorldController: buildings, placement,     │
 │   │ camera, projection              import (GlbValidator, Normalization),    │
 │   ▼                                 ViewModeMachine (map ↔ iso transitions)   │
 │ OverlayProjector: pixel-space       │                                        │
 │ camera, per-building Mat4,          ├─► IsoStage: diorama + iso camera       │
 │ tap hit-testing                     └─► InteriorController: floors →         │
 │   │                                     EditorController (edits, undo, pick) │
 │   ▼                                                                          │
 │ SceneContent (glTF bytes + matrices) + IsoSceneConfig (camera, light, clear) │
 │ AssetStore / BuildingRepository on top of a BlobStore interface              │
 └──────────┬──────────────────────────────────────────────┬────────────────────┘
            │ expect: IsoSceneView, BlobStore, file picker  │
 androidMain ▼                                       jsMain ▼
 Filament + gltfio on a translucent              three.js + GLTFLoader, offscreen,
 TextureView (touches pass to the map)           frame drawn by Compose
 FileBlobStore (app files dir)                   IndexedDbBlobStore
 FileKit: system document picker                 FileKit: <input type="file">
```

Renderers know nothing about maps, buildings or editing. They draw a list of glTF models with the
matrices and camera that shared code computed. That's why both platforms show the same thing for
the same state, and why all the logic is unit-tested on the JVM.

### Platform notes and fallbacks

- **Web 3D is drawn offscreen.** Compose for Web draws everything, MapLibre included, into one
  canvas, and HTML elements can only sit *above* it, where they would cover the HUD. So three.js
  renders into a hidden canvas, and Compose draws its pixels like an image (at most 1.5 px per
  CSS px). This keeps the same layering as Android. The cost is a pixel read-back for each frame
  that changes.
- **Android 3D is a translucent `TextureView`** above the MapLibre surface. Compose shares pointer
  events between overlapping interop views, so when the overlay ignores touches, the map gets them.
- **The Android picker shows all files.** Most document providers don't know the
  `model/gltf-binary` MIME type and would hide every `.glb`. The validator rejects anything else.
- **The shell is hidden inside** instead of shown at 15 % opacity, the same on both platforms (see above).

## Versions

| | Version |
|---|---|
| Filament (`filament-android`, `gltfio-android`) | 1.81.0 |
| MapLibre Compose | 0.20.0 (Kotlin/JS on the Web: no Wasm artifact yet) |
| Kotlin | 2.4.21 |
| Compose Multiplatform | 1.12.2 |
| Android Gradle Plugin | 9.1.1 (see below) |
| Gradle | 9.9.2 |
| compileSdk / targetSdk / minSdk | 37 / 35 / 24 |

## Basement editor (digital twin, v1)

From the home screen, create a **New basement** (empty) or a **Sample basement** (4 furnished rooms).

| Tool | How |
|---|---|
| View | drag = rotate · pinch = zoom · two fingers = pan |
| Room | drag a rectangle on the grid; touching rooms share one wall |
| Wall | drag along grid lines |
| Door / Window | tap a wall (1 m wide); tap again to remove, or switch type |
| Furniture | pick an item (Rotate sets its orientation), then tap or drag; the preview is green when it fits, red when it doesn't |
| Select | tap furniture or a room → Rotate / Color / Delete; drag furniture to move it |
| Erase | tap furniture, a wall or a room |

Plus Undo/Redo, ⟳ 90° view rotation, Plan view (top-down), cutaway walls, and Recenter.
Every change is saved immediately (one JSON file per house in app storage).

### How it works

```
House (JSON) ─► ShellBuilder ─► GlbWriter ─► .glb bytes ─┐
                OverlayBuilder (grid, previews)          ├─► SceneContent ─► FilamentIsoRenderer
Catalog ─► built-in mesh or Blender .glb ────────────────┘   (list of models)   (diffs + reloads what changed)
```

- **Data model** (`model/House.kt`): grid of 0.5 m cells. Walls are a *set of unit edges*, so shared
  walls merge automatically; rooms are *sets of cells*, which later become indoor-tracking zones.
- **Edits** (`editor/Edits.kt`): pure `Level -> Level` functions; undo keeps old snapshots (`History`).
- **Geometry in common code**: walls and floors are generated as triangles, then written as an
  in-memory glTF by `GlbWriter` (pure Kotlin, output passes the official glTF validator). Filament
  loads it with the same gltfio pipeline as any Blender file, so no custom materials are needed.
- **Cutaway**: walls between the camera and a room are drawn 35 cm tall (`ShellBuilder.isCut`).
- **Picking**: with an orthographic camera every pixel is a parallel ray, so
  `IsoCamera.screenToPlane` maps a touch to a grid cell exactly. Pure math, unit-tested.
- **Renderer** (`androidMain`): knows nothing about houses; it renders a list of glTF models,
  reloading only models whose source changed and moving the rest with transforms.

### Adding your own Blender furniture

1. Model in metres. Make the footprint a multiple of 0.5 m, put the origin at the footprint
   centre on the floor, face the front toward +Y in Blender (+Z in glTF), and apply all transforms.
2. Use only Principled BSDF with plain colors or image textures. Name a material `fabric` (or
   anything else listed in `recolorable`) if users should be able to recolor it.
3. Export glTF Binary (.glb) with +Y up, then copy it to `androidApp/src/main/assets/furniture/`.
4. Add an entry to `androidApp/src/main/assets/furniture/catalog.json`:
   ```json
   [{ "id": "sofa", "name": "Sofa", "category": "Living", "footprintW": 4, "footprintD": 2,
      "height": 0.85, "file": "furniture/sofa.glb", "recolorable": ["fabric"],
      "colors": [{"r":0.33,"g":0.42,"b":0.58}, {"r":0.62,"g":0.30,"b":0.28}] }]
   ```
   An entry whose `id` matches a built-in item replaces that placeholder.

## Build and run

### From the terminal (Windows)

```powershell
.\gradlew.bat :shared:testAndroidHostTest     # commonTest (IsoCamera math) on the JVM
.\gradlew.bat :androidApp:assembleDebug       # APK in androidApp\build\outputs\apk\debug
adb devices                                   # phone must show as "device"
.\gradlew.bat :androidApp:installDebug
adb shell am start -n dev.filamentiso.app/.MainActivity
adb logcat -s FilamentIso:* Filament:*        # app + engine logs
```

To connect a phone: enable Developer options → **USB debugging** and accept the prompt, or use
**Wireless debugging** with `adb pair <ip>:<port>` and then `adb connect <ip>:<port>`.

### From IntelliJ IDEA

1. Open the `FilamentIso` folder and let Gradle sync.
2. In **Settings → Build, Execution, Deployment → Build Tools → Gradle**, set **Gradle JVM** to
   your full JDK (`JAVA_HOME`). IntelliJ's bundled JBR has no `jlink`, and AGP needs it.
3. Choose the **androidApp** run configuration, select your phone, and click Run.

### Build notes

- **AGP 9.1.1, not 9.5.2:** the Android plugin bundled with IntelliJ 2026.2 is the AGP 9.1
  generation, so it can't sync a newer AGP. If you only build from the terminal, you can raise
  `agp` in `gradle/libs.versions.toml`. The trade-off is that IntelliJ sync then breaks.
- **compileSdk 37:** the AndroidX libraries under Compose Multiplatform 1.12 require it.
  targetSdk stays at 35.
- **The configuration cache is off.** With JDK 27, AGP 9.1's `JdkImageTransform` (jlink) fails
  while the configuration cache is being stored. Without the cache it works.
- `gradle.properties` keeps memory low: Gradle 2 GB, Kotlin daemon 1 GB, no parallel builds.

## Project layout: what's shared and what's Android-only

```
shared/                      KMP module (com.android.kotlin.multiplatform.library)
  src/commonMain/            pure Kotlin + Compose. No Filament.
    IsoSceneConfig.kt        yaw / pitch / zoom / pan / background / sun / ambient
    IsoCamera.kt             iso camera: eye/target + ortho fit, screen↔floor picking, pan
    IsoSceneView.kt          `expect fun IsoSceneView(content, config, onGesture)`: the renderer seam
    scene/SceneContent.kt    engine-agnostic scene: glTF models + transforms, raw gestures
    model/                   House, Level, Edge, Room, FurnitureItem, Catalog, built-in furniture
    editor/                  pure edit functions, undo History, EditorController (tools → edits)
    geometry/                MeshBuilder, ShellBuilder, OverlayBuilder, GlbWriter
    geometry/SampleBuildings procedural cottage / mosque / apartment / café (+ SampleFiles)
    glb/                     GlbValidator (header, chunks, limits, extensions), Normalization
    storage/                 HouseStore; BlobStore, AssetStore, BuildingRepository (WorldStorage.kt)
    world/                   WorldController, ViewMode state machine, IsoStage, InteriorController,
                             DemoContent; model/ (Building, ModelAsset, ModelTransform),
                             overlay/ (OverlayProjector, ModelProvider), ui/ (map, panels, HUDs)
    App.kt, ui/              home screen, basement editor UI, isoroom demo
  src/commonTest/            77 tests: GLB validation, normalization, overlay math, view modes,
                             import flow, interior edits, collisions, floors, serialization, editor
  src/androidMain/           Android-only
    IsoSceneView.android.kt  `actual`: AndroidView + TextureView raw gestures + lifecycle observer
    FilamentIsoRenderer.kt   Engine/Renderer/Scene/View/Camera, UiHelper + DisplayHelper,
                             Choreographer loop, multi-model gltfio diffing, lights, teardown
    storage/FileBlobStore.kt app files dir; world/platform/ picker type, online state, location
    AndroidPlatform.kt       FileHouseStore, asset catalog loader
  src/jsMain/                Web-only: ThreeRenderer + IsoSceneView.js.kt (three.js 0.187),
                             IndexedDbBlobStore, picker type, online state, location
  src/androidHostTest/       SampleFilesTest (keeps the bundled samples in sync), GlbDumpTest
androidApp/                  thin com.android.application module: MainActivity + assets/isoroom.glb
webApp/                      ComposeViewport entry point; webpack.config.d/three.js (ESM resolve)
tools/                       pastel-style.mjs (map style), generate_samples/ (sample .glb files)
```

AGP 9 no longer allows `com.android.application` and `kotlin.multiplatform` in the same module,
which is why the app shell lives in its own module.

**commonMain** decides *what* to show: camera math, config, UI state and gestures expressed as
config changes. **androidMain** decides *how*: Filament objects, the surface and the frame loop.
Gestures go up from the TextureView as new `IsoSceneConfig` values. State is hoisted in common
code and flows back down.

### Rendering details

- **Camera:** `Camera.Projection.ORTHO`, from `IsoCamera.compute()`. The default yaw of 45° and
  pitch of 35.264° (atan 1/√2) make a true isometric view, where the three axes have equal
  on-screen length. The fit uses the vertical cylinder around the bounding box, so the framing
  stays the same while you rotate. Pinch divides the ortho bounds.
- **Lighting without cmgen/KTX:** one `SUN` directional light with shadows, plus an
  `IndirectLight` built from a single SH band (`irradiance(1, [1,1,1])`), which gives constant
  ambient light.
- **Lifecycle:** the Choreographer callback is removed on `ON_PAUSE` and posted again on
  `ON_RESUME`. `AndroidView.onRelease` destroys everything in reverse order: swap chain (via
  `UiHelper.detach`), asset, loaders, material provider, lights, renderer, view, scene, camera,
  engine. Tested on device: 4 rotations gave 4 clean destroy/create pairs, memory stayed flat,
  and CPU use drops to 0% in the background.

### Asset findings (isoroom.glb)

2.2 MB, about 101k triangles, 23 materials, no textures, animations, cameras or lights. Y-up, with
the room spanning roughly 11.7 × 6.5 × 11.7 units. The app fixes three problems at runtime and
leaves the file untouched:

- `Cube.009` and `Cube.010` stick out up to 6 units below the floor. They are **hidden**, and
  the camera fits only the visible renderables.
- 8 materials exported without PBR data, so they default to metallic = 1 and look dark under
  ambient light. They are **overridden** to matte (metallic 0, roughness 0.8). Their real
  colors would need a re-export from Blender.
- `Material.003` combines clearcoat and transmission. The ubershader keeps only the clearcoat
  (Filament logs a warning).

## How well does Filament fit KMP? An honest note

Filament is a C++ engine with first-party bindings for **Android (Java/JNI)**, **iOS/macOS
(C++/Objective-C)** and **web (filament.js / WASM)**. There is no Kotlin Multiplatform artifact, so
KMP can share everything *around* the renderer but not the renderer itself.

| Target | Status |
|---|---|
| **Android** | **Works now.** Official AARs on Maven Central. Everything in this repo. |
| **Desktop (JVM)** | Possible, not turnkey. Filament has Java bindings for desktop, but no published desktop JNI artifacts. You'd build `filament-java` + JNI from source for each OS, then host it in a Compose Desktop `SwingPanel`/AWT canvas. |
| **iOS** | Possible, extra work. Filament's iOS API is C++. Kotlin/Native cinterop can't call C++ directly, so you'd write a thin Objective-C (or C) wrapper around Engine/gltfio and cinterop that, then show it in a `UIKitView`. |
| **Web** | Use `filament.js` (WASM) from Kotlin/JS or Wasm through JS interop. It's a separate binding with its own API shape. |

The realistic way to use Filament with KMP: share the scene description, camera math, input
handling, state and UI in common code (what this repo does), and write a thin `actual` renderer
for each platform against that platform's native Filament binding. Expect the Android `actual`
to be easy and every other target to need native build work first.

## Credits

- Map data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright), available under the
  Open Database License (ODbL).
- Vector tiles and fonts: [OpenFreeMap](https://openfreemap.org) (OpenMapTiles schema). The pastel style
  (`tools/pastel-style.mjs`) uses its "liberty" style as a reference.
- Map rendering: [MapLibre](https://maplibre.org) through [MapLibre Compose](https://github.com/maplibre/maplibre-compose).
- 3D rendering: [Filament](https://github.com/google/filament) (Android) and [three.js](https://threejs.org) (Web).
- File picking: [FileKit](https://github.com/vinceglb/FileKit) (Android + Web).

## License

[Apache License 2.0](LICENSE).
