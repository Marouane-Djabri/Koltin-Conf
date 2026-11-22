# FilamentIso

A small Kotlin Multiplatform + Compose Multiplatform test of how [Google Filament](https://github.com/google/filament)
fits into KMP. It renders `isoroom.glb` in a true isometric, orthographic view on Android,
with drag-to-rotate and pinch-to-zoom.

| | Version |
|---|---|
| Filament (`filament-android`, `gltfio-android`) | 1.81.0 |
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
    storage/                 HouseStore interface, JSON, templates
    App.kt, ui/              home screen, editor UI, isoroom demo
  src/commonTest/            31 tests: camera, picking, edits, undo, placement rules, cutaway, GLB
  src/androidMain/           Android-only
    IsoSceneView.android.kt  `actual`: AndroidView + SurfaceView raw gestures + lifecycle observer
    FilamentIsoRenderer.kt   Engine/Renderer/Scene/View/Camera, UiHelper + DisplayHelper,
                             Choreographer loop, multi-model gltfio diffing, lights, teardown
    AndroidPlatform.kt       FileHouseStore, asset catalog loader
androidApp/                  thin com.android.application module: MainActivity + assets/isoroom.glb
```

AGP 9 no longer allows `com.android.application` and `kotlin.multiplatform` in the same module,
which is why the app shell lives in its own module.

**commonMain** decides *what* to show: camera math, config, UI state and gestures expressed as
config changes. **androidMain** decides *how*: Filament objects, the surface and the frame loop.
Gestures go up from the SurfaceView as new `IsoSceneConfig` values. State is hoisted in common
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
