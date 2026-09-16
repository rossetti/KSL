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

import ksl.utilities.distributions.fitting.estimators.NormalMLEParameterEstimator
import ksl.utilities.distributions.fitting.estimators.ParameterEstimatorIfc
import ksl.utilities.random.rvariable.NormalRV
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 *  The decorator. Two properties carry the design: a zero fraction must delegate unchanged,
 *  and a positive fraction must widen the range handed to the wrapped fitter while leaving the
 *  range reported back describing the group.
 */
class WindowedComponentFitterTest {

    /** Records the ranges it was asked to fit, and otherwise delegates. */
    private class RecordingFitter(
        private val inner: ComponentFitterIfc
    ) : ComponentFitterIfc {
        val ranges = mutableListOf<Pair<Int, Int>>()
        override fun fitGroup(
            sortedData: DoubleArray, startIndex: Int, endIndex: Int
        ): GroupFitResult {
            ranges.add(startIndex to endIndex)
            return inner.fitGroup(sortedData, startIndex, endIndex)
        }
    }

    private val estimators: Set<ParameterEstimatorIfc> = setOf(NormalMLEParameterEstimator)

    private fun sample(n: Int = 400): DoubleArray {
        val low = NormalRV(10.0, 1.0, streamNum = 71)
        val high = NormalRV(20.0, 1.0, streamNum = 72)
        return DoubleArray(n) { if (it % 2 == 0) low.value else high.value }.sortedArray()
    }

    @Test
    fun `a zero fraction delegates the group's own range`() {
        val recorder = RecordingFitter(PDFComponentFitter(estimators))
        val windowed = WindowedComponentFitter(recorder, 0.0)
        windowed.fitGroup(sample(), 100, 300)
        assertEquals(listOf(100 to 300), recorder.ranges)
    }

    @Test
    fun `a zero fraction returns exactly what the wrapped fitter returns`() {
        val data = sample()
        val plain = PDFComponentFitter(estimators)
        val direct = plain.fitGroup(data, 100, 300)
        val windowed = WindowedComponentFitter(PDFComponentFitter(estimators), 0.0)
            .fitGroup(data, 100, 300)
        assertEquals(direct.startIndex, windowed.startIndex)
        assertEquals(direct.endIndex, windowed.endIndex)
        assertEquals(direct.candidates.size, windowed.candidates.size)
        for (i in direct.candidates.indices) {
            assertEquals(
                direct.candidates[i].distribution.parameters().toList(),
                windowed.candidates[i].distribution.parameters().toList(),
                "candidate $i parameters"
            )
        }
    }

    @Test
    fun `a positive fraction widens the range handed to the wrapped fitter`() {
        val recorder = RecordingFitter(PDFComponentFitter(estimators))
        val windowed = WindowedComponentFitter(recorder, 0.25)
        windowed.fitGroup(sample(), 100, 300)
        // group of 200, so the window reaches 50 either side
        assertEquals(listOf(50 to 350), recorder.ranges)
    }

    @Test
    fun `the result describes the group, not the window`() {
        val windowed = WindowedComponentFitter(PDFComponentFitter(estimators), 0.25)
        val fit = windowed.fitGroup(sample(), 100, 300)
        assertEquals(100, fit.startIndex)
        assertEquals(300, fit.endIndex)
        assertEquals(200, fit.groupSize)
    }

    @Test
    fun `a wider window changes the estimate`() {
        // the point of the exercise: the fit must actually differ, or nothing has happened
        val data = sample()
        val narrow = WindowedComponentFitter(PDFComponentFitter(estimators), 0.0)
            .fitGroup(data, 100, 300).candidates.first().distribution.parameters()
        val wide = WindowedComponentFitter(PDFComponentFitter(estimators), 0.25)
            .fitGroup(data, 100, 300).candidates.first().distribution.parameters()
        assertNotEquals(narrow.toList(), wide.toList())
    }

    @Test
    fun `the window clips rather than running off the sample`() {
        val recorder = RecordingFitter(PDFComponentFitter(estimators))
        val windowed = WindowedComponentFitter(recorder, 0.5)
        val data = sample(400)
        windowed.fitGroup(data, 0, 100)
        windowed.fitGroup(data, 300, 400)
        assertEquals(0 to 150, recorder.ranges[0])
        assertEquals(250 to 400, recorder.ranges[1])
    }

    @Test
    fun `it reports the window it would use without fitting`() {
        val windowed = WindowedComponentFitter(PDFComponentFitter(estimators), 0.25)
        assertEquals(EstimationWindow(50, 350), windowed.windowFor(100, 300, 400))
    }

    @Test
    fun `a negative or non-finite fraction is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            WindowedComponentFitter(PDFComponentFitter(estimators), -0.1)
        }
        assertFailsWith<IllegalArgumentException> {
            WindowedComponentFitter(PDFComponentFitter(estimators), Double.NaN)
        }
    }

    @Test
    fun `caching outside the decorator keeps its hit rate`() {
        // the arrangement the class documents: the cache sees assignment ranges, which repeat
        // during refinement, so widening costs no hits
        val data = sample()
        val cached = ComponentFitCache(
            WindowedComponentFitter(PDFComponentFitter(estimators), 0.25)
        )
        repeat(5) { cached.fitGroup(data, 100, 300) }
        assertEquals(4, cached.hits)
        assertEquals(1, cached.misses)
        assertTrue(cached.hitRate > 0.79)
    }
}
