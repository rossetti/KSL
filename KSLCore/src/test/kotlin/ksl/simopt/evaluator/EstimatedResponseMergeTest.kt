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

package ksl.simopt.evaluator

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  Merging two estimates of the same response gives the estimate one sample of all the observations would give,
 *  however the observations were split.
 */
class EstimatedResponseMergeTest {

    /** The estimate of [xs] as one sample: its average, sample variance (divisor n - 1, NaN for one value) and count. */
    private fun estimate(vararg xs: Double): EstimatedResponse {
        val n = xs.size.toDouble()
        val avg = xs.average()
        val v = if (xs.size < 2) Double.NaN else xs.sumOf { (it - avg) * (it - avg) } / (n - 1.0)
        return EstimatedResponse("Y", avg, v, n)
    }

    @Test
    @DisplayName("every split of the same observations merges to the sample variance of all of them")
    fun mergeDoesNotDependOnTheSplit() {
        val all = estimate(1.0, 2.0, 9.0, 10.0)
        val splits = listOf(
            estimate(1.0, 2.0) to estimate(9.0, 10.0),
            estimate(1.0) to estimate(2.0, 9.0, 10.0),
            estimate(1.0, 2.0, 9.0) to estimate(10.0),
            estimate(1.0, 9.0) to estimate(2.0, 10.0)
        )
        for ((a, b) in splits) {
            val merged = a.merge(b)
            assertEquals(all.count, merged.count, "count of $a + $b")
            assertEquals(all.average, merged.average, 1e-12, "average of $a + $b")
            assertEquals(all.variance, merged.variance, 1e-12, "variance of $a + $b")
        }
    }

    @Test
    @DisplayName("two single observations merge to their sample variance")
    fun twoSingleObservations() {
        val merged = estimate(3.0).merge(estimate(7.0))
        assertEquals(estimate(3.0, 7.0).variance, merged.variance, 1e-12)
    }
}
