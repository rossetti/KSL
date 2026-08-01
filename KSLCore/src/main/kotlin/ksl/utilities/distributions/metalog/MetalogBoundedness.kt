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

import kotlin.math.exp
import kotlin.math.ln

/**
 *  Identifies which member of the metalog family a pair of bounds describes, and supplies the
 *  transformation between the metalog's fitting space and the random variable's space.
 *
 *  The member is derived from the bounds rather than chosen. Infinite on both sides is
 *  unbounded, finite on both sides is bounded, and a single finite bound is semi-bounded on
 *  that side. This is why one distribution class per term count is sufficient to cover the
 *  unbounded, semi-bounded, and bounded members described in Keelin (2016).
 *
 *  The metalog quantile function produces a value in fitting space. Applying
 *  `fromFittingSpace` maps that value onto the support of the random variable. Applying
 *  `toFittingSpace` performs the inverse, which is what turns observed data into the response
 *  vector of the least squares problem. Multiplying the metalog density by `densityFactor`
 *  converts it to a density on the random variable's support.
 *
 *  Every density factor is strictly positive, so a coefficient vector that is feasible for the
 *  unbounded metalog is feasible for all four members.
 */
enum class MetalogBoundedness {

    /**
     *  No bounds. The quantile function is the metalog quantile function itself.
     */
    Unbounded {
        override fun fromFittingSpace(z: Double, lowerBound: Double, upperBound: Double): Double = z

        override fun toFittingSpace(x: Double, lowerBound: Double, upperBound: Double): Double = x

        override fun densityFactor(z: Double, lowerBound: Double, upperBound: Double): Double = 1.0
    },

    /**
     *  A known lower bound and no upper bound. The natural log of the shifted variable is
     *  metalog distributed. Keelin calls this the log metalog.
     */
    LowerBounded {
        override fun fromFittingSpace(z: Double, lowerBound: Double, upperBound: Double): Double =
            lowerBound + exp(z)

        override fun toFittingSpace(x: Double, lowerBound: Double, upperBound: Double): Double {
            require(x > lowerBound) { "The value $x must be strictly greater than the lower bound $lowerBound" }
            return ln(x - lowerBound)
        }

        override fun densityFactor(z: Double, lowerBound: Double, upperBound: Double): Double =
            exp(-z)
    },

    /**
     *  A known upper bound and no lower bound. Keelin calls this the negative-log metalog.
     */
    UpperBounded {
        override fun fromFittingSpace(z: Double, lowerBound: Double, upperBound: Double): Double =
            upperBound - exp(-z)

        override fun toFittingSpace(x: Double, lowerBound: Double, upperBound: Double): Double {
            require(x < upperBound) { "The value $x must be strictly less than the upper bound $upperBound" }
            return -ln(upperBound - x)
        }

        override fun densityFactor(z: Double, lowerBound: Double, upperBound: Double): Double =
            exp(z)
    },

    /**
     *  Both bounds known. The logit of the scaled variable is metalog distributed. Keelin calls
     *  this the logit metalog.
     */
    Bounded {
        override fun fromFittingSpace(z: Double, lowerBound: Double, upperBound: Double): Double {
            // Written to avoid overflow of exp(z) for large z, where the limit is the upper bound.
            if (z >= LOGIT_OVERFLOW_LIMIT) {
                return upperBound
            }
            if (z <= -LOGIT_OVERFLOW_LIMIT) {
                return lowerBound
            }
            val e = exp(z)
            return (lowerBound + upperBound * e) / (1.0 + e)
        }

        override fun toFittingSpace(x: Double, lowerBound: Double, upperBound: Double): Double {
            require(x > lowerBound) { "The value $x must be strictly greater than the lower bound $lowerBound" }
            require(x < upperBound) { "The value $x must be strictly less than the upper bound $upperBound" }
            return ln((x - lowerBound) / (upperBound - x))
        }

        override fun densityFactor(z: Double, lowerBound: Double, upperBound: Double): Double {
            val e = exp(z)
            val onePlusE = 1.0 + e
            return (onePlusE * onePlusE) / ((upperBound - lowerBound) * e)
        }
    };

    /**
     *  Maps a value from the metalog's fitting space onto the support of the random variable.
     *  Bounds that this member does not use are ignored.
     */
    abstract fun fromFittingSpace(z: Double, lowerBound: Double, upperBound: Double): Double

    /**
     *  Maps a value on the support of the random variable into the metalog's fitting space.
     *  Bounds that this member does not use are ignored. The value must lie strictly inside
     *  any finite bound, because the transform is not defined at the bound itself.
     */
    abstract fun toFittingSpace(x: Double, lowerBound: Double, upperBound: Double): Double

    /**
     *  The strictly positive multiplier that converts the metalog density in fitting space to a
     *  density on the support of the random variable. The argument is the fitting-space value,
     *  not the random variable's value.
     */
    abstract fun densityFactor(z: Double, lowerBound: Double, upperBound: Double): Double

    /**
     *  True when this member has a finite lower bound.
     */
    val hasLowerBound: Boolean
        get() = (this == LowerBounded) || (this == Bounded)

    /**
     *  True when this member has a finite upper bound.
     */
    val hasUpperBound: Boolean
        get() = (this == UpperBounded) || (this == Bounded)

    companion object {

        /**
         *  Beyond this magnitude in fitting space, the bounded transform is evaluated at its
         *  limit rather than through the exponential, which would otherwise overflow.
         */
        const val LOGIT_OVERFLOW_LIMIT: Double = 745.0

        /**
         *  Determines which member of the metalog family the supplied bounds describe. A bound
         *  is treated as absent when it is infinite. Neither bound may be NaN, and the lower
         *  bound must be strictly less than the upper bound.
         */
        fun of(lowerBound: Double, upperBound: Double): MetalogBoundedness {
            require(!lowerBound.isNaN()) { "The lower bound must not be NaN" }
            require(!upperBound.isNaN()) { "The upper bound must not be NaN" }
            require(lowerBound < upperBound) {
                "The lower bound $lowerBound must be strictly less than the upper bound $upperBound"
            }
            val lowerIsFinite = lowerBound.isFinite()
            val upperIsFinite = upperBound.isFinite()
            return when {
                !lowerIsFinite && !upperIsFinite -> Unbounded
                lowerIsFinite && !upperIsFinite -> LowerBounded
                !lowerIsFinite && upperIsFinite -> UpperBounded
                else -> Bounded
            }
        }
    }
}
