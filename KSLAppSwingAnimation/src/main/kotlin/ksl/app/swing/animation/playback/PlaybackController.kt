/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.app.swing.animation.playback

/**
 * The headless playback state machine for the replay viewer: it owns the current replay time, the
 * playback speed, and the play/pause/loop state, and advances time when driven. It contains no
 * Swing code — a [PlaybackPanel] owns the wall-clock timer that calls [advanceBy], and tests drive
 * it directly — so the timing logic stays unit-testable.
 *
 * [speed] is in simulated-time units advanced per real second (so 2.0 plays twice as fast as the
 * simulation ran, in base time units). Time is always clamped to [timeRange]; reaching the end
 * either stops playback or wraps when [loop] is set.
 */
class PlaybackController(timeRange: ClosedRange<Double> = 0.0..0.0) {

    var timeRange: ClosedRange<Double> = timeRange
        set(value) {
            field = value
            currentTime = currentTime.coerceIn(effectiveRange.start, effectiveRange.endInclusive)
        }

    /**
     * Optional in/out focus sub-range (8I.7). When set, play/scrub/loop all operate within it (the
     * scrubber zooms to it); `null` reproduces full-range behavior. Setting it clamps the current time
     * into the focus.
     */
    var focus: ClosedRange<Double>? = null
        set(value) {
            field = value
            currentTime = currentTime.coerceIn(effectiveRange.start, effectiveRange.endInclusive)
        }

    /** The active playback range: the [focus] sub-range when set, else the full [timeRange]. */
    val effectiveRange: ClosedRange<Double>
        get() = focus ?: timeRange

    var currentTime: Double = timeRange.start
        private set(value) {
            field = value
            listeners.forEach { it(field) }
        }

    /** Simulated-time units advanced per real second of playback. */
    var speed: Double = 1.0

    var loop: Boolean = false

    var isPlaying: Boolean = false
        private set

    private val listeners = ArrayList<(Double) -> Unit>()

    /** Registers a callback fired whenever [currentTime] changes (play tick, seek, or range clamp). */
    fun addTimeListener(listener: (Double) -> Unit) {
        listeners.add(listener)
    }

    fun play() {
        if (currentTime >= effectiveRange.endInclusive) currentTime = effectiveRange.start
        isPlaying = true
    }

    /** Sets the in-point of the focus to [t] (8I.7), keeping the current out-point (clamped/ordered). */
    fun setIn(t: Double) = setInOut(t, focus?.endInclusive ?: timeRange.endInclusive)

    /** Sets the out-point of the focus to [t] (8I.7), keeping the current in-point (clamped/ordered). */
    fun setOut(t: Double) = setInOut(focus?.start ?: timeRange.start, t)

    /** Sets the focus to the span between [a] and [b] (order-independent, clamped to [timeRange]). */
    fun setInOut(a: Double, b: Double) {
        val lo = minOf(a, b).coerceIn(timeRange.start, timeRange.endInclusive)
        val hi = maxOf(a, b).coerceIn(timeRange.start, timeRange.endInclusive)
        focus = lo..hi
    }

    /** Clears the focus, restoring full-range playback (8I.7). */
    fun clearFocus() {
        focus = null
    }

    fun pause() {
        isPlaying = false
    }

    /** Stops playback and rewinds to the start of the active range (P1). */
    fun stop() {
        isPlaying = false
        currentTime = effectiveRange.start
    }

    fun togglePlay() {
        if (isPlaying) pause() else play()
    }

    /** Jumps to [t] (clamped to the active range); pauses behavior is unchanged. */
    fun seek(t: Double) {
        currentTime = t.coerceIn(effectiveRange.start, effectiveRange.endInclusive)
    }

    /**
     * Advances time by [realSeconds] of wall-clock at the current [speed], if playing. On reaching
     * the end it wraps when [loop] is set, otherwise it stops at the end. A no-op when paused.
     */
    fun advanceBy(realSeconds: Double) {
        if (!isPlaying || realSeconds <= 0.0) return
        val range = effectiveRange
        var next = currentTime + speed * realSeconds
        if (next >= range.endInclusive) {
            if (loop) {
                val span = range.endInclusive - range.start
                next = if (span > 0.0) range.start + (next - range.start) % span else range.start
            } else {
                next = range.endInclusive
                isPlaying = false
            }
        }
        currentTime = next.coerceIn(range.start, range.endInclusive)
    }

    /** Fractional progress through the active range in `[0,1]` (0 for an empty range). */
    fun fraction(): Double {
        val span = effectiveRange.endInclusive - effectiveRange.start
        return if (span <= 0.0) 0.0 else (currentTime - effectiveRange.start) / span
    }

    /** Seeks to a fractional position in `[0,1]` of the active range. */
    fun seekFraction(f: Double) {
        val span = effectiveRange.endInclusive - effectiveRange.start
        seek(effectiveRange.start + f.coerceIn(0.0, 1.0) * span)
    }
}
