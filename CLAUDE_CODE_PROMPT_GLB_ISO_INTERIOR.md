# Next step: GLB import, tap-to-isometric zoom, and interior editor (KMP, Android + Web)

Read this whole brief first. Then inspect the current code before changing anything.

## Context
- This is my **Kotlin Multiplatform Contest** entry. **Deadline: December 7, 2026.** Judges clone the repo and run it from the README.
- Targets: **Android** and **Web (Kotlin/JS)**. No iOS for now.
- What already exists (from the previous step, verify it): a shared MapLibre Compose map (OpenFreeMap tiles, pastel style), and a **transparent platform overlay** that draws 3D models anchored to map positions: **Filament on Android**, **three.js on Web**. Shared code projects entities to screen positions (`OverlayFrame` / `ScreenEntity` / `WorldOverlay` expect/actual), plus hit-testing, landmarks from Overpass, search, and "Build home here" with placeholder models.
- **Keep the architecture rule:** map, data, state, math, validation and logic in `commonMain`. Only rendering, file picking and file I/O are platform-specific.

**Step 0:** inspect the repo, summarize what exists and how the overlay works today, then propose a plan for this step. Wait for my OK.

## Goal of this step
1. Users can **import their own `.glb` model** for a building and place it on the map.
2. **Tapping a building zooms in and switches to an isometric view** of that building (map fades out).
3. In the isometric view, users can **enter the building and customize the interior**: floors, walls and furniture.

## 1. GLB import
**Picking the file (expect/actual):**
- Android: system document picker (`ActivityResultContracts.OpenDocument`), filtering for `.glb` / `model/gltf-binary` with a fallback to all files.
- Web: `<input type="file" accept=".glb">`, read as an ArrayBuffer.
- If a KMP file-picker library (e.g. FileKit) cleanly supports Android + JS at our versions, use it instead of hand-writing both. Check first.
- **Accept `.glb` only** (self-contained). Reject `.gltf` with a clear message.

**Validation, shared code, before handing bytes to any renderer:**
- Parse the GLB header: magic `glTF`, version `2`, valid chunk lengths. Parse the JSON chunk.
- Limits (constants, easy to tweak): max **15 MB** file, max **50k triangles** (estimate from accessor counts), max **2048 px** textures if detectable, max **8 materials**.
- Detect `extensionsRequired`: if it lists `KHR_draco_mesh_compression` or `EXT_meshopt_compression`, either support it on both platforms or reject with "Please re-export without compression". Decide based on what Filament + three.js setups can actually do, and document the choice.
- Return a typed result: `Valid(info)` or `Invalid(reason)` with user-friendly messages. Unit test with small hand-built GLB byte arrays.

**Normalization, stored as data, never by modifying the file:**
- After loading, compute the model's bounding box (renderer reports it back to shared code, or compute from accessor min/max in shared code, which is preferred).
- Default transform: center X/Z, put the base on the ground (minY → 0), auto-scale so the largest horizontal side fits the target footprint (default ~20 m for a home).
- Placement UI: **scale** slider, **rotate** (snap to 15°), **nudge/move** by dragging on the map, live preview in the overlay.
```kotlin
data class ModelAsset(val id: String, val ownerId: String, val fileName: String, val sizeBytes: Long,
                      val triangleCount: Int, val bboxMin: Vec3, val bboxMax: Vec3, val storagePath: String)
data class ModelTransform(val scale: Float, val rotationDeg: Float, val offsetMeters: Vec2)
data class Building(val id: String, val ownerId: String, val position: LatLng, val asset: ModelAsset?,
                    val placeholderKind: EntityKind?, val transform: ModelTransform,
                    val visibility: Visibility /* PRIVATE, FRIENDS, PUBLIC */, val floors: List<Floor>)
```

**Storage (local only, no backend in this step):**
- Put everything behind interfaces: `AssetStore` (save/load bytes) and `BuildingRepository`, so a backend can be added later without touching the rest.
- Implement **local** versions only: Android: app files dir; Web: IndexedDB (or another browser storage that can hold a few MB of binary data).
- **Do not add Supabase or any other backend.** No accounts, no network sync. Everything is saved on the device.

**Rendering imported models:**
- Android: Filament `gltfio` (AssetLoader + ResourceLoader), loading from bytes. Web: three.js `GLTFLoader.parse` from the ArrayBuffer.
- Same `ModelProvider` abstraction as placeholders: `placeholder(kind, style)` or `fromAsset(assetId)`.
- Free GPU resources when entities leave the screen for a while. Don't leak.

**Performance on the map:**
- Below a zoom threshold (e.g. 16), custom buildings render as a **round icon bubble or simple block**, not the full GLB.
- Load full models only when zoomed in, max ~15 custom models at once (nearest first).

## 2. Tap building → zoom → isometric view
Implement a **shared state machine**:
```kotlin
sealed interface ViewMode {
  data object Map : ViewMode
  data class TransitionToIso(val buildingId: String, val progress: Float) : ViewMode
  data class Iso(val buildingId: String, val sub: IsoSub /* EXTERIOR, INTERIOR */) : ViewMode
  data class TransitionToMap(val buildingId: String, val progress: Float) : ViewMode
}
```
- **Map → Iso (~800 ms, eased):** animate the map camera to center on the building and zoom in, then fade the map out while a soft pastel background (gentle gradient + ground disc + soft shadow) fades in. The renderer switches from **map-anchored mode** to its own **isometric scene** centered on the building.
- **Iso scene:** orthographic camera at a classic isometric angle. User can **orbit in 90° steps** (buttons + swipe) and pinch to zoom within limits. Soft lighting, a subtle shadow under the model.
- **Back** (button / system back / pinch out past a threshold) reverses the transition and returns the camera to where it was.
- Both platforms must behave identically, driven by the shared state. The renderer just reads the current mode + progress.

## 3. Interior editor
An imported GLB is only a **shell**: there are no real rooms in it. So:
- **"Enter" button** (Iso EXTERIOR → INTERIOR): fade the shell to ~15% opacity (or hide it if transparency is a problem on one platform; pick one and document it), and show an **interior floor grid** sized from the model's bounding box footprint (e.g. 1 tile = 1 m, clamp to sane limits).
- **Floors:** floor selector (add/remove floors, max 5). Each floor has its own grid. Floor height default 3 m.
- **Editing tools (shared logic, platform rendering):**
  - Paint floor tiles (4–6 pastel materials: wood, tile, carpet, concrete).
  - Walls on tile edges (tap edge to toggle), with door and window variants.
  - **Furniture catalog** (bottom sheet): placeholder procedural pieces built from boxes, at least: desk, chair, couch, bed, table, kitchen counter, fridge, coffee machine, plant, lamp, bookshelf, TV. Each has a footprint (e.g. 2×1 tiles) and 4 rotations.
  - Users can also **import a `.glb` as furniture** (same pipeline, smaller limits: 5 MB, 15k triangles; auto-fit to the chosen footprint).
  - Drag to place, tap to select, rotate button, delete button. Green/red ghost preview for valid/invalid placement (overlap and out-of-bounds checks in shared code).
- **Zones:** every furniture item has an optional `status` (WORKING, RELAXING, EATING, SLEEPING, MEETING). This prepares the next step (avatars + real-time presence).
```kotlin
data class Floor(val index: Int, val width: Int, val depth: Int, val tiles: Map<TilePos, FloorMaterial>,
                 val walls: Set<WallEdge>, val items: List<PlacedItem>)
data class PlacedItem(val id: String, val catalogId: String?, val assetId: String?, val x: Int, val y: Int,
                      val rotation: Int /* 0..3 */, val status: Status?)
```
- Save the interior with the building (local storage). Undo for the last ~20 actions is a nice-to-have.

## 4. Sample models (I have no models of my own)
- **Generate all sample `.glb` files yourself** with a small script committed to the repo (e.g. `tools/generate_samples`), so they're 100% ours with no licensing questions. Make them cute: soft pastel colors, rounded-looking low-poly shapes.
- Buildings (at least 4): **cottage house**, **small mosque** (box + dome + minaret), **apartment block**, **café/shop** with an awning. Furniture (at least 2): **armchair**, **potted plant**.
- Each file under 1 MB, valid glTF 2.0 binary, no compression extensions, Y-up, 1 unit = 1 meter. Run them through our own validator in a test.
- Bundle them as app resources on both platforms.
- Add a **"Try a sample model"** button in the import flow so judges don't need their own files.
- Seed one demo building with a furnished interior near the default map location.

## Quality bar
- Unit tests in `commonTest` for: GLB header/JSON parsing + validation limits, bounding box + normalization math, the ViewMode state machine transitions, grid placement collisions/bounds, and serialization of `Building` / `Floor`.
- Both **Android and JS build** after every feature. 60 fps target in isometric view on a mid-range phone.
- Clear error messages for: file too big, wrong format, compression unsupported, corrupt file, out of storage, offline.
- Update the README: how to import a model, size limits, supported formats, how to enter and edit the interior, sample files, and the architecture diagram (shared state/logic vs platform renderers).

## How to work
- Step 0 first (inspect + plan, wait for my OK).
- Then in order: GLB validation (shared + tests) → import + placement + storage → tap-to-iso transition → interior editor → demo content + README.
- After each one: build both targets, run tests, commit with a clear message, and tell me exactly what to test on my phone and in the browser.
- Prefer simple, readable code. Judges will read it.
- If a library or platform can't do something, pick a working fallback, note it in the README, and keep going.
- No secrets committed (there shouldn't be any in this step).
