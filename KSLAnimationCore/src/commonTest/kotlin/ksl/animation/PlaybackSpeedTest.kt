package ksl.animation

import ksl.app.swing.animation.playback.PlaybackController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins what a playback speed means, because the two viewers have to agree about it and once did not.
 *
 * A speed is **simulated time units per real second** — an absolute rate. The browser's transport bar used
 * to offer multipliers of whatever rate had been chosen to fit the run on screen, which made the same label
 * mean different rates for different models and left its slowest setting far above the desktop's: on a
 * 480-unit run it could not go below six units a second where the desktop reached a quarter of one.
 */
class PlaybackSpeedTest {

    @Test
    fun theSlowestOfferedSpeedIsSlowEnoughToStudyAModel() {
        // A quarter of a unit a second: 480 units takes half an hour, which is the point of the setting.
        assertEquals(0.25, PlaybackController.SPEEDS.first())
        assertTrue(PlaybackController.SPEEDS.zipWithNext().all { (a, b) -> a < b }, "ascending")
    }

    @Test
    fun anAutoChosenSpeedPlaysTheRunInAboutTheTargetTime() {
        val span = 480.0
        val speed = PlaybackController.autoSpeedFor(span, targetSeconds = 25.0)
        val seconds = span / speed
        assertTrue(seconds in 10.0..50.0, "480 units at ${speed}x takes ${seconds}s, not near the 25s target")
    }

    @Test
    fun anAutoChosenSpeedIsATidyNumber() {
        // 1/2/5 x 10^n — a rate a person would have picked, and one the viewers can show in a list.
        for (span in listOf(20.0, 120.0, 480.0, 20_000.0)) {
            val speed = PlaybackController.autoSpeedFor(span)
            val mantissa = generateSequence(speed) { it / 10.0 }.first { it >= 1.0 && it < 10.0 }
            assertTrue(
                mantissa in listOf(1.0, 2.0, 5.0),
                "span $span gave ${speed}x, whose leading digit $mantissa is not 1, 2 or 5"
            )
        }
    }

    @Test
    fun aShortRunIsNotGivenASpeedBelowTheSlowestOffered() {
        // Otherwise the control would open on a value it cannot display.
        assertTrue(PlaybackController.autoSpeedFor(0.5) >= PlaybackController.SPEEDS.first())
        assertEquals(1.0, PlaybackController.autoSpeedFor(0.0), "an empty range falls back to real time")
    }
}
