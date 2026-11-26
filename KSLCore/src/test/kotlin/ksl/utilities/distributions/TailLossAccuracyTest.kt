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
package ksl.utilities.distributions

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln1p
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  Loss functions far in the upper tail: between whole numbers they keep their relative accuracy, and where the
 *  support cannot be represented they refuse rather than return a wrong value.
 */
class TailLossAccuracyTest {

    /** The order-[order] loss at [x] summed directly over the mass above it, from its definition. */
    private fun directLoss(d: Poisson, x: Double, order: Int): Double = (41..200).sumOf { j ->
        val a = maxOf(j - x, 0.0)
        val b = maxOf(j - x - 1.0, 0.0)
        val c = maxOf(j - x - 2.0, 0.0)
        val ways = when (order) {
            1 -> a
            2 -> a * b / 2.0
            else -> a * b * c / 6.0
        }
        ways * d.pmf(j)
    }

    @Test
    @DisplayName("a Poisson's loss functions between whole numbers far in the tail match the direct sums")
    fun poissonFractionalTail() {
        val d = Poisson(6.0)
        val x = 40.5
        val actual = listOf(d.firstOrderLossFunction(x), d.secondOrderLossFunction(x), d.thirdOrderLossFunction(x))
        for (order in 1..3) {
            val expected = directLoss(d, x, order)
            val relative = abs(actual[order - 1] / expected - 1.0)
            assertTrue(actual[order - 1] > 0.0, "order $order: ${actual[order - 1]} is not positive")
            assertTrue(relative < 1e-9, "order $order: ${actual[order - 1]} against $expected, relative error $relative")
        }
    }

    @Test
    @DisplayName("a negative binomial too wide to sum over its tail, just below the largest point computed, matches the geometric tail")
    fun negativeBinomialNearTheLargestWholeNumber() {
        val p = 1e-9
        // the closed form serves a distribution this wide; its direct algorithms keep that quick so near the limit
        val d = NegativeBinomial(p, 1.0).also { it.recursiveAlgorithmFlag = false }
        val x = Int.MAX_VALUE - 3.0
        // r = 1 is geometric: G1(x) = q^(x+1)/p
        val expected = exp((x + 1) * ln1p(-p)) / p
        // to the accuracy of the incomplete beta function the direct algorithms use
        assertEquals(1.0, d.firstOrderLossFunction(x) / expected, 1e-5)
    }

    @Test
    @DisplayName("a loss function whose next point is beyond the largest whole number refuses rather than returning a wrong value")
    fun beyondTheLargestWholeNumber() {
        val d = NegativeBinomial(1e-9, 1.0)
        assertFailsWith<IllegalArgumentException> { d.firstOrderLossFunction(Int.MAX_VALUE.toDouble()) }
    }
}
