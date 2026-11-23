# Build the shared map + platform 3D world overlay (KMP, Android + Web)

Read this whole brief first. Then inspect the existing project before changing anything.

## Context
- This is my entry for the **Kotlin Multiplatform Contest**. **Deadline: December 7, 2026.** Judges clone the GitHub repo and run it from the README, so it must build and run easily.
- Targets: **Android** and **Web (Kotlin/JS)**. No iOS for now. Don't remove iOS code if it exists, but don't spend time on it.
- **This repo already has a working Filament 3D building renderer on Android.** Before you change anything: explore the project, summarize its current structure, Gradle setup, Kotlin/Compose/Filament versions, and how Filament is wired. Then propose a plan and wait for my OK.

## The product
A cute, real-world map. On top of it sits a **separate cute 3D/isometric world layer**: landmarks (mosques, churches, airports, schools, hospitals, stadiums) and user-built homes, drawn as cute 3D models. Users can **search for a place**, fly there, and **build their home** on the map. Later, tapping a home opens its interior (Filament room editor).

## The core architecture rule (most important)
The **map** and the **3D rendering** are two separate layers:
- **Map layer = shared KMP code** (`commonMain`): MapLibre Compose map, style, camera, search, data, game logic, projection math, hit-testing.
- **Rendering layer = platform-specific**: **Filament on Android**, **three.js on Web**. It draws on a **transparent overlay above the map** and knows nothing about tiles.
- The only thing they share is the camera. The map doesn't know about models, and the renderer doesn't know about tiles.

```kotlin
// commonMain
Box {
    MaplibreMap(...)                                   // shared
    WorldOverlay(frame = overlayFrame, modifier = Modifier.fillMaxSize())  // expect/actual
    Hud(...)                                           // shared Compose UI: search, buttons, sheets
}

data class LatLng(val lat: Double, val lng: Double)

data class MapCamera(val target: LatLng, val zoom: Double, val bearing: Double, val tilt: Double, val viewportPx: IntSize)

data class RenderEntity(
    val id: String,
    val position: LatLng,
    val kind: EntityKind,            // LANDMARK_MOSQUE, LANDMARK_CHURCH, LANDMARK_AIRPORT, LANDMARK_SCHOOL, LANDMARK_HOSPITAL, LANDMARK_STADIUM, HOME
    val style: BuildingStyle,        // colors, prefab, add-ons
    val headingDeg: Float = 0f,
    val sizeMeters: Float            // footprint size used for scaling
)

// What shared code hands the renderer every frame (already projected)
data class ScreenEntity(
    val id: String, val kind: EntityKind, val style: BuildingStyle,
    val screenX: Float, val screenY: Float,   // px, anchor = model base center
    val scale: Float,                         // px per meter at current zoom
    val rotationDeg: Float,                   // entity heading minus map bearing
    val depth: Float                          // for draw ordering
)
data class OverlayFrame(val camera: MapCamera, val entities: List<ScreenEntity>, val selectedId: String?)

@Composable expect fun WorldOverlay(frame: OverlayFrame, modifier: Modifier)
```

## Decisions (already made, don't revisit)
1. **Approach A: screen-anchored, orthographic/isometric rendering.**
   - Every frame, shared code projects each entity's LatLng to a screen pixel using the map's own projection (use MapLibre Compose's camera/projection API if it exposes one, otherwise implement Web Mercator in common code and verify it against the map).
   - Compute `scale` from meters-per-pixel at the current zoom and latitude: `metersPerPixel = 156543.03392 * cos(lat) / 2^zoom`, with MapLibre's 512px tiles taken into account. Verify against the map visually.
   - The renderer uses an **orthographic camera** with a fixed isometric-ish pitch that follows the map's tilt (clamped), and draws each model at its screen point with that scale and rotation.
   - Models must stay glued to the map while the user pans, zooms and rotates. That's the acceptance test.
2. **Tiles: OpenFreeMap** (free, no API key). Start from its "liberty" style and build our own **pastel style JSON** in resources: soft greens, cream roads, light blue water, fewer labels, low pastel `fill-extrusion` blocks for buildings. Add an **OSM attribution** that is always visible.
3. **Web = Kotlin/JS target** (not Wasm), because MapLibre Compose doesn't support Wasm yet. Check the latest MapLibre Compose version and its web setup docs. **Pin one version** for the whole contest.
4. **Placeholders for every model.** No real art yet. Build placeholders procedurally so I can swap in `.glb` files later:
   - Mosque: box + dome (sphere half) + thin cylinder minaret. Church: box + pyramid roof + small tower. Airport: wide flat box + control tower. School: L-shape boxes. Hospital: box + cross sign. Stadium: ring/oval. Home: box + gable roof.
   - Pastel colors per kind.
   - Put this behind a `ModelProvider` per platform: `placeholder(kind, style)` now, `loadGlb(path)` later. One flag switches between them.

## Features to build (in this order)
1. **Map screen (shared):** MapLibre Compose + pastel style. Pan, zoom, rotate, tilt. Start camera on a nice default city, with a "my location" button (Android only is fine).
2. **Projection + overlay plumbing:** shared projection code + `WorldOverlay` actuals:
   - **Android:** Filament rendering into a **transparent** `TextureView` (or a `SurfaceView` with zOrder on top and a transparent clear color) inside `AndroidView`, layered above the map. Render on a Choreographer frame callback so it updates in the same frame as the map camera. Reuse the existing Filament setup where possible.
   - **Web:** a **three.js canvas with `alpha: true`**, absolutely positioned above the map element, updated with `requestAnimationFrame`. Use Kotlin/JS interop with the npm three package (typed externals for the few APIs you need).
   - The overlay must **not take touches**. All gestures go to the map.
3. **Hit-testing (shared):** on map tap, find the nearest `ScreenEntity` within its projected bounds and select it. Show a shared bottom sheet with the entity's info. Highlight the selected entity in the renderer (bounce or outline).
4. **Landmarks:** load real places around the camera from the **Overpass API** (debounced, only above zoom ~14, cached per area). Tag mapping: `amenity=place_of_worship` + `religion=muslim` → mosque, `+ religion=christian` → church, `aeroway=aerodrome|terminal` → airport, `amenity=school|university` → school, `amenity=hospital` → hospital, `leisure=stadium` → stadium. Cap the number of entities on screen (e.g. 60, nearest first). Use a proper User-Agent and respect fair use.
   - Hide or flatten the map's own building extrusions under a landmark model (style filter or a GeoJSON mask) so the two don't overlap badly.
5. **Search (shared):** a search bar using **Photon** (komoot, OSM-based) with **Nominatim** as fallback. Debounce input, show results, and fly the camera to the chosen place with an animation. Set a User-Agent and respect both services' usage policies.
6. **Build home here:** a "Build here" button → tap a spot on the map → pick a home prefab (3–4 placeholder variants) → pick wall and roof color + 1–2 add-ons (tree, flag, fence) → place it. Live preview in the overlay during placement. Save as data:
   ```kotlin
   data class Home(val id: String, val ownerName: String, val position: LatLng, val prefab: String, val style: BuildingStyle, val createdAt: Long)
   ```
   Persist locally (works on Android + JS). Put it behind a `HomeRepository` interface so I can add a Supabase backend later.
7. **Tap a home → "Enter"** opens a placeholder interior screen (Android: reuse the existing Filament scene; Web: simple "coming soon" for now). The real interior editor is a later task, not this one.

## Quality bar
- Models never drift from their map position while panning/zooming/rotating. Test at different latitudes.
- 60 fps target on a mid-range Android phone with ~60 entities. Skip off-screen entities. Don't allocate per frame in the render loop.
- Keep `commonMain` free of platform code. Put all projection, hit-testing, tag mapping and repositories in shared code, with **unit tests in `commonTest`** for: projection (known lat/lng/zoom → expected pixels), meters-per-pixel, hit-testing, OSM tag → kind mapping, and search-result parsing.
- Handle errors gracefully: offline, Overpass timeout, no search results.

## README (contest requirement)
- What the app is, plus screenshots/GIF placeholders.
- How to run on **Android** and **Web** (exact Gradle tasks), and how to try the key features (search → fly → build a home → tap it).
- Architecture section with a diagram: shared map + logic vs platform renderers, and why the renderer is platform-specific.
- Credits: © OpenStreetMap contributors (ODbL), OpenFreeMap, MapLibre, Filament, three.js, Photon/Nominatim.
- Add an **MIT or Apache 2.0 LICENSE** file if there isn't one (contest rule).

## How to work
- **Step 0:** inspect the repo and report back (structure, versions, how Filament is set up), then propose a file/module plan. Wait for my OK.
- Then do one feature at a time, in the order above. After each one: make sure **both Android and JS build**, run the tests, commit with a clear message, and tell me briefly what to test manually on my phone/browser.
- Prefer simple, readable code. Judges will read it.
- If a library can't do something on one platform, pick a working fallback, keep going, and note it in the README. Don't block.
- No secrets in the repo.
