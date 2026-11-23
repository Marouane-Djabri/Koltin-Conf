package dev.filamentiso

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.ViewConfiguration
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.filamentiso.scene.SceneContent
import dev.filamentiso.scene.SceneGesture
import kotlin.math.hypot

/**
 * TextureView (composes correctly above the map and below the HUD) that reports raw gestures:
 * one finger → Tap or DragStart/Move/End; two fingers → Pan + Zoom.
 */
@SuppressLint("ViewConstructor")
internal class IsoTextureView(
    context: Context,
    private val onGesture: (SceneGesture) -> Unit,
) : TextureView(context) {

    /** Off on the map overlay: touches then fall through to the map below. */
    var gesturesEnabled = true

    private enum class Mode { NONE, PENDING, DRAG, MULTI }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var focusX = 0f
    private var focusY = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                if (mode == Mode.MULTI) onGesture(SceneGesture.Zoom(d.scaleFactor))
                return true
            }
        },
    )

    private fun centroid(e: MotionEvent, skipIndex: Int = -1): Pair<Float, Float> {
        var sx = 0f; var sy = 0f; var n = 0
        for (i in 0 until e.pointerCount) {
            if (i == skipIndex) continue
            sx += e.getX(i); sy += e.getY(i); n++
        }
        return if (n == 0) 0f to 0f else sx / n to sy / n
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!gesturesEnabled) { mode = Mode.NONE; return false }
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = Mode.PENDING; downX = e.x; downY = e.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (mode == Mode.DRAG) onGesture(SceneGesture.DragCancel)
                mode = Mode.MULTI
                centroid(e).let { (x, y) -> focusX = x; focusY = y }
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.PENDING -> if (hypot(e.x - downX, e.y - downY) > touchSlop) {
                    mode = Mode.DRAG
                    onGesture(SceneGesture.DragStart(downX, downY))
                    onGesture(SceneGesture.DragMove(e.x, e.y))
                }
                Mode.DRAG -> onGesture(SceneGesture.DragMove(e.x, e.y))
                Mode.MULTI -> if (e.pointerCount >= 2) {
                    val (x, y) = centroid(e)
                    onGesture(SceneGesture.Pan(x - focusX, y - focusY))
                    focusX = x; focusY = y
                }
                Mode.NONE -> Unit
            }
            MotionEvent.ACTION_POINTER_UP -> if (mode == Mode.MULTI) {
                // Re-anchor on the remaining fingers so the view doesn't jump.
                centroid(e, skipIndex = e.actionIndex).let { (x, y) -> focusX = x; focusY = y }
            }
            MotionEvent.ACTION_UP -> {
                when (mode) {
                    Mode.PENDING -> onGesture(SceneGesture.Tap(e.x, e.y))
                    Mode.DRAG -> onGesture(SceneGesture.DragEnd(e.x, e.y))
                    else -> Unit
                }
                mode = Mode.NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.DRAG) onGesture(SceneGesture.DragCancel)
                mode = Mode.NONE
            }
        }
        return true
    }
}

@Composable
actual fun IsoSceneView(
    content: SceneContent,
    config: IsoSceneConfig,
    onGesture: ((SceneGesture) -> Unit)?,
    modifier: Modifier,
) {
    val latestOnGesture by rememberUpdatedState(onGesture)
    val latestConfig by rememberUpdatedState(config)
    val rendererHolder = remember { mutableStateOf<FilamentIsoRenderer?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            IsoTextureView(ctx) { g -> latestOnGesture?.invoke(g) }.also { sv ->
                sv.gesturesEnabled = latestOnGesture != null
                val r = FilamentIsoRenderer(ctx, sv, latestConfig)
                rendererHolder.value = r
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) r.resume()
            }
        },
        update = { v ->
            v.gesturesEnabled = onGesture != null
            rendererHolder.value?.let { r ->
                r.setContent(content)
                r.setConfig(config)
            }
        },
        onRelease = {
            rendererHolder.value?.destroy()
            rendererHolder.value = null
        },
    )

    // Stop the Choreographer loop while not visible; resume when back.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> rendererHolder.value?.resume()
                Lifecycle.Event.ON_PAUSE -> rendererHolder.value?.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)
