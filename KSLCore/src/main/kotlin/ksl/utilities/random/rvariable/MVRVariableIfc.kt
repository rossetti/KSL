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

import ksl.utilities.random.rng.RNStreamChangeIfc
import ksl.utilities.random.rng.RNStreamControlIfc
import ksl.utilities.random.rng.RNStreamIfc


/**
 * An interface for defining random variables
 */
interface MVRVariableIfc : RNStreamControlIfc, MVSampleIfc, RNStreamChangeIfc {
    /**
     * @param stream the random number stream to use
     * @return a new instance with same parameter value
     */
    fun instance(stream: RNStreamIfc): MVRVariableIfc

    /**
     * @return a new instance with same parameter value
     */
    fun instance(): MVRVariableIfc {
        return instance(KSLRandom.nextRNStream())
    }

    /**
     * @return a new instance with same parameter value, but that has antithetic variates
     */
    fun antitheticInstance(): MVRVariableIfc
}