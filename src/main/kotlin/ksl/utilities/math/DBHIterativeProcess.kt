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

/**
 * An iterative process is a general structure managing iterations.
 *
 * * This is based on the IterativeProcess class of Didier Besset in
 * "Object-Oriented Implementation of Numerical Methods", Morgan-Kaufmann
 */
abstract class DBHIterativeProcess(maxIter: Int = 100, desiredPrec: Double = KSLMath.defaultNumericalPrecision) {

    init {
        require(maxIter >= 1) { "Non-positive maximum iteration: $maxIter" }
        require(desiredPrec > 0) {"Non-positive desired precision: $desiredPrec" }
    }

    /**
     * Maximum allowed number of iterations.
     */
    var maximumIterations = maxIter
        set(value) {
            require(value >= 1) { "Non-positive maximum iteration: $value" }
            field = value
        }

    /**
     * Desired precision.
     */
    var desiredPrecision = desiredPrec
        set(value) {
            require(value > 0) { "Non-positive precision: $value" }
            field = value
        }

    /**
     * Number of iterations performed.
     */
    var iterationsExecuted = 0
        private set

    /**
     * The achieved precision.
     */
    var achievedPrecision = Double.MAX_VALUE
        private set

    /**
     * Check to see if the result has been attained.
     * @return boolean
     */
    fun hasConverged() = achievedPrecision < desiredPrecision

    /**
     * @param epsilon double
     * @param x double
     * @return double
     */
    fun relativePrecision(epsilon: Double, x: Double): Double {
        return if (x > KSLMath.defaultNumericalPrecision) epsilon / x else epsilon
    }

    /**
     * Performs the iterative process.
     * Note: this method does not return anything
     * Subclass must implement a method to get the result
     */
    fun evaluate() {
        iterationsExecuted = 0
        initializeIterations()
        while (iterationsExecuted++ < maximumIterations) {
            achievedPrecision = evaluateIteration()
            if (hasConverged()) break
        }
        finalizeIterations()
    }

    /**
     * Evaluate the result of the current iteration.
     * @return the estimated precision of the result.
     */
    protected abstract fun evaluateIteration(): Double

    /**
     * Perform eventual clean-up operations
     * (must be implemented by subclass when needed).
     */
    protected abstract fun finalizeIterations()

    /**
     * Initializes internal parameters to start the iterative process.
     */
    protected abstract fun initializeIterations()


}