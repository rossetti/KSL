/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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
package ksl.utilities.distributions.fitting.mixture

import ksl.utilities.distributions.ContinuousDistributionIfc
import kotlin.math.exp
import kotlin.math.ln

/**
 *  The observed-data log-likelihood of a mixture, together with the diagnostics needed to know
 *  whether that number can be compared with another one.
 *
 *  @param value the sum over observations of the log of the mixture density. Negative infinity
 *  when at least one observation lies where every component has zero density.
 *  @param numZeroDensity the number of observations at which the mixture density is zero. A
 *  candidate with any such observation is not comparable on an information criterion with one
 *  that has none, so this count is reported rather than absorbed.
 *  @param numResponsibilityUndefined the number of observations at which the mixture density is
 *  zero and therefore no component responsibility is defined. This is a model-state failure in
 *  the sense of Proposition 5(a), not a zero responsibility for every component.
 */
data class MixtureLogLikelihoodResult(
    val value: Double,
    val numZeroDensity: Int,
    val numResponsibilityUndefined: Int
) {
    /**
     *  Indicates whether the log-likelihood is a finite number, and therefore whether it may be
     *  used in an information criterion.
     */
    val isUsable: Boolean
        get() = value.isFinite()
}

/**
 *  Evaluates mixture log-likelihoods and component responsibilities in a numerically stable way.
 *
 *  Forming the mixture density and then taking its logarithm loses precision exactly where the
 *  method is supposed to work best: when components are well separated, all but one of the terms
 *  in the sum underflow, and the surviving term can itself be small enough to round away. The
 *  log-sum-exp identity
 *
 *      ln( sum of w(j) f(j)(x) ) = M + ln( sum of exp( ln w(j) + ln f(j)(x) - M ) )
 *
 *  with M the largest of the log terms, computes the same quantity without underflow because the
 *  largest exponent is always zero.
 *
 *  KSL's own scoring models obtain a log-likelihood through `sumLogLikelihood`, which forms the
 *  density first. That is appropriate for a single distribution, where there is no sum to
 *  underflow, and inappropriate for a mixture.
 */
internal object MixtureLogLikelihood {

    /**
     *  The natural log of the density of a component at an observation, or negative infinity
     *  when that density is zero. Values are not floored: a zero density is represented as
     *  negative infinity so that the caller can decide what it means, rather than silently
     *  becoming a large finite penalty.
     *
     *  @param distribution the component distribution
     *  @param x the observation
     */
    fun logDensity(distribution: ContinuousDistributionIfc, x: Double): Double {
        val density = distribution.pdf(x)
        if (!density.isFinite()) {
            // An infinite density signals a degenerate fit, typically a support endpoint that
            // has collapsed onto an observation. It is reported rather than clamped.
            return if (density > 0.0) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY
        }
        return if (density <= 0.0) Double.NEGATIVE_INFINITY else ln(density)
    }

    /**
     *  The log of the mixture density at a single observation, by log-sum-exp.
     *
     *  @param weights the mixing weights, must be positive and sum to one
     *  @param components the component distributions, one per weight
     *  @param x the observation
     */
    fun logMixtureDensity(
        weights: DoubleArray,
        components: List<ContinuousDistributionIfc>,
        x: Double
    ): Double {
        require(weights.size == components.size) {
            "The number of weights (${weights.size}) must equal the number of components " +
                    "(${components.size})"
        }
        var maximum = Double.NEGATIVE_INFINITY
        val terms = DoubleArray(weights.size)
        for (j in weights.indices) {
            val term = ln(weights[j]) + logDensity(components[j], x)
            terms[j] = term
            if (term > maximum) maximum = term
        }
        if (maximum == Double.NEGATIVE_INFINITY) return Double.NEGATIVE_INFINITY
        if (maximum == Double.POSITIVE_INFINITY) return Double.POSITIVE_INFINITY
        var sum = 0.0
        for (term in terms) {
            sum += exp(term - maximum)
        }
        return maximum + ln(sum)
    }

    /**
     *  The observed-data log-likelihood of the mixture over the supplied observations, with the
     *  diagnostics that say whether it may be compared with another candidate's.
     *
     *  @param data the observations
     *  @param weights the mixing weights, must be positive and sum to one
     *  @param components the component distributions, one per weight
     */
    fun evaluate(
        data: DoubleArray,
        weights: DoubleArray,
        components: List<ContinuousDistributionIfc>
    ): MixtureLogLikelihoodResult {
        require(data.isNotEmpty()) { "The data must not be empty" }
        requireValidWeights(weights, components.size)
        var total = 0.0
        var zeroCount = 0
        for (x in data) {
            val logDensity = logMixtureDensity(weights, components, x)
            if (logDensity == Double.NEGATIVE_INFINITY) {
                zeroCount++
                total = Double.NEGATIVE_INFINITY
            } else if (total != Double.NEGATIVE_INFINITY) {
                total += logDensity
            }
        }
        return MixtureLogLikelihoodResult(total, zeroCount, zeroCount)
    }

    /**
     *  The component responsibilities at each observation: entry (i, j) is the posterior
     *  probability that observation i came from component j.
     *
     *  Rows for observations at which the mixture density is zero are filled with the not-a-number
     *  value, because no responsibility is defined there. Proposition 5(a) requires the mixture
     *  density to be positive for responsibilities to exist, and treating an undefined row as a
     *  row of zeros would silently misstate the model's state.
     *
     *  @param data the observations
     *  @param weights the mixing weights, must be positive and sum to one
     *  @param components the component distributions, one per weight
     */
    fun responsibilities(
        data: DoubleArray,
        weights: DoubleArray,
        components: List<ContinuousDistributionIfc>
    ): Array<DoubleArray> {
        requireValidWeights(weights, components.size)
        return Array(data.size) { i ->
            val x = data[i]
            val terms = DoubleArray(weights.size) { j ->
                ln(weights[j]) + logDensity(components[j], x)
            }
            val maximum = terms.max()
            if (!maximum.isFinite()) {
                DoubleArray(weights.size) { Double.NaN }
            } else {
                var sum = 0.0
                val scaled = DoubleArray(terms.size) { j ->
                    val e = exp(terms[j] - maximum)
                    sum += e
                    e
                }
                DoubleArray(scaled.size) { j -> scaled[j] / sum }
            }
        }
    }

    /**
     *  The classification entropy of a responsibility matrix, which is the quantity the
     *  integrated completed likelihood adds to the Bayesian information criterion. Zero when
     *  every observation is assigned to one component with certainty, larger when components
     *  overlap.
     *
     *  Rows with undefined responsibilities contribute nothing and are counted by the caller
     *  through the log-likelihood diagnostics.
     *
     *  @param responsibilities the responsibility matrix
     */
    fun classificationEntropy(responsibilities: Array<DoubleArray>): Double {
        var entropy = 0.0
        for (row in responsibilities) {
            for (g in row) {
                if (g.isFinite() && g > 0.0) {
                    entropy -= g * ln(g)
                }
            }
        }
        return entropy
    }

    private fun requireValidWeights(weights: DoubleArray, numComponents: Int) {
        require(weights.size == numComponents) {
            "The number of weights (${weights.size}) must equal the number of components " +
                    "($numComponents)"
        }
        require(weights.isNotEmpty()) { "There must be at least one weight" }
        var sum = 0.0
        for (w in weights) {
            require(w > 0.0) { "Every mixing weight must be > 0.0. Found $w" }
            sum += w
        }
        require(kotlin.math.abs(sum - 1.0) < 1.0e-8) {
            "The mixing weights must sum to 1.0. They summed to $sum"
        }
    }
}
