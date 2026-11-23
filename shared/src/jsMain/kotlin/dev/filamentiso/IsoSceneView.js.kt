package dev.filamentiso

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.filamentiso.scene.SceneContent
import dev.filamentiso.scene.SceneGesture
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.math.exp
import kotlin.math.hypot

/**
 * three.js renders offscreen at up to 1.5 physical px per CSS px, then Compose draws the frame
 * (flipped: WebGL rows are bottom-up). Input becomes the same [SceneGesture]s Android sends.
 */
@Composable
actual fun IsoSceneView(
    content: SceneContent,
    config: IsoSceneConfig,
    onGesture: ((SceneGesture) -> Unit)?,
    modifier: Modifier,
) {
    var asyncVersion by remember { mutableIntStateOf(0) }
    val renderer = remember { ThreeRenderer { asyncVersion++ } }
    DisposableEffect(renderer) { onDispose { renderer.dispose() } }

    var size by remember { mutableStateOf(IntSize.Zero) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    val density = LocalDensity.current.density
    val renderScale = (1.5f / density).coerceAtMost(1f)

    LaunchedEffect(content, config, size, asyncVersion) {
        if (size.width == 0 || size.height == 0) return@LaunchedEffect
        withFrameNanos { } // coalesce several state changes into one render per frame
        val w = (size.width * renderScale).toInt().coerceAtLeast(1)
        val h = (size.height * renderScale).toInt().coerceAtLeast(1)
        renderer.setSize(w, h)
        renderer.setConfig(config)
        renderer.setContent(content)
        val bytes = renderer.render()
        frame = Image.makeRaster(ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.PREMUL), bytes, w * 4).toComposeImageBitmap()
    }

    val latestOnGesture by rememberUpdatedState(onGesture)
    val gestures = if (onGesture == null) Modifier else Modifier.pointerInput(Unit) { detectSceneGestures { latestOnGesture?.invoke(it) } }

    Canvas(modifier.onSizeChanged { size = it }.then(gestures)) {
        val img = frame ?: return@Canvas
        scale(1f, -1f) {
            drawImage(img, dstSize = IntSize(this.size.width.toInt(), this.size.height.toInt()), dstOffset = IntOffset.Zero, filterQuality = FilterQuality.Low)
        }
    }
}

/**
 * One finger / left mouse: Tap or DragStart/Move/End. Two fingers: Pan + Zoom.
 * Mouse wheel: Zoom. Right or middle mouse drag: Pan.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectSceneGestures(emit: (SceneGesture) -> Unit) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val first = awaitPointerEvent()
        if (first.type == PointerEventType.Scroll) {
            val dy = first.changes.firstOrNull()?.scrollDelta?.y ?: 0f
            if (dy != 0f) emit(SceneGesture.Zoom(exp(-dy * 0.15f)))
            first.changes.forEach { it.consume() }
            return@awaitEachGesture
        }
        val down = first.changes.firstOrNull { it.pressed && !it.previousPressed } ?: return@awaitEachGesture
        val panButton = first.buttons.isSecondaryPressed || first.buttons.isTertiaryPressed
        val start = down.position
        var dragging = false
        var multi = false
        var lastCentroid = Offset.Zero
        var lastSpan = 0f
        var lastMouse = start
        while (true) {
            val ev = awaitPointerEvent()
            val pressed = ev.changes.filter { it.pressed }
            if (pressed.isEmpty()) {
                val up = ev.changes.first().position
                when {
                    dragging -> emit(SceneGesture.DragEnd(up.x, up.y))
                    !multi && !panButton -> emit(SceneGesture.Tap(up.x, up.y))
                }
                break
            }
            if (pressed.size >= 2) {
                val c = Offset(pressed.map { it.position.x }.average().toFloat(), pressed.map { it.position.y }.average().toFloat())
                val span = hypot(pressed[0].position.x - pressed[1].position.x, pressed[0].position.y - pressed[1].position.y)
                if (!multi) {
                    if (dragging) { emit(SceneGesture.DragCancel); dragging = false }
                    multi = true
                } else {
                    emit(SceneGesture.Pan(c.x - lastCentroid.x, c.y - lastCentroid.y))
                    if (lastSpan > 0f && span > 0f) emit(SceneGesture.Zoom(span / lastSpan))
                }
                lastCentroid = c; lastSpan = span
            } else if (!multi) {
                val p = pressed[0].position
                if (panButton) {
                    emit(SceneGesture.Pan(p.x - lastMouse.x, p.y - lastMouse.y))
                    lastMouse = p
                } else if (!dragging && hypot(p.x - start.x, p.y - start.y) > slop) {
                    dragging = true
                    emit(SceneGesture.DragStart(start.x, start.y))
                    emit(SceneGesture.DragMove(p.x, p.y))
                } else if (dragging && pressed[0].positionChanged()) {
                    emit(SceneGesture.DragMove(p.x, p.y))
                }
            }
            ev.changes.forEach { it.consume() }
        }
    }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
