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

package ksl.app.swing.animation.replay

/**
 * A step function over time: a series of `(time, value)` samples that hold their value until the
 * next sample. Values are appended in non-decreasing time order (the trace is time-ordered), and
 * [valueAt] is an O(log n) binary search for the value in effect at a query time — the basis for
 * smooth scrubbing to any time.
 */
class StepTimeline<V> {
    private val times = ArrayList<Double>()
    private val values = ArrayList<V>()

    val size: Int get() = times.size
    fun isEmpty(): Boolean = times.isEmpty()
    val firstTime: Double? get() = times.firstOrNull()
    val lastTime: Double? get() = times.lastOrNull()

    /** Appends a sample. [time] must be >= the last appended time. */
    fun add(time: Double, value: V) {
        times.add(time)
        values.add(value)
    }

    /**
     * The value in effect at [t]: the value of the last sample with time <= [t], or `null` if [t]
     * precedes the first sample (or the timeline is empty).
     */
    fun valueAt(t: Double): V? {
        if (times.isEmpty() || t < times[0]) return null
        var lo = 0
        var hi = times.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= t) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return values[ans]
    }

    /** The `(time, value)` samples with time <= [t], in order (for drawing a plot up to [t]). */
    fun samplesUpTo(t: Double): List<Pair<Double, V>> {
        val result = ArrayList<Pair<Double, V>>()
        for (i in times.indices) {
            if (times[i] > t) break
            result.add(times[i] to values[i])
        }
        return result
    }
}
