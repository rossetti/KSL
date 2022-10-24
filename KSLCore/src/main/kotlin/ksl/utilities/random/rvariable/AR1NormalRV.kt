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

import ksl.utilities.random.rng.RNStreamIfc

/** Creates an autoregressive order 1 normal process
 *
 * @param mean  the mean of the process
 * @param variance the variance of the process
 * @param lag1Corr the lag-1 correlation for the process
 * @param stream the random number stream
 */
class AR1NormalRV(
    val mean: Double = 0.0,
    val variance: Double = 1.0,
    val lag1Corr: Double = 0.0,
    stream: RNStreamIfc = KSLRandom.nextRNStream(),
    name: String? = null
) : ParameterizedRV(stream, name) {
    private var myX: Double
    private val myErrors: NormalRV

    init {
        require(variance > 0) { "Variance must be positive" }
        require(!(lag1Corr < -1.0 || lag1Corr > 1.0)) { "The correlation must be [-1,1]" }
        // generate the first value for the process N(mean, variance)
        myX = KSLRandom.rNormal(mean, variance, stream)
        // set the correlation and the error distribution N(0, myVar*(1-myPhi^2)
        val v = variance * (1.0 - lag1Corr * lag1Corr)
        // create the error random variable
        myErrors = NormalRV(0.0, v, stream)
    }

    /**
     * Constructs a bi-variate normal with the provided parameters
     *
     * @param mean  the mean of the process
     * @param variance the variance of the process
     * @param lag1Corr the lag-1 correlation for the process
     * @param streamNum the stream number
     */
    constructor(mean: Double = 0.0, variance: Double = 1.0, lag1Corr: Double = 0.0, streamNum: Int) :
            this(mean, variance, lag1Corr, KSLRandom.rnStream(streamNum))

    override fun instance(stream: RNStreamIfc): RVariableIfc {
        return AR1NormalRV(mean, variance, lag1Corr, stream)
    }

    /**
     *
     * @return The variance of the underlying errors
     */
    val errorVariance: Double = myErrors.variance

    override fun generate(): Double {
        myX = mean + lag1Corr * (myX - mean) + myErrors.value
        return myX
    }

    override fun toString(): String {
        return "AR1NormalRV(mean=$mean, variance=$variance, lag1Corr=$lag1Corr, errorVariance=$errorVariance)"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = RVParameters.AR1NormalRVParameters()
            parameters.changeDoubleParameter("mean", mean)
            parameters.changeDoubleParameter("variance", variance)
            parameters.changeDoubleParameter("correlation", lag1Corr)
            return parameters
        }

}