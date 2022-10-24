/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
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
package ksl.utilities.math

import kotlin.math.abs

/**
 * Continued fraction
 *
 */
abstract class ContinuedFraction(maxIter: Int = 100, desiredPrec: Double = KSLMath.defaultNumericalPrecision) :
    DBHIterativeProcess(maxIter, desiredPrec) {

    /**
     * Best approximation of the fraction.
     */
    var result = 0.0
        private set

    /**
     * Fraction's argument.
     */
    protected var x = 0.0

    /**
     * @param r double	the value of the series argument.
     */
    fun setArgument(r: Double) {//TODO
        x = r
    }

    /**
     * Fraction's accumulated numerator.
     */
    private var numerator = 0.0

    /**
     * Fraction's accumulated denominator.
     */
    private var denominator = 0.0

    /**
     * Fraction's next factors.
     */
    protected var factors = DoubleArray(2)

    /**
     * Compute the pair numerator/denominator for iteration n.
     * @param n int
     */
    protected abstract fun computeFactorsAt(n: Int)

    /**
     * @return double
     */
    public override fun evaluateIteration(): Double {
        computeFactorsAt(iterationsExecuted)
        denominator = 1 / limitedSmallValue(
            factors[0] * denominator
                    + factors[1]
        )
        numerator = limitedSmallValue(factors[0] / numerator + factors[1])
        val delta = numerator * denominator
        result = result * delta
        return abs(delta - 1)
    }

    override fun initializeIterations() {
        numerator = limitedSmallValue(initialValue())
        denominator = 0.0
        result = numerator
    }

    /**
     * @return double
     */
    protected abstract fun initialValue(): Double

    /**
     * Protection against small factors.
     * @return double
     * @param r double
     */
    private fun limitedSmallValue(r: Double): Double {
        return if (abs(r) < KSLMath.smallNumber) KSLMath.smallNumber else r
    }

}