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

/**
 * An iterative process is a general structure managing iterations.
 *
 * * This is based on the IterativeProcess class of Didier Besset in
 * "Object-Oriented Implementation of Numerical Methods", Morgan-Kaufmann
 */
abstract class DBHIterativeProcess(maxIter: Int = 100, desiredPrec: Double = KSLMath.defaultNumericalPrecision) {

    init {
        require(maxIter >= 1) { "The maximum number of iterations must be >= 1: $maxIter" }
        require(desiredPrec > 0) {"The desired precision must be > 0: $desiredPrec" }
    }

    /**
     * Maximum allowed number of iterations.
     */
    var maximumIterations = maxIter
        set(value) {
            require(value >= 1) { "The maximum number of iterations must be >= 1: $value" }
            field = value
        }

    /**
     * Desired precision.
     */
    var desiredPrecision = desiredPrec
        set(value) {
            require(value > 0) { "The desired precision must be > 0: $value" }
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