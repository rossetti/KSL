package ksl.animation.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a finger on the exported player's canvas is supposed to do.
 *
 * These are the cases that fail silently in a browser — the animation just moves oddly — so they are worth
 * pinning here rather than discovering on a tablet.
 */
class PointerGesturesTest {

    private fun assertClose(expected: Double, actual: Double, what: String) =
        assertTrue(kotlin.math.abs(expected - actual) < 1e-9, "$what: expected $expected but was $actual")

    @Test
    fun oneFingerPansByTheDistanceItMovedAndDoesNotZoom() {
        val g = PointerGestures()
        g.down(1, 100.0, 100.0, 0.0)
        val change = g.move(1, 130.0, 90.0)!!
        assertClose(30.0, change.panXPx, "pan x")
        assertClose(-10.0, change.panYPx, "pan y")
        assertEquals(1.0, change.zoomFactor, "one finger must not zoom")
    }

    @Test
    fun anUntrackedPointerAsksForNothing() {
        val g = PointerGestures()
        assertNull(g.move(7, 10.0, 10.0), "a move with no press behind it is not a gesture")
    }

    @Test
    fun twoFingersSpreadingZoomInAboutTheirMidpoint() {
        val g = PointerGestures()
        g.down(1, 100.0, 200.0, 0.0)
        g.down(2, 200.0, 200.0, 0.0) // 100 px apart, midpoint x = 150

        val change = g.move(2, 300.0, 200.0)!! // now 200 px apart, midpoint x = 200
        assertClose(2.0, change.zoomFactor, "spreading to twice the distance doubles the zoom")
        assertClose(200.0, change.focusXPx, "zoom holds the fingers' midpoint")
        assertClose(200.0, change.focusYPx, "zoom holds the fingers' midpoint")
        // The midpoint also travelled, and that part is a pan.
        assertClose(50.0, change.panXPx, "pan follows the midpoint")
    }

    @Test
    fun theFirstMoveOfAPinchDoesNotScaleAgainstAStaleSpread() {
        val g = PointerGestures()
        g.down(1, 100.0, 100.0, 0.0)
        // A second finger arrives mid-drag. Its spread was zero an instant ago; taking a ratio against
        // that would send the zoom to infinity on the very next move.
        g.down(2, 140.0, 100.0, 10.0)
        val change = g.move(2, 141.0, 100.0)!!
        assertTrue(change.zoomFactor in 1.0..1.1, "a nudge must be a nudge, not a jump: ${change.zoomFactor}")
    }

    @Test
    fun liftingOneFingerOfAPinchIsNotAJump() {
        val g = PointerGestures()
        g.down(1, 100.0, 100.0, 0.0)
        g.down(2, 300.0, 100.0, 0.0)
        g.move(2, 320.0, 100.0)

        g.up(2, 320.0, 100.0, 50.0) // the centroid moves from x=210 to x=100, but the hand did not
        val change = g.move(1, 105.0, 100.0)!!
        assertClose(5.0, change.panXPx, "the remaining finger continues from where it is")
        assertEquals(1.0, change.zoomFactor, "one finger left means no zoom")
    }

    @Test
    fun aThirdFingerDoesNotHandOverTheGesture() {
        val g = PointerGestures()
        g.down(1, 100.0, 100.0, 0.0)
        g.down(2, 200.0, 100.0, 0.0)
        g.down(3, 500.0, 100.0, 0.0) // ignored: the first two own the pinch
        val change = g.move(2, 300.0, 100.0)!!
        assertClose(2.0, change.zoomFactor, "the spread is still measured between fingers 1 and 2")
    }

    @Test
    fun twoQuickTapsInTheSamePlaceAreADoubleTap() {
        val g = PointerGestures()
        g.down(1, 50.0, 50.0, 0.0)
        assertFalse(g.up(1, 50.0, 50.0, 40.0), "one tap is not two")
        g.down(2, 52.0, 51.0, 180.0)
        assertTrue(g.up(2, 52.0, 51.0, 210.0), "the second tap resets the view")
    }

    @Test
    fun aSlowSecondTapOrOneFarAwayIsNotADoubleTap() {
        val late = PointerGestures()
        late.down(1, 50.0, 50.0, 0.0)
        late.up(1, 50.0, 50.0, 40.0)
        late.down(2, 50.0, 50.0, 900.0)
        assertFalse(late.up(2, 50.0, 50.0, 930.0), "a second tap a second later is a separate tap")

        val elsewhere = PointerGestures()
        elsewhere.down(1, 50.0, 50.0, 0.0)
        elsewhere.up(1, 50.0, 50.0, 40.0)
        elsewhere.down(2, 400.0, 50.0, 150.0)
        assertFalse(elsewhere.up(2, 400.0, 50.0, 180.0), "a tap across the canvas is not the same tap")
    }

    @Test
    fun aDragIsNotATapHoweverBrieflyItLasted() {
        val g = PointerGestures()
        g.down(1, 50.0, 50.0, 0.0)
        g.move(1, 250.0, 50.0)
        assertFalse(g.up(1, 250.0, 50.0, 30.0), "panning then releasing must not reset the view")
    }

    @Test
    fun endingAPinchIsNotATap() {
        val g = PointerGestures()
        g.down(1, 100.0, 100.0, 0.0)
        g.down(2, 200.0, 100.0, 10.0)
        assertFalse(g.up(2, 200.0, 100.0, 30.0), "releasing one of two fingers is not a tap")
        assertFalse(g.up(1, 100.0, 100.0, 40.0), "nor is releasing the other")
    }

    @Test
    fun theCanvasKnowsWhenNothingIsHeld() {
        val g = PointerGestures()
        assertFalse(g.isActive)
        g.down(1, 0.0, 0.0, 0.0)
        assertTrue(g.isActive)
        g.cancel(1)
        assertFalse(g.isActive, "a cancelled pointer leaves nothing held")
    }
}
