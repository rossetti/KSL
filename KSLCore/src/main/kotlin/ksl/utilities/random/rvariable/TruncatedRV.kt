/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

package ksl.utilities.random.rvariable

import ksl.utilities.distributions.DistributionFunctionIfc
import ksl.utilities.math.KSLMath
import ksl.utilities.random.rng.RNStreamIfc

/**
 * Constructs a truncated random variable based on the provided distribution
 *
 * @param distribution the distribution to truncate, must not be null
 * @param cdfLL        The lower limit of the range of support of the distribution
 * @param cdfUL        The upper limit of the range of support of the distribution
 * @param lowerLimit      The truncated lower limit (if moved in from cdfLL), must be &gt;= cdfLL
 * @param upperLimit      The truncated upper limit (if moved in from cdfUL), must be &lt;= cdfUL
 * @param stream the random number stream
 */
class TruncatedRV(
    distribution: DistributionFunctionIfc, val cdfLL: Double, val cdfUL: Double,
    val lowerLimit: Double, val upperLimit: Double, stream: RNStreamIfc = KSLRandom.nextRNStream()
) : RVariable(stream) {
    init {
        require(lowerLimit < upperLimit) { "The lower limit must be < the upper limit" }
        require(lowerLimit >= cdfLL) { "The lower limit must be >= $cdfLL" }
        require(upperLimit <= cdfUL) { "The upper limit must be <= $cdfUL" }
        require(!(lowerLimit == cdfLL && upperLimit == cdfUL)) { "There was no truncation over the interval of support" }
    }

    private val myDistribution: DistributionFunctionIfc = distribution

    private val myFofLL: Double
    private val myFofUL: Double
    private val myDeltaFUFL: Double

    init {
        if (lowerLimit > cdfLL && upperLimit < cdfUL) {
            // truncation on both ends
            myFofUL = myDistribution.cdf(upperLimit)
            myFofLL = myDistribution.cdf(lowerLimit)
        } else if (upperLimit < cdfUL) { // truncation on upper tail
            // must be that upperLimit < UL, and lowerLimit == LL
            myFofUL = myDistribution.cdf(upperLimit)
            myFofLL = 0.0
        } else { //truncation on the lower tail
            // must be that upperLimit == UL, and lowerLimit > LL
            myFofUL = 1.0
            myFofLL = myDistribution.cdf(lowerLimit)
        }
        myDeltaFUFL = myFofUL - myFofLL
        require(!KSLMath.equal(myDeltaFUFL, 0.0))
        { "The supplied limits have no probability support (F(upper) - F(lower) = 0.0)" }
    }


    /**
     * Constructs a truncated random variable based on the provided distribution
     *
     * @param distribution the distribution to truncate, must not be null
     * @param cdfLL        The lower limit of the range of support of the distribution
     * @param cdfUL        The upper limit of the range of support of the distribution
     * @param truncLL      The truncated lower limit (if moved in from cdfLL), must be &gt;= cdfLL
     * @param truncUL      The truncated upper limit (if moved in from cdfUL), must be &lt;= cdfUL
     * @param streamNum    A positive integer to identify the stream
     */
    constructor(
        distribution: DistributionFunctionIfc, cdfLL: Double, cdfUL: Double,
        truncLL: Double, truncUL: Double, streamNum: Int
    ) : this(distribution, cdfLL, cdfUL, truncLL, truncUL, KSLRandom.rnStream(streamNum)) {
    }

    override fun generate(): Double {
        val v = myFofLL + myDeltaFUFL * rnStream.randU01()
        return myDistribution.invCDF(v)
    }

    override fun instance(stream: RNStreamIfc): RVariableIfc {
        return TruncatedRV(myDistribution, cdfLL, cdfUL, lowerLimit, upperLimit, stream)
    }

    override fun toString(): String {
        return "TruncatedRV(cdfLL=$cdfLL, cdfUL=$cdfUL, lowerLimit=$lowerLimit, upperLimit=$upperLimit, myFofLL=$myFofLL, myFofUL=$myFofUL, myDeltaFUFL=$myDeltaFUFL)"
    }

}