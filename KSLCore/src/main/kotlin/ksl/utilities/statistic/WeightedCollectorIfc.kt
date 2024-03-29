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

package ksl.utilities.statistic

import ksl.utilities.GetValueIfc

interface WeightedCollectorIfc : CollectorIfc {

    /**
     * The last weight collected
     */
    val weight: Double

    /**
     * Collect on the supplied value
     *
     * @param obs a double representing the observation
     */
    override fun collect(obs: Double) {
        collect(obs, 1.0)
    }

    /**
     * Collect on the supplied value
     *
     * @param obs a double representing the observation
     * @param weight a double representing the weight of the observation
     */
    fun collect(obs: Double, weight: Double)

    /**
     * Collects on the values in the supplied array.
     *
     * @param observations the values, must not be null
     * @param weights the weights of the observations
     */
    fun collect(observations: DoubleArray, weights: DoubleArray) {
        require(observations.size == weights.size) { "The size of the arrays must be equal!" }
        for(i in observations.indices){
            collect(observations[i], weights[i])
        }
    }

    /**
     * Collects on the values returned by the supplied GetValueIfc
     *
     * @param v the object that returns the value to be collected
     * @param w the weight associated with the object
     */
    fun collect(v: GetValueIfc, w: GetValueIfc) {
        collect(v.value(), w.value())
    }
}