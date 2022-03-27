/*
 * Copyright (c) 2019. Manuel D. Rossetti, rossetti@uark.edu
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

import ksl.utilities.distributions.InverseCDFIfc
import ksl.utilities.random.rng.RNStreamIfc

/**
 * Facilitates the creation of random variables from distributions that implement InverseCDFIfc
 * @param inverseCDF the inverse of the distribution function
 * @param stream    a random number stream, must not be null
*/
class InverseCDFRV (val inverseCDF: InverseCDFIfc, stream: RNStreamIfc = KSLRandom.nextRNStream()) :
    RVariable(stream) {

    /**
     * Makes one using the supplied stream number to assign the stream
     *
     * @param invFun    the inverse of the distribution function, must not be null
     * @param streamNum a positive integer
     */
    constructor(invFun: InverseCDFIfc, streamNum: Int) : this(invFun, KSLRandom.rnStream(streamNum))

    override fun generate(): Double {
        return inverseCDF.invCDF(rnStream.randU01())
    }

    override fun instance(stream: RNStreamIfc): RVariableIfc {
        return InverseCDFRV(inverseCDF, stream)
    }

    override fun toString(): String {
        return "InverseCDFRV(inverseCDF=$inverseCDF)"
    }

}