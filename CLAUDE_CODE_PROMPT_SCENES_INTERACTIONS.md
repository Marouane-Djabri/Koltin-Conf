# Next step: buildable scenes, interactive objects, animated avatar, and the toon "ink" style (KMP, Android + Web)

Read this whole brief first. Then inspect the current code before changing anything.

## Git
- **Work on a new branch.** Before any change: make sure the working tree is clean (if not, stop and tell me), then create and switch to `feature/scenes-interactions` from the current branch.
- Commit after each milestone with a clear message. **Do not merge, rebase or push** unless I ask.

## Context
- This is my **Kotlin Multiplatform Contest** entry. **Deadline: December 7, 2026.** Judges clone the repo and run it from the README.
- Targets: **Android** and **Web (Kotlin/JS)**. No iOS for now.
- What already exists (verify it): a shared MapLibre Compose map with a pastel style, a **transparent platform overlay** drawing 3D models anchored to map positions (**Filament on Android**, **three.js on Web**), shared projection + hit-testing, landmarks, search, "build home here", **GLB import** with validation/normalization, a **tap-to-isometric** transition, and an **interior grid editor**. Storage is **local only** (no backend).
- **Architecture rule (keep it):** data, state, editor logic, pathfinding, validation and math live in `commonMain`. Only rendering, file picking and file I/O are platform-specific.

**Step 0:** inspect the repo, summarize what exists (especially the interior editor, the isometric scene, the renderer abstraction, and how models/materials are loaded on each platform), then propose a plan for this step. **Wait for my OK.**

## The vision for this step
Users rebuild the real places they spend time in, **indoors and outdoors**, and their avatar lives in those places. Example: a user who fishes at a lake claims the area around their spot, builds the shore, a dock and a bench, places a **fishing spot** on the dock, and their avatar walks there, sits down and fishes. Users don't write code: they attach **interactions** from a library to any object.

## 1. One "Scene" model for indoors and outdoors
Unify the interior editor into a general scene editor. A building interior and an outdoor plot are the same thing.
```kotlin
data class Scene(
  val id: String, val ownerId: String,
  val kind: SceneKind,                   // OUTDOOR_PLOT, BUILDING_FLOOR
  val anchor: LatLng,                    // where it sits on the map
  val headingDeg: Float,
  val width: Int, val depth: Int,        // tiles, 1 tile = 1 m
  val tiles: Map<TilePos, Ground>,       // GRASS, SAND, WATER, PATH, WOOD, TILE, CARPET…
  val walls: Set<WallEdge>,              // used mostly indoors
  val objects: List<PlacedObject>,
  val spots: List<InteractionSpot>
)
data class PlacedObject(val id: String, val catalogId: String?, val assetId: String?,
                        val x: Float, val y: Float, val rotationDeg: Float, val scale: Float,
                        val footprint: IntSize, val blocksWalking: Boolean)
```
- Every object and scene has an `ownerId` (a local user id for now) so going online later doesn't need a rewrite.
- Migrate the existing interior data to this model (keep old saves loading if any exist; otherwise just replace).

## 2. Outdoor plots
- On the map: **"Claim a plot here"**: tap a spot, choose a size (20×20, 40×40, 60×60 m), confirm. The plot shows on the map as a soft outlined area with a small icon.
- Tap the plot → the existing **zoom-to-isometric** transition → the **scene editor** for that plot.
- **Ground painting:** grass, sand, water, path, (indoors: wood, tile, carpet). Brush sizes 1 and 3. Water tiles are not walkable.
- **Object catalog** (bottom sheet, categories: Nature, Seating, Camping, Water, Home, Decor) using **sample models** plus the existing GLB import. Placement: drag, rotate (15° snap), scale, delete, green/red ghost for valid/invalid.

## 3. Interactions ("smart objects")
- Tap any placed object (including imported GLBs) → **"Add interaction"** → pick an action → a marker appears; drag it to where the avatar should be and rotate it to set the facing direction.
```kotlin
data class InteractionSpot(val id: String, val objectId: String?,   // null = free spot on the ground
                           val x: Float, val y: Float, val facingDeg: Float, val actionId: String)
data class ActionDef(val id: String, val label: String, val animation: String,
                     val prop: String?, val status: String, val pose: Pose /* STAND, SIT, LIE */)
```
- **Action library for now:** `SIT` ("🪑 Relaxing"), `FISH` ("🎣 Fishing", prop: fishing rod, pose SIT), `SLEEP` ("😴 Sleeping", pose LIE), `WORK` ("💻 Working"), `EAT` ("🍽️ Eating"). Keep it data-driven so adding actions is one entry.
- Some catalog objects come with default spots (bench → SIT, dock → FISH at its edge, bed → SLEEP, desk → WORK).
- In play mode, tapping a spot shows a button: "Fish here", "Sit here"…

## 4. The avatar
- **One cute avatar**, generated as a **sample `.glb` by a script in the repo** (like the other sample models): a chubby, round, cat-like (or bean-like) character with big head, small body, short limbs. Keep it original, don't copy any existing game's character.
- It must be **rigged with a simple skeleton** and include these **animations**: `idle`, `walk`, `sit_idle`, `fish_cast` (short), `fish_wait` (loop), `sleep`, `work` (typing-ish), `eat`. Simple keyframed bone rotations are fine; cute timing matters more than detail.
- Also generate a **fishing rod** prop `.glb` that attaches to the avatar's right hand bone.
- 4–6 **color variants** via material tint (not separate files).
- Playback: **Filament `gltfio` Animator** on Android, **three.js `AnimationMixer`** on Web, with short cross-fades between animations. Props attach to a named bone on both platforms.
- If generating a skinned/animated GLB by script turns out too hard on both renderers, fall back to a **rigid-parts avatar** (separate head/body/limb nodes animated by node transforms in code) and document the choice. The rest of the system must not care which one is used.

## 5. Movement and pathfinding (shared code)
- Build a **walkability grid** per scene from tiles + objects (`blocksWalking`) + walls.
- **A\*** from the avatar's position to the target spot (8-directional, no corner cutting), then path smoothing.
- Avatar movement: constant walk speed, turns smoothly to face the direction of travel, plays `walk`; on arrival turns to the spot's `facingDeg`, plays the action (e.g. `fish_cast` then `fish_wait`), attaches the prop, and sets the status bubble above the head.
- Tap ground in play mode → walk there. Tap a spot → walk there and do the action. Unreachable → friendly message.
- **Edit mode vs Play mode** toggle in the scene view.
- Unit tests in `commonTest` for: walkability grid building, A* (including unreachable cases), path smoothing, and spot/action lookup.

## 6. The look: toon + ink style (apply to everything, including imported GLBs)
Reference: a cute isometric game with flat two-tone shading, bold dark ink outlines, pastel greens/creams, soft shadows.
- **Toon shading:** 2–3 flat light bands (lit / shadow / optional soft highlight), soft warm light, cool shadows. Applied as a material override to every model, so user-imported GLBs match the style automatically (keep their base colors/textures, replace the lighting).
  - Android: a custom Filament material (compiled with `matc` as part of the build or committed as a prebuilt `.filamat` with its source `.mat` next to it).
  - Web: a custom three.js `ShaderMaterial` (or `MeshToonMaterial` with a gradient map if that's enough).
- **Ink outlines:** **inverted hull** method on both platforms (render a slightly inflated copy with front-face culling in a dark ink color). Outline width as one setting. If a model's normals are bad, fall back to no outline for that model rather than glitching.
- **Ground & scene:** soft pastel ground tiles with subtle variation, a soft blob shadow under the avatar and objects, a warm light from one fixed isometric angle.
- **Settings toggles** (small debug panel is fine): outline on/off + thickness, toon on/off, and a "pixel" mode that renders at low resolution and upscales (only if cheap).
- Target: 60 fps on a mid-range Android phone in a 60×60 scene with ~80 objects. Share materials, avoid per-frame allocations.

## 7. Sample content (I have no models of my own)
- **Generate all sample `.glb` files with scripts committed to the repo** (e.g. `tools/generate_samples`), so they're 100% ours: cute, low-poly, pastel, Y-up, 1 unit = 1 m, valid glTF 2.0 binary, no compression extensions, each under 1 MB, all passing our own validator in a test.
- Needed now: **avatar (rigged + animations)**, **fishing rod**, **dock**, **bench**, **round tree**, **bush**, **rock**, **tent**, **campfire**, **lamp post**, **bed**, **desk**, **chair**.
- **Seed demo:** a ready-made **"Lakeside spot"** plot near the default map location: water, sand shore, a dock with a FISH spot, a bench with a SIT spot, trees, a tent with a SLEEP spot. Opening it and tapping "Fish here" must show the avatar walking to the dock, sitting and fishing. **This is the demo moment, make it delightful.**

## Order of work
1. Step 0 (inspect + plan, wait for OK) and create the branch.
2. Scene model + migration of the interior editor + outdoor plots + ground painting.
3. Object catalog + placement on outdoor plots (reuse GLB import).
4. Sample models script (static objects first).
5. Avatar + animations + prop attachment on both platforms.
6. Walkability + A* + play mode movement.
7. Interaction spots + action library + status bubble.
8. Toon + ink style pass.
9. Seed "Lakeside spot" demo + README update.

After each one: build **both Android and JS**, run tests, commit on the branch, and tell me exactly what to test on my phone and in the browser. If time gets tight, protect 2, 5, 6, 7 and 9; the style pass (8) can be simplified to outlines + basic toon only.

## Rules
- Prefer simple, readable code. Judges will read it.
- If a library or platform can't do something, pick a working fallback, document it in the README, and keep going. Don't block.
- No backend in this step. Keep everything behind repository interfaces with local implementations.
- Don't break existing features (map, overlay, search, GLB import, tap-to-isometric). Re-test them before the final commit.
- README: add sections for plots, the scene editor, interactions and actions, the avatar, the art style, and the "Lakeside spot" demo walkthrough, plus credits/licensing (all sample models generated by our own scripts).
