package dev.filamentiso

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceView
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

/**
 * SurfaceView that turns gestures into config changes:
 * one-finger horizontal drag → yaw, pinch → zoom. Pitch never changes.
 */
@SuppressLint("ViewConstructor")
internal class IsoSurfaceView(
    context: Context,
    private val currentConfig: () -> IsoSceneConfig,
    private val onConfigChange: (IsoSceneConfig) -> Unit,
) : SurfaceView(context) {

    /** Degrees of yaw per pixel dragged, scaled so a full-width swipe ≈ 180°. */
    private val degreesPerPixel get() = 180f / width.coerceAtLeast(1)
    private var lastX = 0f
    private var dragging = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val c = currentConfig()
                onConfigChange(c.withZoom(c.zoom * d.scaleFactor))
                return true
            }
        },
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; dragging = true }
            MotionEvent.ACTION_POINTER_DOWN -> dragging = false // pinch takes over
            MotionEvent.ACTION_MOVE -> if (dragging && event.pointerCount == 1 && !scaleDetector.isInProgress) {
                val dx = event.x - lastX
                lastX = event.x
                val c = currentConfig()
                // Dragging right turns the room to the right (camera orbits the other way).
                onConfigChange(c.withYaw(c.yawDegrees - dx * degreesPerPixel))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }
}

@Composable
actual fun IsoSceneView(
    assetPath: String,
    config: IsoSceneConfig,
    onConfigChange: (IsoSceneConfig) -> Unit,
    modifier: Modifier,
) {
    val latestConfig by rememberUpdatedState(config)
    val latestOnChange by rememberUpdatedState(onConfigChange)
    val rendererHolder = remember { mutableStateOf<FilamentIsoRenderer?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            IsoSurfaceView(
                ctx,
                // Gestures read/write the renderer's config synchronously, so several touch
                // events between two recompositions don't read a stale value.
                currentConfig = { rendererHolder.value?.currentConfig ?: latestConfig },
                onConfigChange = { rendererHolder.value?.setConfig(it); latestOnChange(it) },
            ).also { sv ->
                val r = FilamentIsoRenderer(ctx, sv, assetPath, latestConfig)
                rendererHolder.value = r
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) r.resume()
            }
        },
        update = { rendererHolder.value?.setConfig(config) },
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
