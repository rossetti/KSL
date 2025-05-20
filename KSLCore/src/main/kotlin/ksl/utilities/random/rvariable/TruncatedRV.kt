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

package ksl.utilities.random.rvariable

import ksl.utilities.Interval
import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.distributions.InvertibleCDFIfc
import ksl.utilities.math.KSLMath
import ksl.utilities.random.rng.RNStreamProviderIfc

/**
 * Constructs a truncated random variable based on the provided distribution
 *
 * @param distribution the distribution to truncate, must not be null
 * @param cdfLL        The lower limit of the range of support of the distribution
 * @param cdfUL        The upper limit of the range of support of the distribution
 * @param lowerLimit      The truncated lower limit (if moved in from cdfLL), must be &gt;= cdfLL
 * @param upperLimit      The truncated upper limit (if moved in from cdfUL), must be &lt;= cdfUL
 * @param streamNum the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 * @param name an optional name
 */
class TruncatedRV @JvmOverloads constructor(
    distribution: InvertibleCDFIfc,
    val cdfLL: Double,
    val cdfUL: Double,
    val lowerLimit: Double,
    val upperLimit: Double,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : RVariable(streamNum, streamProvider, name) {

    init {
        require(lowerLimit < upperLimit) { "The lower limit must be < the upper limit" }
        require(lowerLimit >= cdfLL) { "The lower limit must be >= $cdfLL" }
        require(upperLimit <= cdfUL) { "The upper limit must be <= $cdfUL" }
        require(!(lowerLimit == cdfLL && upperLimit == cdfUL)) { "There was no truncation over the interval of support" }
    }

    constructor(
        distribution: ContinuousDistributionIfc,
        distDomain: Interval = distribution.domain(),
        truncInterval: Interval,
        streamNum: Int = 0,
        streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
        name: String? = null
    ) : this(
        distribution,
        distDomain.lowerLimit,
        distDomain.upperLimit,
        truncInterval.lowerLimit,
        truncInterval.upperLimit,
        streamNum, streamProvider, name
    )

    private val myDistribution: InvertibleCDFIfc = distribution

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

    override fun generate(): Double {
        val v = myFofLL + myDeltaFUFL * rnStream.randU01()
        return myDistribution.invCDF(v)
    }

    override fun instance(streamNum: Int, rnStreamProvider: RNStreamProviderIfc): TruncatedRV {
        return TruncatedRV(myDistribution, cdfLL, cdfUL, lowerLimit, upperLimit, streamNum, rnStreamProvider, name)
    }

    override fun toString(): String {
        return "TruncatedRV(cdfLL=$cdfLL, cdfUL=$cdfUL, lowerLimit=$lowerLimit, upperLimit=$upperLimit, myFofLL=$myFofLL, myFofUL=$myFofUL, myDeltaFUFL=$myDeltaFUFL)"
    }

}