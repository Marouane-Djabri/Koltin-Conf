package dev.filamentiso

import dev.filamentiso.scene.ModelSource
import dev.filamentiso.scene.SceneContent
import dev.filamentiso.scene.SceneModel
import kotlinx.browser.document
import kotlin.math.PI

private val THREE: dynamic = js("require('three')")

/**
 * The Web twin of FilamentIsoRenderer: keeps a three.js scene in sync with a [SceneContent]
 * and an [IsoSceneConfig], then hands back the frame as RGBA pixels.
 *
 * Why offscreen + read back: Compose for Web draws everything (including the MapLibre map) into
 * one canvas, and HTML elements can only sit *above* it, which would cover the HUD. So three.js
 * renders into its own hidden canvas and the pixels are drawn by Compose like any image.
 */
class ThreeRenderer(private val onAsyncChange: () -> Unit) {
    private val canvas: dynamic = document.createElement("canvas")
    private val renderer: dynamic
    private val scene: dynamic = js("new (require('three').Scene)()")
    private val camera: dynamic = js("new (require('three').OrthographicCamera)()")
    private val sun: dynamic
    private val ambient: dynamic
    private val loader: dynamic = js("new (require('three/examples/jsm/loaders/GLTFLoader.js').GLTFLoader)()")

    private class Loaded(var model: SceneModel, val root: dynamic)
    private val loaded = LinkedHashMap<String, Loaded>()
    /** Model keys whose async parse is running, with the source being parsed. */
    private val pending = HashMap<String, ModelSource>()
    private var content = SceneContent(emptyList())
    private var config = IsoSceneConfig()
    private var bounds = Bounds(Vec3(0f, 1f, 0f), Vec3(3f, 1f, 3f))
    private var width = 1
    private var height = 1
    private var pixels: dynamic = null
    private var disposed = false

    init {
        val opts: dynamic = js("{}")
        opts.canvas = canvas
        opts.alpha = true
        opts.antialias = true
        opts.premultipliedAlpha = true
        renderer = js("new (require('three').WebGLRenderer)(opts)")
        renderer.setPixelRatio(1)
        renderer.outputColorSpace = THREE.SRGBColorSpace
        renderer.toneMapping = THREE.NeutralToneMapping // keeps pastel colours (ACES shifts hues)
        renderer.shadowMap.enabled = true
        renderer.shadowMap.type = THREE.PCFShadowMap
        sun = js("new (require('three').DirectionalLight)(0xfff7eb, 1.0)")
        sun.castShadow = true
        sun.shadow.mapSize.set(2048, 2048)
        sun.shadow.bias = -0.0005
        scene.add(sun)
        scene.add(sun.target)
        ambient = js("new (require('three').AmbientLight)(0xffffff, 1.0)")
        scene.add(ambient)
        camera.matrixAutoUpdate = false
        applyLighting()
    }

    fun setSize(w: Int, h: Int) {
        if (w == width && h == height) return
        width = w.coerceAtLeast(1); height = h.coerceAtLeast(1)
        renderer.setSize(width, height, false)
        pixels = null
    }

    fun setConfig(c: IsoSceneConfig) {
        config = c
        applyLighting()
    }

    fun setContent(newContent: SceneContent) {
        if (disposed) return
        content = newContent
        val keys = newContent.models.mapTo(HashSet()) { it.key }
        loaded.keys.filter { it !in keys }.forEach { remove(it) }
        pending.keys.retainAll(keys)
        var geometryChanged = false
        for (m in newContent.models) {
            val cur = loaded[m.key]
            if (cur == null || needsReload(cur.model, m)) {
                if (pending[m.key] != m.source || cur != null) startLoad(m)
            } else if (cur.model != m) {
                applyInstanceState(cur.root, m)
                if (cur.model.position != m.position || cur.model.rotationYDegrees != m.rotationYDegrees || cur.model.transform != m.transform) geometryChanged = true
                cur.model = m
            }
        }
        if (geometryChanged) refit()
    }

    /** Renders and returns the frame as premultiplied RGBA, bottom row first (WebGL order). */
    fun render(): ByteArray {
        updateCamera()
        renderer.render(scene, camera)
        val gl = renderer.getContext()
        val size = width * height * 4
        if (pixels == null || pixels.length != size) pixels = js("new Uint8Array(size)")
        gl.readPixels(0, 0, width, height, gl.RGBA, gl.UNSIGNED_BYTE, pixels)
        val buffer = pixels.buffer
        return js("new Int8Array(buffer)").unsafeCast<ByteArray>()
    }

    val pixelWidth get() = width
    val pixelHeight get() = height

    fun dispose() {
        disposed = true
        loaded.keys.toList().forEach { remove(it) }
        pending.clear()
        renderer.dispose()
        renderer.forceContextLoss()
    }

    // ---------------------------------------------------------------- models

    private fun needsReload(old: SceneModel, new: SceneModel): Boolean =
        old.source != new.source || old.hiddenNodes != new.hiddenNodes || old.matteMaterials != new.matteMaterials ||
            old.castShadows != new.castShadows || old.recolor != new.recolor || (old.tint != null && new.tint == null)

    private fun startLoad(m: SceneModel) {
        val bytes = when (val s = m.source) {
            is ModelSource.Bytes -> s.bytes
            is ModelSource.Asset -> return // bundled Android assets don't exist on the Web
        }
        pending[m.key] = m.source
        val b: dynamic = bytes
        // Copy into a fresh ArrayBuffer: the Int8Array may be a view into a bigger one.
        val arrayBuffer = js("b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength)")
        loader.parse(arrayBuffer, "", { gltf: dynamic ->
            val wanted = content.models.firstOrNull { it.key == m.key }
            if (!disposed && pending[m.key] == m.source && wanted != null) {
                pending.remove(m.key)
                remove(m.key)
                val root = prepare(gltf.scene, wanted)
                scene.add(root)
                loaded[m.key] = Loaded(wanted, root)
                refit()
                onAsyncChange()
            } else {
                disposeObject(gltf.scene)
            }
        }, { err: dynamic ->
            console.error("three.js could not parse ${m.key}", err)
            pending.remove(m.key)
        })
    }

    private fun prepare(root: dynamic, m: SceneModel): dynamic {
        root.matrixAutoUpdate = false
        for (name in m.hiddenNodes) root.getObjectByName(name)?.visible = false
        root.traverse { o: dynamic ->
            if (o.isMesh == true) {
                o.castShadow = m.castShadows
                o.receiveShadow = true
                val mat = o.material
                if (mat != null) {
                    val name = mat.name as? String
                    if (name != null && name in m.matteMaterials) { mat.metalness = 0; mat.roughness = 0.8 }
                    val c = name?.let { m.recolor[it] }
                    if (c != null) mat.color.setRGB(c.r, c.g, c.b, THREE.SRGBColorSpace)
                    // Our generated GLBs have no textures, so colors come only from the factors.
                    mat.needsUpdate = true
                }
            }
            Unit
        }
        applyInstanceState(root, m)
        return root
    }

    private fun applyInstanceState(root: dynamic, m: SceneModel) {
        val arr = (m.transform ?: Mat4.translation(m.position) * Mat4.rotationY(m.rotationYDegrees)).m
        val jsArr: dynamic = js("[]")
        for (v in arr) jsArr.push(v)
        root.matrix.fromArray(jsArr)
        root.matrixWorldNeedsUpdate = true
        m.tint?.let { t ->
            root.traverse { o: dynamic ->
                if (o.isMesh == true) o.material?.color?.setRGB(t.r, t.g, t.b, THREE.SRGBColorSpace)
                Unit
            }
        }
    }

    private fun remove(key: String) {
        val l = loaded.remove(key) ?: return
        scene.remove(l.root)
        disposeObject(l.root)
    }

    /** Frees GPU buffers and materials; three.js does not do this on scene.remove. */
    private fun disposeObject(root: dynamic) {
        root.traverse { o: dynamic ->
            if (o.isMesh == true) {
                o.geometry?.dispose()
                val mat = o.material
                if (mat != null) {
                    if (js("Array.isArray(mat)") as Boolean) (mat as Array<dynamic>).forEach { disposeMaterial(it) } else disposeMaterial(mat)
                }
            }
            Unit
        }
    }

    private fun disposeMaterial(mat: dynamic) {
        for (k in arrayOf("map", "normalMap", "roughnessMap", "metalnessMap", "emissiveMap", "aoMap")) mat[k]?.dispose()
        mat.dispose()
    }

    private fun refit() {
        if (content.fitBounds != null) { bounds = content.fitBounds!!; return }
        if (config.camera != null || loaded.isEmpty()) return
        val box = js("new (require('three').Box3)()")
        for (l in loaded.values) {
            l.root.updateMatrixWorld(true)
            box.expandByObject(l.root)
        }
        if (box.isEmpty() as Boolean) return
        val min = box.min; val max = box.max
        bounds = Bounds(
            Vec3(((min.x + max.x) / 2).unsafeCast<Float>(), ((min.y + max.y) / 2).unsafeCast<Float>(), ((min.z + max.z) / 2).unsafeCast<Float>()),
            Vec3(((max.x - min.x) / 2).unsafeCast<Float>(), ((max.y - min.y) / 2).unsafeCast<Float>(), ((max.z - min.z) / 2).unsafeCast<Float>()),
        )
    }

    // ---------------------------------------------------------------- lights, camera

    /**
     * Same light as Filament, converted from photometric units: Filament's default camera exposure
     * (f/16, 1/125 s, ISO 100) multiplies lux by ~2.6e-5. three.js ambient also divides by π.
     */
    private fun applyLighting() {
        val exposure = 1.0 / (1.2 * 32768.0) // 1 / (1.2 * 2^EV100), EV100 = 15
        sun.intensity = config.sunIntensity * exposure * PI * 0.85
        ambient.intensity = config.ambientIntensity * exposure * PI
        renderer.shadowMap.enabled = config.shadows
        sun.castShadow = config.shadows
        val bg = config.backgroundColor
        if (config.transparent) {
            renderer.setClearColor(0x000000, 0)
        } else {
            val c = js("new (require('three').Color)()")
            c.setRGB(bg.r, bg.g, bg.b, THREE.SRGBColorSpace)
            renderer.setClearColor(c, 1)
        }
    }

    private fun updateCamera() {
        val s = config.camera ?: IsoCamera.compute(config, bounds, width.toFloat() / height)
        camera.left = s.left; camera.right = s.right; camera.top = s.top; camera.bottom = s.bottom
        camera.near = s.near; camera.far = s.far
        camera.position.set(s.eye.x, s.eye.y, s.eye.z)
        camera.up.set(s.up.x, s.up.y, s.up.z)
        // lookAt() reads the world matrix, which is stale with matrixAutoUpdate off: refresh it first.
        camera.updateMatrix()
        camera.updateMatrixWorld(true)
        camera.lookAt(s.target.x, s.target.y, s.target.z)
        camera.updateMatrix()
        camera.updateMatrixWorld(true)
        camera.updateProjectionMatrix()

        // Sun: comes from -direction, aimed at the camera target; shadow box covers the content.
        val d = config.sunDirection.normalized()
        val r = (bounds.halfExtent.length * 1.6f).coerceAtLeast(1f)
        val c = if (config.camera != null) s.target else bounds.center
        sun.position.set(c.x - d.x * r * 2, c.y - d.y * r * 2, c.z - d.z * r * 2)
        sun.target.position.set(c.x, c.y, c.z)
        sun.target.updateMatrixWorld()
        val sc = sun.shadow.camera
        sc.left = -r; sc.right = r; sc.top = r; sc.bottom = -r; sc.near = 0.1; sc.far = r * 4
        sc.updateProjectionMatrix()
    }
}
