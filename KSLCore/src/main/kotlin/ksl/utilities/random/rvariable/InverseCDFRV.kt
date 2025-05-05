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

import ksl.utilities.distributions.InverseCDFIfc
import ksl.utilities.random.rng.RNStreamProviderIfc

/**
 * Facilitates the creation of random variables from distributions that implement InverseCDFIfc
 * @param inverseCDF the inverse of the distribution function
 * @param stream    a random number stream, must not be null
*/
class InverseCDFRV (
    val inverseCDF: InverseCDFIfc,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : RVariable(streamProvider,name) {

    /**
     * Makes one using the supplied stream number to assign the stream
     *
     * @param invFun    the inverse of the distribution function, must not be null
     * @param streamNum a positive integer
     */
    constructor(
        invFun: InverseCDFIfc,
        streamNum: Int,
        streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
        name: String? = null
    ) : this(invFun, streamProvider, name) {
        rnStream = streamProvider.rnStream(streamNum)
    }

    override fun generate(): Double {
        return inverseCDF.invCDF(rnStream.randU01())
    }

    override fun instance(streamNum: Int): RVariableIfc {
        return InverseCDFRV(inverseCDF, streamNum, streamProvider, name)
    }

    override fun toString(): String {
        return "InverseCDFRV(inverseCDF=$inverseCDF)"
    }

}