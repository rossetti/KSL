/*
 * Copyright (c) 2018. Manuel D. Rossetti, rossetti@uark.edu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package ksl.utilities.distributions

import ksl.utilities.math.KSLMath
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.GetRVariableIfc
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.random.rvariable.TruncatedRV

/**
 * Constructs a truncated distribution based on the provided distribution
 *
 * @param theDistribution the distribution to truncate, must not be null
 * @param theCDFLowerLimit        The lower limit of the range of support of the distribution
 * @param theCDFUpperLimit        The upper limit of the range of support of the distribution
 * @param theLowerLimit      The truncated lower limit (if moved in from cdfLL), must be &gt;= cdfLL
 * @param theUpperLimit      The truncated upper limit (if moved in from cdfUL), must be &lt;= cdfUL
 * @param name         an optional name/label

 */
class TruncatedDistribution(
    theDistribution: DistributionIfc<*>, theCDFLowerLimit: Double, theCDFUpperLimit: Double,
    theLowerLimit: Double, theUpperLimit: Double, name: String? = null
) : Distribution<TruncatedDistribution>(name), GetRVariableIfc {

    init {
        require(theLowerLimit < theUpperLimit) { "The lower limit must be < the upper limit" }
        require(theLowerLimit >= theCDFLowerLimit) { "The lower limit must be >= $theCDFLowerLimit" }
        require(theUpperLimit <= theCDFUpperLimit) { "The upper limit must be <= $theCDFUpperLimit" }
        require(!(theLowerLimit == theCDFLowerLimit && theUpperLimit == theCDFUpperLimit)) { "There was no truncation over the interval of support" }
    }

    var distribution: DistributionIfc<*> = theDistribution
        private set

    var lowerLimit = 0.0
        private set
    var upperLimit = 0.0
        private set
    var cdfLowerLimit = 0.0
        private set
    var cdfUpperLimit = 0.0
        private set
    var cdfAtLowerLimit = 0.0
        private set
    var cdfAtUpperLimit = 0.0
        private set
    private val myDeltaFUFL = cdfAtUpperLimit - cdfAtLowerLimit

    init {
        setDistribution(theDistribution, theCDFLowerLimit, theCDFUpperLimit, theLowerLimit, theUpperLimit)
    }

    override fun instance(): TruncatedDistribution {
        val d = distribution.instance() as DistributionIfc<*>
        return TruncatedDistribution(d, cdfLowerLimit, cdfUpperLimit, lowerLimit, upperLimit)
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
        distribution: DistributionIfc<*>, cdfLL: Double, cdfUL: Double,
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
        lowerLimit = truncLL
        upperLimit = truncUL
        cdfLowerLimit = cdfLL
        cdfUpperLimit = cdfUL
        if (truncLL > cdfLL && truncUL < cdfUL) {
            // truncation on both ends
            cdfAtUpperLimit = distribution.cdf(upperLimit)
            cdfAtLowerLimit = distribution.cdf(lowerLimit)
        } else if (truncUL < cdfUL) { // truncation on upper tail
            // must be that upperLimit < UL, and lowerLimit == LL
            cdfAtUpperLimit = distribution.cdf(upperLimit)
            cdfAtLowerLimit = 0.0
        } else { //truncation on the lower tail
            // must be that upperLimit == UL, and lowerLimit > LL
            cdfAtUpperLimit = 1.0
            cdfAtLowerLimit = distribution.cdf(lowerLimit)
        }
        require(!KSLMath.equal((cdfAtUpperLimit - cdfAtLowerLimit), 0.0)) { "The supplied limits have no probability support (F(upper) - F(lower) = 0.0)" }
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
        distribution.parameters(y)
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
        val x = distribution.parameters()
        val y = DoubleArray(x.size + 4)
        y[0] = cdfLowerLimit
        y[1] = cdfUpperLimit
        y[2] = lowerLimit
        y[3] = upperLimit
        for (i in x.indices) {
            y[i + 4] = x[i]
        }
        return y
    }

    override fun cdf(x: Double): Double {
        return if (x < lowerLimit) {
            0.0
        } else if (x in lowerLimit..upperLimit) {
            val F = distribution.cdf(x)
            (F - cdfAtLowerLimit) / myDeltaFUFL
        } else {
            //if (x > myUpperLimit)
            1.0
        }
    }

    override fun mean(): Double {
        val mu = distribution.mean()
        return mu / myDeltaFUFL
    }

    override fun variance(): Double {
        // Var[X] = E[X^2] - E[X]*E[X]
        // first get 2nd moment of truncated distribution
        // E[X^2] = 2nd moment of original cdf/(F(b)-F(a)
        var mu = distribution.mean()
        val s2 = distribution.variance()
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
        return distribution.invCDF(v)
    }

    override fun randomVariable(stream: RNStreamIfc): RVariableIfc {
        return TruncatedRV(distribution, cdfLowerLimit, cdfUpperLimit, lowerLimit, upperLimit, stream)
    }

}