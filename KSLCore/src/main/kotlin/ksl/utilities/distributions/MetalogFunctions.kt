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

import kotlin.math.ln
import kotlin.math.pow

/**
 *  The basis functions underlying the metalog quantile function of Keelin (2016).
 *
 *  The metalog quantile function is a linear combination of basis terms in the cumulative
 *  probability y. Writing L for the logit ln(y/(1-y)) and c for the centered probability
 *  y - 0.5, the terms in order are 1, L, cL, c, c^2, c^2 L, c^3, c^3 L, and so on, alternating
 *  between a power of c and that power multiplied by the logit. Because the quantile function
 *  is linear in the coefficients, fitting reduces to least squares.
 *
 *  These functions accept any number of terms of at least two. It is the concrete distribution
 *  classes that fix the arity.
 *
 *  Terms are numbered from one, matching the indexing used in the paper, while coefficient
 *  arrays are indexed from zero in the usual way. So the coefficient at array index zero
 *  multiplies term one.
 */
object MetalogFunctions {

    /**
     *  The fewest terms that define a metalog. With two terms the distribution is a logistic.
     */
    const val MIN_TERMS: Int = 2

    /**
     *  The i-th basis term evaluated at cumulative probability y, where i is numbered from one.
     */
    fun basisTerm(i: Int, y: Double): Double {
        require(i >= 1) { "The term number $i must be at least 1" }
        requireValidProbability(y)
        val c = y - 0.5
        val l = logit(y)
        return when {
            i == 1 -> 1.0
            i == 2 -> l
            i == 3 -> c * l
            i == 4 -> c
            i % 2 == 1 -> c.pow((i - 1) / 2)
            else -> c.pow(i / 2 - 1) * l
        }
    }

    /**
     *  The derivative with respect to y of the i-th basis term, where i is numbered from one.
     */
    fun basisTermDerivative(i: Int, y: Double): Double {
        require(i >= 1) { "The term number $i must be at least 1" }
        requireValidProbability(y)
        val c = y - 0.5
        val l = logit(y)
        val w = 1.0 / (y * (1.0 - y))
        return when {
            i == 1 -> 0.0
            i == 2 -> w
            i == 3 -> c * w + l
            i == 4 -> 1.0
            i % 2 == 1 -> {
                val j = (i - 1) / 2
                j * c.pow(j - 1)
            }
            else -> {
                val j = i / 2 - 1
                c.pow(j) * w + j * c.pow(j - 1) * l
            }
        }
    }

    /**
     *  The design matrix of the metalog least squares problem. Row i holds the first
     *  `numTerms` basis terms evaluated at `probabilities` element i, so the matrix has one row
     *  per supplied probability and one column per term.
     */
    fun designMatrix(probabilities: DoubleArray, numTerms: Int): Array<DoubleArray> {
        require(numTerms >= MIN_TERMS) { "The number of terms $numTerms must be at least $MIN_TERMS" }
        require(probabilities.isNotEmpty()) { "The array of probabilities was empty" }
        return Array(probabilities.size) { row ->
            val y = probabilities[row]
            requireValidProbability(y)
            val c = y - 0.5
            val l = logit(y)
            DoubleArray(numTerms) { column -> term(column + 1, c, l) }
        }
    }

    /**
     *  The metalog quantile function evaluated in fitting space. For an unbounded metalog this
     *  is the quantile function of the random variable itself; for the other members it must be
     *  mapped through the appropriate boundedness transform.
     */
    fun quantile(coefficients: DoubleArray, y: Double): Double {
        requireEnoughTerms(coefficients)
        requireValidProbability(y)
        val c = y - 0.5
        val l = logit(y)
        val n = coefficients.size
        var sum = coefficients[0]
        sum += coefficients[1] * l
        if (n > 2) {
            sum += coefficients[2] * c * l
        }
        if (n > 3) {
            sum += coefficients[3] * c
        }
        // Terms five and beyond pair a power of c with that power times the logit. The power
        // advances after each even-numbered term, so it is tracked rather than recomputed.
        var cPower = c * c
        for (i in 5..n) {
            sum += if (i % 2 == 1) {
                coefficients[i - 1] * cPower
            } else {
                coefficients[i - 1] * cPower * l
            }
            if (i % 2 == 0) {
                cPower *= c
            }
        }
        return sum
    }

    /**
     *  The derivative with respect to y of the metalog quantile function. This is strictly
     *  positive throughout the open interval from zero to one exactly when the coefficients
     *  define a valid distribution, which is what `MetalogFeasibilityChecker` verifies.
     */
    fun quantileDerivative(coefficients: DoubleArray, y: Double): Double {
        requireEnoughTerms(coefficients)
        requireValidProbability(y)
        val c = y - 0.5
        val l = logit(y)
        val w = 1.0 / (y * (1.0 - y))
        val n = coefficients.size
        var sum = coefficients[1] * w
        if (n > 2) {
            sum += coefficients[2] * (c * w + l)
        }
        if (n > 3) {
            sum += coefficients[3]
        }
        // The odd term needs c raised to one less than the power the even term needs, so both
        // are carried forward together.
        var j = 2
        var lowerPower = c
        var power = c * c
        for (i in 5..n) {
            if (i % 2 == 1) {
                sum += coefficients[i - 1] * j * lowerPower
            } else {
                sum += coefficients[i - 1] * (power * w + j * lowerPower * l)
                j += 1
                lowerPower = power
                power *= c
            }
        }
        return sum
    }

    /**
     *  The metalog density in fitting space, which is the reciprocal of the quantile
     *  derivative. This is a density with respect to the fitting-space variable; multiply by
     *  the appropriate boundedness density factor to obtain a density on the support of the
     *  random variable.
     */
    fun density(coefficients: DoubleArray, y: Double): Double {
        return 1.0 / quantileDerivative(coefficients, y)
    }

    /**
     *  The logit of the supplied probability, the quantity Keelin writes as ln(y/(1-y)).
     */
    fun logit(y: Double): Double {
        requireValidProbability(y)
        return ln(y / (1.0 - y))
    }

    /**
     *  Evaluates a basis term from already-computed logit and centered probability, avoiding
     *  the repeated logarithm when a whole row or a whole sum is being built.
     */
    private fun term(i: Int, c: Double, l: Double): Double {
        return when {
            i == 1 -> 1.0
            i == 2 -> l
            i == 3 -> c * l
            i == 4 -> c
            i % 2 == 1 -> c.pow((i - 1) / 2)
            else -> c.pow(i / 2 - 1) * l
        }
    }

    private fun requireValidProbability(y: Double) {
        require(!y.isNaN()) { "The probability must not be NaN" }
        require((y > 0.0) && (y < 1.0)) { "The probability $y must be strictly within (0,1)" }
    }

    private fun requireEnoughTerms(coefficients: DoubleArray) {
        require(coefficients.size >= MIN_TERMS) {
            "There must be at least $MIN_TERMS coefficients, found ${coefficients.size}"
        }
    }
}
