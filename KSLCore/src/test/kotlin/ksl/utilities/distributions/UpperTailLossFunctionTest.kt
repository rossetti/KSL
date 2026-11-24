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
import kotlin.math.sqrt
import kotlin.test.assertTrue

/**
 *  The loss functions of the Poisson, the negative binomial and the binomial are expectations of non-negative quantities that
 *  do not increase with x, and they keep to the sum that defines them far into the upper tail, where their closed
 *  forms subtract nearly equal terms.
 */
class UpperTailLossFunctionTest {

    private val poisson = Poisson(6000.0 * 2.0 / 52.0)
    private val negBin = NegativeBinomial(probSuccess = 0.2, numSuccesses = 250.0)
    private val binomial = Binomial(pSuccess = 0.5, nTrials = 200)

    /** G_k(x) summed from its definition: the sum over j greater than x of C(j - x, k) p(j). */
    private fun reference(pmf: (Int) -> Double, x: Int, order: Int, mean: Double): Double {
        var total = 0.0
        var j = x + 1
        while (true) {
            val n = (j - x).toDouble()
            val c = when (order) {
                1 -> n
                2 -> n * (n - 1.0) / 2.0
                else -> n * (n - 1.0) * (n - 2.0) / 6.0
            }
            val term = c * pmf(j)
            total += term
            // the first terms of a higher order are 0: stop only once the terms have begun and stopped counting
            if (j > mean && n > order && (term == 0.0 || term < total * 1e-18)) break
            if (j > 1_000_000) break
            j++
        }
        return total
    }

    private fun lossOf(d: ThirdOrderLossFunctionIfc, order: Int, x: Double): Double = when (order) {
        1 -> (d as LossFunctionDistributionIfc).firstOrderLossFunction(x)
        2 -> (d as LossFunctionDistributionIfc).secondOrderLossFunction(x)
        else -> d.thirdOrderLossFunction(x)
    }

    private fun check(name: String, d: ThirdOrderLossFunctionIfc, pmf: (Int) -> Double, mean: Double, sd: Double, farPoints: List<Int>) {
        // below the mean, where the closed forms apply, and above it, near and far
        val points = ((-3..8).map { (mean + it * sd).toInt() } + farPoints).distinct().sorted()
        for (order in 1..3) {
            var previous = Double.POSITIVE_INFINITY
            for (x in points) {
                val value = lossOf(d, order, x.toDouble())
                val ref = reference(pmf, x, order, mean)
                assertTrue(value >= 0.0, "$name G$order($x) = $value is negative")
                assertTrue(value <= previous, "$name G$order($x) = $value is above the value before it, $previous")
                assertTrue(abs(value - ref) <= 1e-9 * ref + 1e-300, "$name G$order($x) = $value, by its definition $ref")
                previous = value
            }
        }
    }

    @Test
    @DisplayName("Poisson: G1, G2 and G3 keep to their definition far into the upper tail")
    fun poissonUpperTail() {
        check("Poisson", poisson, { poisson.pmf(it) }, poisson.mean, sqrt(poisson.mean), listOf(300, 400, 826, 2000))
    }

    @Test
    @DisplayName("Negative binomial: G1, G2 and G3 keep to their definition far into the upper tail")
    fun negativeBinomialUpperTail() {
        check("NegativeBinomial", negBin, { negBin.pmf(it) }, negBin.mean(), sqrt(negBin.variance()), listOf(2000, 3000, 5000))
    }

    @Test
    @DisplayName("Binomial: G1, G2 and G3 keep to their definition far into the upper tail, and are 0 from n on")
    fun binomialUpperTail() {
        check("Binomial", binomial, { binomial.pmf(it) }, binomial.mean(), sqrt(binomial.variance()),
            listOf(130, 150, 199, 200, 250))
    }
}
