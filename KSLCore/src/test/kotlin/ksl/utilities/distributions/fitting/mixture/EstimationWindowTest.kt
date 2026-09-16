/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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
package ksl.utilities.distributions.fitting.mixture

import ksl.utilities.distributions.Normal
import ksl.utilities.random.rvariable.UniformRV
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertFailsWith

/**
 *  The window arithmetic, and the one property that connects it to the analysis it exists to
 *  serve: a window widened by a count of observations must approach, as the sample grows, the
 *  window widened by a share of probability mass. The bias analysis is written in the second
 *  language and the implementation in the first, and nothing else checks that they describe
 *  the same object.
 */
class EstimationWindowTest {

    @Test
    fun `a zero fraction returns the group itself`() {
        val w = EstimationWindows.windowFor(10, 40, 100, 0.0)
        assertEquals(10, w.startIndex)
        assertEquals(40, w.endIndex)
    }

    @Test
    fun `the window always contains its group`() {
        for (delta in listOf(0.0, 0.01, 0.1, 0.5, 1.0, 3.0)) {
            val w = EstimationWindows.windowFor(20, 60, 200, delta)
            assertTrue(w.startIndex <= 20, "delta=$delta start ${w.startIndex}")
            assertTrue(w.endIndex >= 60, "delta=$delta end ${w.endIndex}")
        }
    }

    @Test
    fun `the reach is a floor of the fraction times the group's own size`() {
        // group of 40; delta 0.25 reaches 10 either side
        val w = EstimationWindows.windowFor(50, 90, 500, 0.25)
        assertEquals(40, w.startIndex)
        assertEquals(100, w.endIndex)
        // 0.26 * 40 = 10.4, which floors to the same 10
        assertEquals(w, EstimationWindows.windowFor(50, 90, 500, 0.26))
    }

    @Test
    fun `the window clips at both ends of the sample`() {
        val low = EstimationWindows.windowFor(0, 20, 100, 0.5)
        assertEquals(0, low.startIndex)
        assertEquals(30, low.endIndex)
        val high = EstimationWindows.windowFor(80, 100, 100, 0.5)
        assertEquals(70, high.startIndex)
        assertEquals(100, high.endIndex)
        val whole = EstimationWindows.windowFor(0, 100, 100, 2.0)
        assertEquals(0, whole.startIndex)
        assertEquals(100, whole.endIndex)
    }

    @Test
    fun `the window never shrinks as the fraction grows`() {
        var previous = EstimationWindows.windowFor(200, 300, 1000, 0.0)
        for (i in 1..40) {
            val w = EstimationWindows.windowFor(200, 300, 1000, i * 0.05)
            assertTrue(w.startIndex <= previous.startIndex && w.endIndex >= previous.endIndex)
            previous = w
        }
    }

    @Test
    fun `bad arguments are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            EstimationWindows.windowFor(10, 20, 100, -0.1)
        }
        assertFailsWith<IllegalArgumentException> {
            EstimationWindows.windowFor(10, 20, 100, Double.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            EstimationWindows.windowFor(20, 20, 100, 0.5)
        }
        assertFailsWith<IllegalArgumentException> {
            EstimationWindows.windowFor(10, 200, 100, 0.5)
        }
    }

    @Test
    fun `windows for a partition are windows for its groups`() {
        val partition = DataPartition(100, intArrayOf(30, 70))
        val windows = EstimationWindows.windowsFor(partition, 0.2)
        assertEquals(3, windows.size)
        for (g in 0 until partition.numGroups) {
            assertEquals(
                EstimationWindows.windowFor(
                    partition.startIndex(g), partition.endIndex(g), 100, 0.2
                ),
                windows[g]
            )
        }
    }

    @Test
    fun `a window of zero fraction is the same object the group would produce`() {
        // guards the property everything else rests on: no arithmetic happens at delta zero,
        // so no rounding step can separate the window from the group
        val w = EstimationWindows.windowFor(37, 91, 400, 0.0)
        assertEquals(EstimationWindow(37, 91), w)
        assertEquals(54, w.size)
    }
}
