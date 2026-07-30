/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

import kotlin.math.abs

/**
 *  The outcome of a metalog feasibility check.
 *
 *  When the coefficients are not feasible, the worst probability locates where the quantile
 *  function was least increasing. That is worth surfacing, because it explains why a parameter
 *  estimation failed and indicates which tail is responsible.
 *
 *  @param feasible true when the quantile derivative was strictly positive everywhere checked
 *  @param minimumDerivative the smallest quantile derivative found
 *  @param worstProbability the probability at which the smallest derivative occurred
 */
data class MetalogFeasibilityResult(
    val feasible: Boolean,
    val minimumDerivative: Double,
    val worstProbability: Double
)

/**
 *  Checks whether a metalog coefficient vector defines a valid quantile function.
 *
 *  A metalog is a valid distribution exactly when its quantile function is strictly increasing,
 *  that is, when the quantile derivative is positive throughout the open interval from zero to
 *  one. This is a property of the fitted coefficients rather than a constraint that can be
 *  imposed on them in advance, so it has to be verified after the fact. Nothing about a data
 *  set guarantees that a least squares fit to it will be feasible.
 *
 *  For two and three terms the condition is available in closed form and the companion
 *  functions should be preferred. For four or more terms no closed form exists and the
 *  derivative is scanned on a grid. A uniform grid alone under-resolves the tails, which is
 *  where violations tend to hide, so the grid combines uniform interior spacing with geometric
 *  refinement approaching each endpoint.
 *
 *  Feasibility of the underlying metalog is necessary and sufficient for every member of the
 *  family, because each boundedness transform contributes a strictly positive density factor.
 *
 *  @param uniformStep the spacing of the uniform portion of the grid, which must be in (0, 0.5)
 *  @param tailDecades how many powers of ten of refinement to add approaching each endpoint
 */
class MetalogFeasibilityChecker(
    val uniformStep: Double = DEFAULT_UNIFORM_STEP,
    val tailDecades: Int = DEFAULT_TAIL_DECADES
) {

    private val myGrid: DoubleArray

    init {
        require(uniformStep > 0.0) { "The uniform step $uniformStep must be positive" }
        require(uniformStep < 0.5) { "The uniform step $uniformStep must be less than 0.5" }
        require(tailDecades >= 1) { "The number of tail decades $tailDecades must be at least 1" }
        myGrid = buildGrid()
    }

    /**
     *  How many probabilities the grid contains.
     */
    val gridSize: Int
        get() = myGrid.size

    /**
     *  A defensive copy of the probabilities at which the derivative is evaluated.
     */
    fun grid(): DoubleArray = myGrid.copyOf()

    /**
     *  True when the supplied coefficients define a valid quantile function. Two- and
     *  three-term coefficient vectors take the exact closed-form route.
     */
    fun isFeasible(coefficients: DoubleArray): Boolean {
        require(coefficients.size >= MetalogFunctions.MIN_TERMS) {
            "There must be at least ${MetalogFunctions.MIN_TERMS} coefficients"
        }
        if (coefficients.any { !it.isFinite() }) {
            return false
        }
        return when (coefficients.size) {
            2 -> isFeasible2Term(coefficients[1])
            3 -> isFeasible3Term(coefficients[1], coefficients[2])
            else -> check(coefficients).feasible
        }
    }

    /**
     *  Scans the grid and reports the smallest quantile derivative found along with where it
     *  occurred. Unlike `isFeasible`, this always performs the scan, so it can be used to
     *  inspect how much margin a feasible fit has.
     */
    fun check(coefficients: DoubleArray): MetalogFeasibilityResult {
        require(coefficients.size >= MetalogFunctions.MIN_TERMS) {
            "There must be at least ${MetalogFunctions.MIN_TERMS} coefficients"
        }
        if (coefficients.any { !it.isFinite() }) {
            return MetalogFeasibilityResult(false, Double.NaN, Double.NaN)
        }
        var minimum = Double.POSITIVE_INFINITY
        var worst = myGrid[0]
        for (y in myGrid) {
            val derivative = MetalogFunctions.quantileDerivative(coefficients, y)
            if (derivative.isNaN()) {
                return MetalogFeasibilityResult(false, Double.NaN, y)
            }
            if (derivative < minimum) {
                minimum = derivative
                worst = y
            }
        }
        return MetalogFeasibilityResult(minimum > 0.0, minimum, worst)
    }

    /**
     *  Builds the union of a uniform interior grid and geometrically refined tail points. Tail
     *  points that the uniform spacing already resolves are omitted, and the result is sorted
     *  and free of duplicates.
     */
    private fun buildGrid(): DoubleArray {
        val points = sortedSetOf<Double>()
        var y = uniformStep
        while (y < 1.0) {
            points.add(y)
            y += uniformStep
        }
        for (decade in 2..tailDecades) {
            val tail = Math.pow(10.0, -decade.toDouble())
            if (tail < uniformStep) {
                points.add(tail)
                points.add(1.0 - tail)
            }
        }
        // Guard against a rounding artifact putting a point at or outside the open interval.
        points.removeIf { (it <= 0.0) || (it >= 1.0) }
        return points.toDoubleArray()
    }

    companion object {

        /**
         *  The default uniform spacing. Measured against a dense reference over random
         *  coefficient vectors, this resolution produced no false acceptances, whereas a
         *  spacing of 0.01 did.
         */
        const val DEFAULT_UNIFORM_STEP: Double = 0.001

        /**
         *  The default number of powers of ten of tail refinement.
         */
        const val DEFAULT_TAIL_DECADES: Int = 9

        /**
         *  The three-term feasibility limit on the magnitude of the ratio of the third
         *  coefficient to the second, from Keelin (2016) Proposition 2. It is the minimum over
         *  the lower half of the probability interval of the function Keelin writes as C(y).
         */
        const val THREE_TERM_RATIO_LIMIT: Double = 1.66711

        /**
         *  The exact two-term condition. A two-term metalog is a logistic distribution whose
         *  scale is the second coefficient, so it is valid exactly when that scale is positive.
         */
        fun isFeasible2Term(a2: Double): Boolean {
            return a2.isFinite() && (a2 > 0.0)
        }

        /**
         *  The exact three-term condition from Keelin (2016) Proposition 2. The second
         *  coefficient must be positive and the ratio of the third to the second must be
         *  smaller in magnitude than the limiting value.
         */
        fun isFeasible3Term(a2: Double, a3: Double): Boolean {
            if (!a2.isFinite() || !a3.isFinite()) {
                return false
            }
            if (a2 <= 0.0) {
                return false
            }
            return abs(a3 / a2) < THREE_TERM_RATIO_LIMIT
        }

        /**
         *  A checker built with the default grid, suitable for sharing because it holds no
         *  mutable state.
         */
        val defaultChecker: MetalogFeasibilityChecker by lazy { MetalogFeasibilityChecker() }
    }
}
