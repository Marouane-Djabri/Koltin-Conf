package dev.filamentiso.world

import kotlin.math.max
import kotlin.math.min

enum class IsoSub { EXTERIOR, INTERIOR }

/**
 * What the world screen shows. Pure data: Android and the Web render the same thing for the same
 * state, and every transition is unit-tested ([ViewModeMachine]).
 */
sealed interface ViewMode {
    data object Map : ViewMode
    data class TransitionToIso(val buildingId: String, val progress: Float) : ViewMode
    data class Iso(val buildingId: String, val sub: IsoSub) : ViewMode
    data class TransitionToMap(val buildingId: String, val progress: Float) : ViewMode

    val buildingIdOrNull: String?
        get() = when (this) {
            Map -> null
            is TransitionToIso -> buildingId
            is Iso -> buildingId
            is TransitionToMap -> buildingId
        }
}

/**
 * The state machine. [tick] advances transitions by real time; everything else is an event.
 * Invalid events (e.g. "enter interior" while on the map) leave the state unchanged.
 */
object ViewModeMachine {
    const val TRANSITION_MS = 800f

    fun open(m: ViewMode, buildingId: String): ViewMode = when (m) {
        ViewMode.Map -> ViewMode.TransitionToIso(buildingId, 0f)
        // Re-opening the same building while leaving it: turn around from where we are.
        is ViewMode.TransitionToMap -> if (m.buildingId == buildingId) ViewMode.TransitionToIso(buildingId, 1f - m.progress) else m
        else -> m
    }

    /** Back button / system back / pinch out: interior → exterior → map. */
    fun back(m: ViewMode): ViewMode = when (m) {
        ViewMode.Map -> m
        is ViewMode.TransitionToIso -> ViewMode.TransitionToMap(m.buildingId, 1f - m.progress)
        is ViewMode.Iso -> if (m.sub == IsoSub.INTERIOR) m.copy(sub = IsoSub.EXTERIOR) else ViewMode.TransitionToMap(m.buildingId, 0f)
        is ViewMode.TransitionToMap -> m
    }

    fun enterInterior(m: ViewMode): ViewMode = if (m is ViewMode.Iso) m.copy(sub = IsoSub.INTERIOR) else m
    fun exitInterior(m: ViewMode): ViewMode = if (m is ViewMode.Iso) m.copy(sub = IsoSub.EXTERIOR) else m

    fun tick(m: ViewMode, dtMs: Float): ViewMode = when (m) {
        is ViewMode.TransitionToIso -> {
            val p = m.progress + dtMs / TRANSITION_MS
            if (p >= 1f) ViewMode.Iso(m.buildingId, IsoSub.EXTERIOR) else m.copy(progress = p)
        }
        is ViewMode.TransitionToMap -> {
            val p = m.progress + dtMs / TRANSITION_MS
            if (p >= 1f) ViewMode.Map else m.copy(progress = p)
        }
        else -> m
    }

    /** The building was deleted (or never loaded): drop back to the map. */
    fun buildingGone(m: ViewMode): ViewMode = ViewMode.Map

    /**
     * How "isometric" the screen is, 0 = map, 1 = iso view. Both directions use the same curve,
     * so going back plays the opening animation in reverse.
     */
    fun isoAmount(m: ViewMode): Float = when (m) {
        ViewMode.Map -> 0f
        is ViewMode.TransitionToIso -> m.progress
        is ViewMode.Iso -> 1f
        is ViewMode.TransitionToMap -> 1f - m.progress
    }
}

/**
 * The transition's look as a function of [ViewModeMachine.isoAmount] t (0..1), shared by both platforms:
 * - 0.00–0.45: the map camera flies to the building (started by the UI when the transition begins);
 * - 0.30–0.65: the pastel backdrop fades in over the map;
 * - from 0.50: the renderer switches from the map-anchored overlay to the iso scene, which zooms in.
 */
object TransitionLook {
    fun backdropAlpha(t: Float) = smooth(0.30f, 0.65f, t)
    fun showIsoScene(t: Float) = t >= 0.5f
    /** Multiplies the iso camera zoom: starts a bit far, settles at 1. */
    fun isoZoomFactor(t: Float) = 0.55f + 0.45f * smooth(0.5f, 1f, t)
    /** HUD for the iso view fades in last. */
    fun isoHudAlpha(t: Float) = smooth(0.8f, 1f, t)

    /** Ease in-out (smoothstep) of t between [a] and [b]. */
    fun smooth(a: Float, b: Float, t: Float): Float {
        val x = ((t - a) / (b - a)).let { max(0f, min(1f, it)) }
        return x * x * (3 - 2 * x)
    }
}
