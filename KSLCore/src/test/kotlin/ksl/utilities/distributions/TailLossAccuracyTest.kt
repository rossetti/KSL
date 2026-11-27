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
    @DisplayName("a loss function past the largest whole number its mass function can reach refuses rather than returning a wrong value")
    fun beyondTheLargestWholeNumber() {
        // a narrow distribution, so only the range refuses: the largest argument computed is Int.MAX_VALUE - 5
        val d = Poisson(6.0)
        assertFailsWith<IllegalArgumentException> { d.firstOrderLossFunction(Int.MAX_VALUE - 4.0) }
        assertFailsWith<IllegalArgumentException> { d.thirdOrderLossFunction(Int.MAX_VALUE.toDouble()) }
    }

    @Test
    @DisplayName("a wide negative binomial far into its tail matches the geometric tail, at every order")
    fun wideNegativeBinomialDeepInTheTail() {
        // r = 1 is geometric: Gk(x) = q^(x+k)/p^k; mean 1e6, standard deviation about 1e6
        val p = 1e-6
        val d = NegativeBinomial(p, 1.0)
        val x = 4.5e7
        // the sum steps the mass through tens of millions of multiplications, so rounding gathers to about 1e-8
        assertEquals(1.0, d.firstOrderLossFunction(x) / (exp((x + 1) * ln1p(-p)) / p), 1e-6)
        assertEquals(1.0, d.secondOrderLossFunction(x) / (exp((x + 2) * ln1p(-p)) / (p * p)), 1e-6)
        assertEquals(1.0, d.thirdOrderLossFunction(x) / (exp((x + 3) * ln1p(-p)) / (p * p * p)), 1e-6)
    }

    @Test
    @DisplayName("a negative binomial too wide to sum over its tail refuses rather than return a closed form's inaccurate value")
    fun tooWideIsRefused() {
        // success probability 1e-8: about 4e9 terms would be needed, known before summing; and one so small that
        // 1 - p rounds to 1
        assertFailsWith<IllegalArgumentException> { NegativeBinomial(1e-17, 1e-8).firstOrderLossFunction(1.5e9) }
        val narrowSuccess = NegativeBinomial(1e-8, 1.0)
        assertFailsWith<IllegalArgumentException> { narrowSuccess.firstOrderLossFunction(1.1e8) }
        assertFailsWith<IllegalArgumentException> { narrowSuccess.secondOrderLossFunction(1e9) }
        assertFailsWith<IllegalArgumentException> { narrowSuccess.thirdOrderLossFunction(1.5e8) }
        // many successes: the estimate passes, but the sum does not settle within its limit
        val manySuccesses = NegativeBinomial(2.1e-7, 20.0)
        assertFailsWith<IllegalArgumentException> { manySuccesses.firstOrderLossFunction(manySuccesses.mean().toInt() + 1.0) }
    }

    @Test
    @DisplayName("every loss function is zero at infinity, infinite at minus infinity, and refuses NaN or a point below the range")
    fun nonFiniteArguments() {
        for (d in listOf(Poisson(6.0), NegativeBinomial(0.3, 2.0), Binomial(0.4, 30), Geometric(0.3))) {
            val losses = listOf<(Double) -> Double>(
                { d.firstOrderLossFunction(it) }, { d.secondOrderLossFunction(it) }, { d.thirdOrderLossFunction(it) }
            )
            for ((k, g) in losses.withIndex()) {
                assertEquals(0.0, g(Double.POSITIVE_INFINITY), "$d order ${k + 1} at infinity")
                assertEquals(Double.POSITIVE_INFINITY, g(Double.NEGATIVE_INFINITY), "$d order ${k + 1} at minus infinity")
                assertFailsWith<IllegalArgumentException>("$d order ${k + 1} at NaN") { g(Double.NaN) }
                assertFailsWith<IllegalArgumentException>("$d order ${k + 1} below the range") { g(-3e9) }
            }
        }
    }
}
