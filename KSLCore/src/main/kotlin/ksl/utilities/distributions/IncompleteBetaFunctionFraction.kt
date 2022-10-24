/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
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

import ksl.utilities.math.ContinuedFraction

/**
 *
 */
class IncompleteBetaFunctionFraction : ContinuedFraction() {
    private var alpha1 = 0.0
    private var alpha2 = 0.0
    fun evaluateFraction(x: Double, a1: Double, a2: Double): Double {
        alpha1 = a1
        alpha2 = a2
        setArgument(x)
        evaluate()
        return result
    }

    /**
     * Compute the pair numerator/denominator for iteration n.
     * @param n int
     */
    override fun computeFactorsAt(n: Int) {
        val m = n / 2
        val m2 = 2 * m
        if (m2 == n) {
            factors[0] = x * m * (alpha2 - m) / ((alpha1 + m2) * (alpha1 + m2 - 1))
        } else {
            factors[0] = -x * (alpha1 + m) * (alpha1 + alpha2 + m) / ((alpha1 + m2) * (alpha1 + m2 + 1))
        }
    }

    override fun initialValue(): Double {
        factors[1] = 1.0
        return 1.0
    }

    override fun finalizeIterations() {}
}