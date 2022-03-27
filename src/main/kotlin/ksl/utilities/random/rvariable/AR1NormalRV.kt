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
    stream: RNStreamIfc = KSLRandom.nextRNStream()
) : RVariable(stream) {
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

}