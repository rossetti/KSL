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

package ksl.utilities.distributions.metalog

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tanh

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
     *  The metalog quantile function evaluated in fitting space, parameterized by the logit of
     *  the cumulative probability rather than by the probability itself.
     *
     *  This reaches much further into the tails than the probability-based overload can. Near one,
     *  consecutive doubles are about a tenth of a quadrillionth apart, so a probability handed in
     *  as a double has already lost the information needed to recover its own logit: at a logit of
     *  thirty the round trip is wrong in the third decimal place. Parameterizing by the logit
     *  avoids the loss entirely, since the logit is then exact and the centered probability is
     *  obtained from the hyperbolic tangent of half of it, which does not cancel.
     *
     *  Use this wherever the far tail matters, such as integrating a moment of a semi-bounded
     *  metalog whose quantile function grows like a power law.
     */
    fun quantileFromLogit(coefficients: DoubleArray, logit: Double): Double {
        requireEnoughTerms(coefficients)
        require(!logit.isNaN()) { "The logit must not be NaN" }
        val c = 0.5 * tanh(0.5 * logit)
        val n = coefficients.size
        var sum = coefficients[0]
        sum += coefficients[1] * logit
        if (n > 2) {
            sum += coefficients[2] * c * logit
        }
        if (n > 3) {
            sum += coefficients[3] * c
        }
        var cPower = c * c
        for (i in 5..n) {
            sum += if (i % 2 == 1) {
                coefficients[i - 1] * cPower
            } else {
                coefficients[i - 1] * cPower * logit
            }
            if (i % 2 == 0) {
                cPower *= c
            }
        }
        return sum
    }

    /**
     *  The derivative of the cumulative probability with respect to its logit, which is the
     *  product of the probability and its complement. Written through the hyperbolic secant so
     *  that neither tail cancels.
     */
    fun probabilityDerivativeFromLogit(logit: Double): Double {
        val e = exp(-abs(logit))
        val onePlusE = 1.0 + e
        return e / (onePlusE * onePlusE)
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
     *  The combined weight on the logit at the supplied centered probability, formed by summing
     *  every coefficient whose basis term carries the logit, each scaled by the matching power of
     *  that centered probability. Terms two and three carry the logit, as does every
     *  even-numbered term from six onward.
     *
     *  Evaluated at plus or minus one half, this governs the behavior of the quantile function in
     *  the corresponding tail. A strictly positive weight means the quantile function diverges
     *  logarithmically there; a weight of exactly zero means it approaches a finite limit.
     */
    fun logitWeightAt(coefficients: DoubleArray, c: Double): Double {
        requireEnoughTerms(coefficients)
        var sum = coefficients[1]
        if (coefficients.size > 2) {
            sum += coefficients[2] * c
        }
        var i = 6
        while (i <= coefficients.size) {
            sum += coefficients[i - 1] * c.pow(i / 2 - 1)
            i += 2
        }
        return sum
    }

    /**
     *  The part of the quantile function that does not carry the logit, evaluated at the supplied
     *  centered probability. The first term contributes a constant, the fourth contributes the
     *  centered probability itself, and every odd-numbered term from five onward contributes a
     *  power of it.
     */
    fun nonLogitPartAt(coefficients: DoubleArray, c: Double): Double {
        requireEnoughTerms(coefficients)
        var sum = coefficients[0]
        if (coefficients.size > 3) {
            sum += coefficients[3] * c
        }
        var i = 5
        while (i <= coefficients.size) {
            sum += coefficients[i - 1] * c.pow((i - 1) / 2)
            i += 2
        }
        return sum
    }

    /**
     *  The limit of the quantile function in fitting space as the cumulative probability
     *  approaches zero.
     *
     *  A metalog whose declared bounds are infinite is not necessarily unbounded. Keelin notes
     *  that the quantile function is bounded whenever every coefficient carrying the logit is
     *  zero, the four-term uniform being the familiar example. In that case the logit weight
     *  vanishes and the limit is finite, because the logit diverges only logarithmically while
     *  its weight approaches zero linearly. Otherwise the limit is negative infinity.
     *
     *  The test for a vanishing weight is exact, so a fitted metalog whose weight is merely small
     *  is correctly reported as diverging.
     */
    fun limitAsProbabilityApproachesZero(coefficients: DoubleArray): Double {
        return if (logitWeightAt(coefficients, -0.5) == 0.0) {
            nonLogitPartAt(coefficients, -0.5)
        } else {
            Double.NEGATIVE_INFINITY
        }
    }

    /**
     *  The limit of the quantile function in fitting space as the cumulative probability
     *  approaches one. See the companion function for why this can be finite even when the
     *  declared bounds are infinite.
     */
    fun limitAsProbabilityApproachesOne(coefficients: DoubleArray): Double {
        return if (logitWeightAt(coefficients, 0.5) == 0.0) {
            nonLogitPartAt(coefficients, 0.5)
        } else {
            Double.POSITIVE_INFINITY
        }
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
