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
import kotlin.math.pow

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

    companion object {
        /**
         * The speeds a viewer offers, in **simulated time units per real second** — an absolute rate, not a
         * multiplier. A run whose clock reaches 480 played at `5.0` takes 96 seconds to watch, whatever the
         * model is and whichever viewer is showing it.
         *
         * Absolute rather than relative because the two are indistinguishable on screen and behave quite
         * differently: a multiplier of a rate chosen to fit the run means "1x" is fast for a long run and
         * slow for a short one, and its slowest setting is only ever a fraction of a speed somebody else
         * picked. The browser player used to do that, and could not be slowed below a quarter of a fitted
         * rate — six times the desktop's floor on a typical run, with both labelled "1x".
         */
        val SPEEDS: List<Double> = listOf(0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 25.0, 50.0, 100.0)

        /** How long a viewer aims to take playing a whole run when it picks a speed for you. */
        const val DEFAULT_TARGET_SECONDS: Double = 25.0

        /**
         * A tidy speed that plays a run of [span] simulated units in about [targetSeconds] real ones.
         *
         * Rounded to a 1/2/5 x 10^n value so the read-out is something a person would have chosen, and
         * floored at the slowest offered speed so a very short run does not select a rate below the list.
         * Shared so the desktop and the browser cannot disagree about what a run should look like.
         */
        fun autoSpeedFor(span: Double, targetSeconds: Double = DEFAULT_TARGET_SECONDS): Double {
            if (span <= 0.0 || targetSeconds <= 0.0) return 1.0
            val desired = span / targetSeconds
            if (desired <= 0.0) return 1.0
            val magnitude = kotlin.math.floor(kotlin.math.log10(desired)).let { e -> tenTo(e) }
            val normalized = desired / magnitude
            val nice = when {
                normalized < 1.5 -> 1.0
                normalized < 3.5 -> 2.0
                normalized < 7.5 -> 5.0
                else -> 10.0
            } * magnitude
            return maxOf(nice, SPEEDS.first())
        }

        /** 10^[e]. Written as a receiver call because this file is compiled for Kotlin/JS as well. */
        private fun tenTo(e: Double): Double = 10.0.pow(e)
    }
}
