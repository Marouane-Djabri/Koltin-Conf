package dev.filamentiso

import dev.filamentiso.world.IsoSub
import dev.filamentiso.world.TransitionLook
import dev.filamentiso.world.ViewMode
import dev.filamentiso.world.ViewModeMachine as M
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ViewModeTest {
    @Test
    fun openRunsTransitionThenIsoExterior() {
        var m: ViewMode = M.open(ViewMode.Map, "b1")
        assertEquals(ViewMode.TransitionToIso("b1", 0f), m)
        m = M.tick(m, 400f)
        assertEquals(0.5f, (m as ViewMode.TransitionToIso).progress)
        m = M.tick(m, 500f)
        assertEquals(ViewMode.Iso("b1", IsoSub.EXTERIOR), m)
    }

    @Test
    fun backGoesInteriorThenExteriorThenMap() {
        var m: ViewMode = ViewMode.Iso("b1", IsoSub.EXTERIOR)
        m = M.enterInterior(m)
        assertEquals(IsoSub.INTERIOR, (m as ViewMode.Iso).sub)
        m = M.back(m)
        assertEquals(ViewMode.Iso("b1", IsoSub.EXTERIOR), m)
        m = M.back(m)
        assertEquals(ViewMode.TransitionToMap("b1", 0f), m)
        m = M.tick(m, 1000f)
        assertEquals(ViewMode.Map, m)
    }

    @Test
    fun backMidTransitionReversesFromSameLook() {
        val half = M.tick(M.open(ViewMode.Map, "b1"), 200f) // 25 %
        val back = M.back(half)
        assertIs<ViewMode.TransitionToMap>(back)
        assertEquals(M.isoAmount(half), M.isoAmount(back), "no visual jump")
        // And re-opening mid-way turns around again.
        val again = M.open(M.tick(back, 100f), "b1")
        assertIs<ViewMode.TransitionToIso>(again)
    }

    @Test
    fun invalidEventsAreIgnored() {
        assertEquals(ViewMode.Map, M.enterInterior(ViewMode.Map))
        assertEquals(ViewMode.Map, M.back(ViewMode.Map))
        assertEquals(ViewMode.Map, M.tick(ViewMode.Map, 1000f))
        val iso = ViewMode.Iso("b1", IsoSub.EXTERIOR)
        assertEquals(iso, M.open(iso, "b2"), "can't open another building from the iso view")
    }

    @Test
    fun transitionLookIsMonotonic() {
        var prev = -1f
        for (i in 0..20) {
            val a = TransitionLook.backdropAlpha(i / 20f)
            assertTrue(a >= prev); prev = a
        }
        assertEquals(0f, TransitionLook.backdropAlpha(0f))
        assertEquals(1f, TransitionLook.backdropAlpha(1f))
        assertEquals(1f, TransitionLook.isoZoomFactor(1f))
        assertTrue(!TransitionLook.showIsoScene(0.49f) && TransitionLook.showIsoScene(0.5f))
    }
}
