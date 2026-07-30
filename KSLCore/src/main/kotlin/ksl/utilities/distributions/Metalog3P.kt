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

/**
 *  A three-term metalog distribution.
 *
 *  The third coefficient primarily controls skewness: increasing it from zero skews the
 *  distribution to the right, and decreasing it below zero skews it to the left. At zero the
 *  distribution reduces to the two-term case.
 *
 *  This is the arity used by the symmetric percentile triplet workflow common in decision
 *  analysis, where an expert supplies three quantiles such as the tenth, fiftieth, and ninetieth
 *  percentiles. Three terms fit three points exactly, so the companion factory functions build
 *  the distribution directly from those quantiles without any least squares step.
 *
 *  @param a1 the location, which is the median in fitting space
 *  @param a2 the scale, which must be strictly positive
 *  @param a3 the skewness coefficient
 *  @param lowerBound the lower bound, or negative infinity when unbounded below
 *  @param upperBound the upper bound, or positive infinity when unbounded above
 *  @param name an optional name
 */
class Metalog3P(
    a1: Double = 0.0,
    a2: Double = 1.0,
    a3: Double = 0.0,
    lowerBound: Double = Double.NEGATIVE_INFINITY,
    upperBound: Double = Double.POSITIVE_INFINITY,
    name: String? = null
) : MetalogDistribution(doubleArrayOf(a1, a2, a3), lowerBound, upperBound, name) {

    /**
     *  The location coefficient.
     */
    var a1: Double
        get() = myCoefficients[0]
        set(value) = changeCoefficient(0, value)

    /**
     *  The scale coefficient, which must remain strictly positive.
     */
    var a2: Double
        get() = myCoefficients[1]
        set(value) = changeCoefficient(1, value)

    /**
     *  The skewness coefficient. Its magnitude relative to the scale is what limits how much
     *  skewness three terms can represent.
     */
    var a3: Double
        get() = myCoefficients[2]
        set(value) = changeCoefficient(2, value)

    override fun instance(): Metalog3P {
        return Metalog3P(a1, a2, a3, lowerBound, upperBound, name)
    }

    companion object {

        /**
         *  The default probability offset for a symmetric percentile triplet, giving the tenth,
         *  fiftieth, and ninetieth percentiles.
         */
        const val DEFAULT_ALPHA: Double = 0.1

        /**
         *  Builds a three-term metalog from a symmetric percentile triplet, using the closed
         *  form of Keelin (2016) Proposition 1 and its bounded counterparts in Propositions 3
         *  and 4.
         *
         *  The three quantiles correspond to cumulative probabilities of alpha, one half, and
         *  one minus alpha. Supplying a finite bound selects the semi-bounded or bounded member,
         *  in which case each quantile must lie strictly inside the bounds.
         *
         *  Not every triplet describes a valid distribution. A median too close to either outer
         *  quantile demands more skewness than three terms can express, and this function fails
         *  in that case. Test a triplet first with the companion predicate, or add terms.
         *
         *  @param lowerQuantile the quantile at cumulative probability alpha
         *  @param median the quantile at cumulative probability one half
         *  @param upperQuantile the quantile at cumulative probability one minus alpha
         *  @param alpha the probability offset, which must lie strictly within zero and one half
         *  @param lowerBound the lower bound, or negative infinity when unbounded below
         *  @param upperBound the upper bound, or positive infinity when unbounded above
         *  @param name an optional name
         */
        fun fromSPT(
            lowerQuantile: Double,
            median: Double,
            upperQuantile: Double,
            alpha: Double = DEFAULT_ALPHA,
            lowerBound: Double = Double.NEGATIVE_INFINITY,
            upperBound: Double = Double.POSITIVE_INFINITY,
            name: String? = null
        ): Metalog3P {
            val a = sptCoefficients(lowerQuantile, median, upperQuantile, alpha, lowerBound, upperBound)
            return Metalog3P(a[0], a[1], a[2], lowerBound, upperBound, name)
        }

        /**
         *  Whether the supplied symmetric percentile triplet describes a valid three-term
         *  metalog, checked without constructing one. This is the closed-form feasibility
         *  condition of Keelin (2016) Proposition 2, expressed through the coefficients the
         *  triplet produces.
         */
        fun isFeasibleSPT(
            lowerQuantile: Double,
            median: Double,
            upperQuantile: Double,
            alpha: Double = DEFAULT_ALPHA,
            lowerBound: Double = Double.NEGATIVE_INFINITY,
            upperBound: Double = Double.POSITIVE_INFINITY
        ): Boolean {
            val a = sptCoefficients(lowerQuantile, median, upperQuantile, alpha, lowerBound, upperBound)
            return MetalogFeasibilityChecker.isFeasible3Term(a[1], a[2])
        }

        /**
         *  The three coefficients implied by a symmetric percentile triplet.
         *
         *  Keelin's Propositions 3 and 4 are Proposition 1 applied to the transformed quantiles,
         *  so the quantiles are mapped into fitting space first and one expression then serves
         *  every member of the family.
         */
        fun sptCoefficients(
            lowerQuantile: Double,
            median: Double,
            upperQuantile: Double,
            alpha: Double = DEFAULT_ALPHA,
            lowerBound: Double = Double.NEGATIVE_INFINITY,
            upperBound: Double = Double.POSITIVE_INFINITY
        ): DoubleArray {
            require((alpha > 0.0) && (alpha < 0.5)) {
                "The probability offset $alpha must lie strictly within 0 and 0.5"
            }
            require(lowerQuantile < median) {
                "The lower quantile $lowerQuantile must be less than the median $median"
            }
            require(median < upperQuantile) {
                "The median $median must be less than the upper quantile $upperQuantile"
            }
            val boundedness = MetalogBoundedness.of(lowerBound, upperBound)
            val zLower = boundedness.toFittingSpace(lowerQuantile, lowerBound, upperBound)
            val zMedian = boundedness.toFittingSpace(median, lowerBound, upperBound)
            val zUpper = boundedness.toFittingSpace(upperQuantile, lowerBound, upperBound)
            val logOdds = ln((1.0 - alpha) / alpha)
            val spread = zUpper - zLower
            val r = (zMedian - zLower) / spread
            val a1 = zMedian
            val a2 = 0.5 * spread / logOdds
            val a3 = (1.0 - 2.0 * r) * spread / ((1.0 - 2.0 * alpha) * logOdds)
            return doubleArrayOf(a1, a2, a3)
        }
    }
}
