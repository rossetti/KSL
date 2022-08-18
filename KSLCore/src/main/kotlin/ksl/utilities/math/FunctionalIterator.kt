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

//TODO look more at using Kotlin functions

/**
 * Iterative process based on a one-variable function, having a single numerical result.
 *
 */
abstract class FunctionalIterator(
    aFunction: FunctionIfc,
    maxIterations: Int = 100,
    desiredPrecision: Double = KSLMath.defaultNumericalPrecision
) : DBHIterativeProcess(maxIterations, desiredPrecision) {

    var func:FunctionIfc = aFunction // this means that func is a changeable property that must not be null
        protected set

    /**
     * Returns the result (assuming convergence has been attained).
     *
     */
    var result = Double.NaN // a public getter and a protected setter
        protected set

    /**
     * @param epsilon double
     * @return relative precision
     */
    fun relativePrecision(epsilon: Double): Double {
        return relativePrecision(epsilon, abs(result))
    }
}