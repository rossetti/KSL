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