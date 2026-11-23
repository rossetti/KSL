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
        // exactly, as the direct formula gives it for small values
        assertEquals(2.0, estimate(1.0).merge(estimate(3.0)).variance)
    }

    @Test
    @DisplayName("a pooled variance that fits in a Double is computed without overflowing on the way")
    fun largeButRepresentableVariance() {
        // four observations of 0 and four of 1e154: mean 5e153, sample variance 8 (5e153)^2 / 7
        val merged = EstimatedResponse("x", 0.0, 0.0, 4.0).merge(EstimatedResponse("x", 1.0e154, 0.0, 4.0))
        val expected = (8.0 / 7.0) * 5.0e153 * 5.0e153
        assertEquals(5.0e153, merged.average, 5.0e153 * 1e-14)
        assertEquals(expected, merged.variance, expected * 1e-12)
    }

    @Test
    @DisplayName("merging is symmetric, also with unequal counts and large close means")
    fun symmetricWithUnequalCounts() {
        val a = EstimatedResponse("x", 1.0e150, 4.0e298, 3.0)
        val b = EstimatedResponse("x", 1.0e150 + 2.0e149, 9.0e298, 11.0)
        val ab = a.merge(b)
        val ba = b.merge(a)
        assertEquals(ab.average, ba.average, ab.average * 1e-14)
        assertEquals(ab.variance, ba.variance, ab.variance * 1e-12)
        // (2 * 4e298 + 10 * 9e298 + (3 * 11 / 14) * (2e149)^2) / 13
        val expected = (2.0 * 4.0e298 + 10.0 * 9.0e298 + (33.0 / 14.0) * 4.0e298) / 13.0
        assertEquals(expected, ab.variance, expected * 1e-12)
    }

    @Test
    @DisplayName("a pooled variance too large for a Double is infinite")
    fun unrepresentableVarianceIsInfinite() {
        val merged = EstimatedResponse("x", 0.0, 0.0, 4.0).merge(EstimatedResponse("x", 1.0e200, 0.0, 4.0))
        assertEquals(Double.POSITIVE_INFINITY, merged.variance)
    }
}
