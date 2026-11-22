package dev.filamentiso

import android.content.Context
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import com.google.android.filament.Box
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.Entity
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Owns every Filament object for one [SurfaceView]. Create on the main thread, call
 * [resume]/[pause] with the lifecycle and [destroy] exactly once.
 */
class FilamentIsoRenderer(
    private val context: Context,
    private val surfaceView: SurfaceView,
    assetPath: String,
    initialConfig: IsoSceneConfig,
) : Choreographer.FrameCallback {

    companion object {
        private const val TAG = "FilamentIso"

        init {
            Gltfio.init() // loads gltfio-jni, which also brings up Filament's native lib
        }
    }

    private val engine: Engine = Engine.create()
    private val renderer: Renderer = engine.createRenderer()
    private val scene: Scene = engine.createScene()
    private val view: View = engine.createView()
    @Entity private val cameraEntity: Int = EntityManager.get().create()
    private val camera: Camera = engine.createCamera(cameraEntity)
    @Entity private val sunEntity: Int = EntityManager.get().create()
    private var indirectLight: IndirectLight? = null

    private val materialProvider = UbershaderProvider(engine)
    private val assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
    private val resourceLoader = ResourceLoader(engine)
    private var asset: FilamentAsset? = null

    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private val displayHelper = DisplayHelper(context)
    private var swapChain: SwapChain? = null
    private val choreographer = Choreographer.getInstance()

    private var config = initialConfig
    private var bounds = Bounds(Vec3(0f, 0f, 0f), Vec3(1f, 1f, 1f))
    private var viewportW = 1
    private var viewportH = 1
    private var running = false
    private var destroyed = false

    init {
        view.scene = scene
        view.camera = camera
        applyBackground()

        loadAsset(assetPath)
        applyLighting()

        uiHelper.renderCallback = SurfaceCallback()
        uiHelper.attachTo(surfaceView)
    }

    // ---------------------------------------------------------------- public API

    val currentConfig: IsoSceneConfig get() = config

    fun setConfig(newConfig: IsoSceneConfig) {
        if (destroyed || newConfig == config) return
        val old = config
        config = newConfig
        if (old.backgroundColor != newConfig.backgroundColor) applyBackground()
        if (old.sunDirection != newConfig.sunDirection || old.sunIntensity != newConfig.sunIntensity ||
            old.ambientIntensity != newConfig.ambientIntensity
        ) applyLighting()
        updateCamera()
    }

    fun resume() {
        if (destroyed || running) return
        running = true
        choreographer.postFrameCallback(this)
    }

    fun pause() {
        running = false
        choreographer.removeFrameCallback(this)
    }

    /** Tears down in reverse creation order. Safe to call twice. */
    fun destroy() {
        if (destroyed) return
        pause()
        destroyed = true

        // Detaching triggers onDetachedFromSurface → swap chain destroyed.
        uiHelper.detach()

        asset?.let { a ->
            scene.removeEntities(a.entities)
            assetLoader.destroyAsset(a)
        }
        asset = null
        assetLoader.destroy()
        materialProvider.destroyMaterials()
        materialProvider.destroy()
        resourceLoader.destroy()

        scene.indirectLight = null
        indirectLight?.let { engine.destroyIndirectLight(it) }
        engine.destroyEntity(sunEntity)
        EntityManager.get().destroy(sunEntity)

        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
        engine.destroy()
        Log.i(TAG, "Filament destroyed")
    }

    // ---------------------------------------------------------------- frame loop

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        choreographer.postFrameCallback(this)
        val sc = swapChain ?: return
        if (!uiHelper.isReadyToRender) return
        if (renderer.beginFrame(sc, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    // ---------------------------------------------------------------- scene setup

    private fun loadAsset(path: String) {
        val buffer = context.assets.open(path).use { input ->
            val bytes = input.readBytes()
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).flip() as ByteBuffer
        }
        val a = assetLoader.createAsset(buffer) ?: error("gltfio could not parse $path")
        resourceLoader.loadResources(a)
        a.releaseSourceData()
        asset = a

        val rm = engine.renderableManager
        val hidden = HashSet<Int>()
        for (e in a.entities) {
            val name = a.getName(e)
            if (name != null && name in config.hiddenNodes) {
                hidden += e
                continue
            }
            val ri = rm.getInstance(e)
            if (ri != 0) {
                rm.setCastShadows(ri, true)
                rm.setReceiveShadows(ri, true)
            }
        }
        scene.addEntities(a.entities.filter { it !in hidden }.toIntArray())
        Log.i(TAG, "Loaded $path: ${a.entities.size} entities, hid ${hidden.size} ${config.hiddenNodes}")

        // Materials that exported with no PBR block default to metallic = 1 (dark under
        // ambient-only light). Make them matte dielectrics instead.
        var overridden = 0
        for (mi in a.instance.materialInstances) {
            if (mi.name in config.matteMaterials) {
                mi.setParameter("metallicFactor", 0f)
                mi.setParameter("roughnessFactor", 0.8f)
                overridden++
            }
        }
        Log.i(TAG, "Material overrides applied to $overridden instances")

        bounds = computeVisibleBounds(a, hidden)
        Log.i(TAG, "Visible bounds: $bounds")
    }

    /** World-space AABB of all visible renderables (the asset box would include hidden ones). */
    private fun computeVisibleBounds(a: FilamentAsset, hidden: Set<Int>): Bounds {
        val rm = engine.renderableManager
        val tm = engine.transformManager
        val mn = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val mx = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        val box = Box()
        val m = FloatArray(16)
        for (e in a.entities) {
            if (e in hidden) continue
            val ri = rm.getInstance(e)
            if (ri == 0) continue
            rm.getAxisAlignedBoundingBox(ri, box)
            tm.getWorldTransform(tm.getInstance(e), m)
            val c = box.center
            val h = box.halfExtent
            for (i in 0 until 8) {
                val x = c[0] + if (i and 1 != 0) h[0] else -h[0]
                val y = c[1] + if (i and 2 != 0) h[1] else -h[1]
                val z = c[2] + if (i and 4 != 0) h[2] else -h[2]
                for (k in 0 until 3) {
                    val w = m[k] * x + m[4 + k] * y + m[8 + k] * z + m[12 + k]
                    mn[k] = min(mn[k], w)
                    mx[k] = max(mx[k], w)
                }
            }
        }
        if (mn[0] > mx[0]) return a.boundingBox.let { Bounds(it.center.toVec3(), it.halfExtent.toVec3()) }
        return Bounds(
            Vec3((mn[0] + mx[0]) / 2, (mn[1] + mx[1]) / 2, (mn[2] + mx[2]) / 2),
            Vec3((mx[0] - mn[0]) / 2, (mx[1] - mn[1]) / 2, (mx[2] - mn[2]) / 2),
        )
    }

    private fun applyLighting() {
        // Sun: (re)build the light component on the same entity.
        engine.destroyEntity(sunEntity)
        val d = config.sunDirection.normalized()
        LightManager.Builder(LightManager.Type.SUN)
            .color(1.0f, 0.97f, 0.92f)
            .intensity(config.sunIntensity)
            .direction(d.x, d.y, d.z)
            .sunAngularRadius(1.5f)
            .castShadows(true)
            .build(engine, sunEntity)
        scene.addEntity(sunEntity)

        // Ambient: one SH band = constant irradiance from every direction. No cmgen/KTX needed.
        scene.indirectLight = null
        indirectLight?.let { engine.destroyIndirectLight(it) }
        indirectLight = IndirectLight.Builder()
            .irradiance(1, floatArrayOf(1.0f, 1.0f, 1.0f))
            .intensity(config.ambientIntensity)
            .build(engine)
        scene.indirectLight = indirectLight
    }

    private fun applyBackground() {
        val c = config.backgroundColor
        renderer.clearOptions = renderer.clearOptions.apply {
            clear = true
            clearColor = doubleArrayOf(
                srgbToLinear(c.r).toDouble(), srgbToLinear(c.g).toDouble(), srgbToLinear(c.b).toDouble(), 1.0,
            )
        }
    }

    private fun updateCamera() {
        val s = IsoCamera.compute(config, bounds, viewportW.toFloat() / viewportH.toFloat())
        camera.setProjection(Camera.Projection.ORTHO, s.left, s.right, s.bottom, s.top, s.near, s.far)
        camera.lookAt(
            s.eye.x.toDouble(), s.eye.y.toDouble(), s.eye.z.toDouble(),
            s.target.x.toDouble(), s.target.y.toDouble(), s.target.z.toDouble(),
            s.up.x.toDouble(), s.up.y.toDouble(), s.up.z.toDouble(),
        )
    }

    private fun FloatArray.toVec3() = Vec3(this[0], this[1], this[2])
    private fun srgbToLinear(c: Float) = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    // ---------------------------------------------------------------- surface

    private inner class SurfaceCallback : UiHelper.RendererCallback {
        override fun onNativeWindowChanged(surface: Surface) {
            swapChain?.let { engine.destroySwapChain(it) }
            swapChain = engine.createSwapChain(surface)
            surfaceView.display?.let { displayHelper.attach(renderer, it) }
        }

        override fun onDetachedFromSurface() {
            displayHelper.detach()
            swapChain?.let {
                engine.destroySwapChain(it)
                // Make sure the swap chain is gone before the Surface is released.
                engine.flushAndWait()
                swapChain = null
            }
        }

        override fun onResized(width: Int, height: Int) {
            viewportW = max(width, 1)
            viewportH = max(height, 1)
            view.viewport = Viewport(0, 0, viewportW, viewportH)
            updateCamera()
        }
    }
}
