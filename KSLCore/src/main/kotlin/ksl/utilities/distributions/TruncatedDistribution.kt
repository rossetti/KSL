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

import ksl.utilities.Interval
import ksl.utilities.math.KSLMath
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.GetRVariableIfc
import ksl.utilities.random.rvariable.TruncatedRV

/**
 * Constructs a truncated distribution based on the provided distribution
 *
 * @param distribution the distribution to truncate, must not be null
 * @param cdfLowerLimit        The lower limit of the range of support of the distribution
 * @param cdfUpperLimit        The upper limit of the range of support of the distribution
 * @param lowerLimit      The truncated lower limit (if moved in from cdfLL), must be &gt;= cdfLL
 * @param upperLimit      The truncated upper limit (if moved in from cdfUL), must be &lt;= cdfUL
 * @param name         an optional name/label

 */
class TruncatedDistribution(
    distribution: DistributionIfc,
    cdfLowerLimit: Double,
    cdfUpperLimit: Double,
    lowerLimit: Double,
    upperLimit: Double,
    name: String? = null
) : Distribution(name), GetRVariableIfc {

    constructor(
        distribution: DistributionIfc,
        distDomain: Interval,
        truncInterval: Interval,
        name: String? = null
    ) : this(
        distribution,
        distDomain.lowerLimit,
        distDomain.upperLimit,
        truncInterval.lowerLimit,
        truncInterval.upperLimit,
        name
    )

    init {
        require(lowerLimit < upperLimit) { "The lower limit must be < the upper limit" }
        require(lowerLimit >= cdfLowerLimit) { "The lower limit must be >= $cdfLowerLimit" }
        require(upperLimit <= cdfUpperLimit) { "The upper limit must be <= $cdfUpperLimit" }
        require(!(lowerLimit == cdfLowerLimit && upperLimit == cdfUpperLimit)) { "There was no truncation over the interval of support" }
    }

    var distribution: DistributionIfc = distribution
        private set

    var lowerLimit : Double = 0.0
        private set
    var upperLimit : Double = 0.0
        private set
    var cdfLowerLimit : Double = 0.0
        private set
    var cdfUpperLimit : Double = 0.0
        private set
    var cdfAtLowerLimit : Double = 0.0
        private set
    var cdfAtUpperLimit : Double = 0.0
        private set
    private val myDeltaFUFL
        get() = cdfAtUpperLimit - cdfAtLowerLimit

    init {
        setDistribution(distribution, cdfLowerLimit, cdfUpperLimit, lowerLimit, upperLimit)
    }

    override fun instance(): TruncatedDistribution {
        val d = this@TruncatedDistribution.distribution.instance()
        return TruncatedDistribution(d, this@TruncatedDistribution.cdfLowerLimit,
            this@TruncatedDistribution.cdfUpperLimit, this@TruncatedDistribution.lowerLimit, this@TruncatedDistribution.upperLimit
        )
    }

    /**
     *
     * @param distribution the distribution to truncate, must not be null
     * @param cdfLL        The lower limit of the range of support of the distribution
     * @param cdfUL        The upper limit of the range of support of the distribution
     * @param truncLL      The truncated lower limit (if moved in from cdfLL), must be &gt;= cdfLL
     * @param truncUL      The truncated upper limit (if moved in from cdfUL), must be &lt;= cdfUL
     */
    fun setDistribution(
        distribution: DistributionIfc, cdfLL: Double, cdfUL: Double,
        truncLL: Double, truncUL: Double
    ) {
        this.distribution = distribution
        setLimits(cdfLL, cdfUL, truncLL, truncUL)
    }

    /**
     *
     * @param cdfLL        The lower limit of the range of support of the distribution
     * @param cdfUL        The upper limit of the range of support of the distribution
     * @param truncLL      The truncated lower limit (if moved in from cdfLL), must be &gt;= cdfLL
     * @param truncUL      The truncated upper limit (if moved in from cdfUL), must be &lt;= cdfUL
     */
    fun setLimits(cdfLL: Double, cdfUL: Double, truncLL: Double, truncUL: Double) {
        require(truncLL < truncUL) { "The lower limit must be < the upper limit" }
        require(truncLL >= cdfLL) { "The lower limit must be >= $cdfLL" }
        require(truncUL <= cdfUL) { "The upper limit must be <= $cdfUL" }
        require(!(truncLL == cdfLL && truncUL == cdfUL)) { "There was no truncation over the interval of support" }
        this@TruncatedDistribution.lowerLimit = truncLL
        this@TruncatedDistribution.upperLimit = truncUL
        this@TruncatedDistribution.cdfLowerLimit = cdfLL
        this@TruncatedDistribution.cdfUpperLimit = cdfUL
        if (truncLL > cdfLL && truncUL < cdfUL) {
            // truncation on both ends
            cdfAtUpperLimit = this@TruncatedDistribution.distribution.cdf(this@TruncatedDistribution.upperLimit)
            cdfAtLowerLimit = this@TruncatedDistribution.distribution.cdf(this@TruncatedDistribution.lowerLimit)
        } else if (truncUL < cdfUL) { // truncation on upper tail
            // must be that upperLimit < UL, and lowerLimit == LL
            cdfAtUpperLimit = this@TruncatedDistribution.distribution.cdf(this@TruncatedDistribution.upperLimit)
            cdfAtLowerLimit = 0.0
        } else { //truncation on the lower tail
            // must be that upperLimit == UL, and lowerLimit > LL
            cdfAtUpperLimit = 1.0
            cdfAtLowerLimit = this@TruncatedDistribution.distribution.cdf(this@TruncatedDistribution.lowerLimit)
        }
        require(
            !KSLMath.equal(
                (cdfAtUpperLimit - cdfAtLowerLimit),
                0.0
            )
        ) { "The supplied limits have no probability support (F(upper) - F(lower) = 0.0)" }
    }

    /**
     * Sets the parameters of the truncated distribution
     * cdfLL = parameter[0]
     * cdfUL = parameters[1]
     * truncLL = parameters[2]
     * truncUL = parameters[3]
     *
     * any other values in the array should be interpreted as the parameters
     * for the underlying distribution
     */
    override fun parameters(params: DoubleArray) {
        setLimits(params[0], params[1], params[2], params[3])
        val y = DoubleArray(params.size - 4)
        for (i in y.indices) {
            y[i] = params[i + 4]
        }
        this@TruncatedDistribution.distribution.parameters(y)
    }

    /**
     * Get the parameters for the truncated distribution
     *
     * cdfLL = parameter[0]
     * cdfUL = parameters[1]
     * truncLL = parameters[2]
     * truncUL = parameters[3]
     *
     * any other values in the array should be interpreted as the parameters
     * for the underlying distribution
     */
    override fun parameters(): DoubleArray {
        val x = this@TruncatedDistribution.distribution.parameters()
        val y = DoubleArray(x.size + 4)
        y[0] = this@TruncatedDistribution.cdfLowerLimit
        y[1] = this@TruncatedDistribution.cdfUpperLimit
        y[2] = this@TruncatedDistribution.lowerLimit
        y[3] = this@TruncatedDistribution.upperLimit
        for (i in x.indices) {
            y[i + 4] = x[i]
        }
        return y
    }

    override fun cdf(x: Double): Double {
        return if (x < this@TruncatedDistribution.lowerLimit) {
            0.0
        } else if (x in this@TruncatedDistribution.lowerLimit..this@TruncatedDistribution.upperLimit) {
            val F = this@TruncatedDistribution.distribution.cdf(x)
            (F - cdfAtLowerLimit) / myDeltaFUFL
        } else {
            //if (x > myUpperLimit)
            1.0
        }
    }

    override fun mean(): Double {
        val mu = this@TruncatedDistribution.distribution.mean()
        return mu / myDeltaFUFL
    }

    override fun variance(): Double {
        // Var[X] = E[X^2] - E[X]*E[X]
        // first get 2nd moment of truncated distribution
        // E[X^2] = 2nd moment of original cdf/(F(b)-F(a)
        var mu = this@TruncatedDistribution.distribution.mean()
        val s2 = this@TruncatedDistribution.distribution.variance()
        // 2nd moment of original cdf
        var m2 = s2 + mu * mu
        // 2nd moment of truncated
        m2 = m2 / myDeltaFUFL
        // mean of truncated
        mu = mean()
        return m2 - mu * mu
    }

    override fun invCDF(p: Double): Double {
        val v = cdfAtLowerLimit + myDeltaFUFL * p
        return this@TruncatedDistribution.distribution.invCDF(v)
    }

    override fun randomVariable(streamNumber: Int, streamProvider: RNStreamProviderIfc): TruncatedRV {
        return TruncatedRV(this@TruncatedDistribution.distribution,
            this@TruncatedDistribution.cdfLowerLimit,
            this@TruncatedDistribution.cdfUpperLimit,
            this@TruncatedDistribution.lowerLimit, this@TruncatedDistribution.upperLimit, streamNumber, streamProvider)
    }

}