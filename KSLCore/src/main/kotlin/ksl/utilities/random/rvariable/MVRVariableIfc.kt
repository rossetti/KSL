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

import ksl.utilities.random.rng.RNStreamControlIfc
import ksl.utilities.random.rng.RNStreamProviderIfc


interface MVRandomInstanceIfc {
    /**
     * @param streamNumber the stream number to use from the underlying provider
     * @param rnStreamProvider the provider for the stream instance
     * @return a new instance with same parameter values
     */
    fun instance(
        streamNumber: Int = 0,
        rnStreamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider
    ): MVRVariableIfc
}

/**
 * An interface for defining multi-variate random variables
 */
interface MVRVariableIfc : RNStreamControlIfc, MVSampleIfc, MVRandomInstanceIfc {

    val streamProvider: RNStreamProviderIfc

    val streamNumber: Int

    /**
     * @return a new instance with same parameter value, but that has antithetic variates
     */
    fun antitheticInstance(): MVRVariableIfc
}