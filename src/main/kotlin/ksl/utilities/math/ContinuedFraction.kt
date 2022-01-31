/*
 * Copyright (c) 2018. Manuel D. Rossetti, rossetti@uark.edu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
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