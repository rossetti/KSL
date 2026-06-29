package ksl.app.swing.animation.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Headless tests for the [PlaybackController] time state machine. */
class PlaybackControllerTest {

    @Test
    fun `advance respects speed and only moves while playing`() {
        val c = PlaybackController(0.0..100.0)
        c.speed = 10.0
        c.advanceBy(1.0)
        assertEquals(0.0, c.currentTime, "paused -> no movement")

        c.play()
        c.advanceBy(1.0) // 10 sim units/real sec * 1 sec
        assertEquals(10.0, c.currentTime, 1e-9)
        c.advanceBy(0.5)
        assertEquals(15.0, c.currentTime, 1e-9)
    }

    @Test
    fun `stop pauses and rewinds to the start of the active range`() {
        val c = PlaybackController(0.0..100.0)
        c.speed = 10.0
        c.play()
        c.advanceBy(2.0) // -> 20.0, playing
        assertEquals(20.0, c.currentTime, 1e-9)
        c.stop()
        assertFalse(c.isPlaying, "stop pauses")
        assertEquals(0.0, c.currentTime, 1e-9, "stop rewinds to range start")
        // With a focus window, stop rewinds to the focus start.
        c.setInOut(30.0, 60.0)
        c.play(); c.advanceBy(1.0); c.stop()
        assertEquals(30.0, c.currentTime, 1e-9, "stop rewinds to focus start when a focus is set")
    }

    @Test
    fun `reaching the end stops at end without loop`() {
        val c = PlaybackController(0.0..10.0)
        c.speed = 100.0
        c.play()
        c.advanceBy(1.0) // would overshoot to 100
        assertEquals(10.0, c.currentTime, 1e-9, "clamped to end")
        assertFalse(c.isPlaying, "stopped at end")
    }

    @Test
    fun `loop wraps around the range`() {
        val c = PlaybackController(0.0..10.0)
        c.speed = 1.0
        c.loop = true
        c.play()
        c.advanceBy(12.0) // 12 mod 10 = 2
        assertEquals(2.0, c.currentTime, 1e-9)
        assertTrue(c.isPlaying, "loop keeps playing")
    }

    @Test
    fun `play from end restarts at the beginning`() {
        val c = PlaybackController(0.0..10.0)
        c.seek(10.0)
        c.play()
        assertEquals(0.0, c.currentTime, 1e-9, "play at end rewinds")
    }

    @Test
    fun `seek and fraction clamp and convert`() {
        val c = PlaybackController(0.0..200.0)
        c.seek(-5.0); assertEquals(0.0, c.currentTime)
        c.seek(500.0); assertEquals(200.0, c.currentTime)
        c.seekFraction(0.25); assertEquals(50.0, c.currentTime, 1e-9)
        assertEquals(0.25, c.fraction(), 1e-9)

        val notifications = ArrayList<Double>()
        c.addTimeListener { notifications.add(it) }
        c.seek(100.0)
        assertEquals(listOf(100.0), notifications, "listener fires on seek")
    }

    @Test
    fun `setting range clamps current time`() {
        val c = PlaybackController(0.0..100.0)
        c.seek(80.0)
        c.timeRange = 0.0..50.0
        assertEquals(50.0, c.currentTime, "current time clamped into new range")
    }

    @Test
    fun `focus confines play, scrub, and loop to the sub-range (8I7)`() {
        val c = PlaybackController(0.0..100.0)
        c.setInOut(20.0, 40.0)
        assertEquals(20.0..40.0, c.focus)
        assertEquals(20.0, c.currentTime, 1e-9, "current time clamped into focus")

        // Fraction/seek map over the focus, not the full range.
        c.seekFraction(0.5); assertEquals(30.0, c.currentTime, 1e-9)
        assertEquals(0.5, c.fraction(), 1e-9)
        c.seek(5.0); assertEquals(20.0, c.currentTime, 1e-9, "seek clamps to focus start")

        // Loop wraps within the focus span (20..40).
        c.speed = 1.0; c.loop = true; c.play()
        c.advanceBy(25.0) // 20 + (25 mod 20) = 25
        assertEquals(25.0, c.currentTime, 1e-9)

        // Without loop, stops at the focus end.
        c.loop = false; c.seek(20.0); c.play(); c.speed = 100.0
        c.advanceBy(1.0)
        assertEquals(40.0, c.currentTime, 1e-9, "stops at focus end")
        assertFalse(c.isPlaying)
    }

    @Test
    fun `clearing focus restores the full range (8I7)`() {
        val c = PlaybackController(0.0..100.0)
        c.setInOut(20.0, 40.0)
        c.clearFocus()
        assertEquals(null, c.focus)
        c.seekFraction(1.0); assertEquals(100.0, c.currentTime, 1e-9, "fraction maps over full range again")
    }

    @Test
    fun `in and out points order-independently and clamp to the range (8I7)`() {
        val c = PlaybackController(0.0..100.0)
        c.seek(60.0); c.setIn(60.0)
        c.seek(30.0); c.setOut(30.0) // out before in -> ordered to 30..60
        assertEquals(30.0..60.0, c.focus)
        c.setInOut(-5.0, 500.0)
        assertEquals(0.0..100.0, c.focus, "endpoints clamp to the full range")
    }
}
