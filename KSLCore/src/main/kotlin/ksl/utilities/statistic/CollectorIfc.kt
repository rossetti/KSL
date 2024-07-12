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

import ksl.modeling.variable.LastValueIfc
import ksl.modeling.variable.ValueIfc
import ksl.utilities.GetValueIfc
import ksl.utilities.random.rvariable.toDouble


/**
 * This interface represents a general set of methods for data collection. The
 * collect() method takes in the supplied data and collects it in some manner as
 * specified by the collector.
 *
 * @author rossetti
 */
interface CollectorIfc : LastValueIfc, ValueIfc {

    /**
     * Collects on the values returned by the supplied GetValueIfc
     *
     * @param v the object that returns the value to be collected
     */
    fun collect(v: GetValueIfc) {
        collect(v.value())
    }

    /**
     * Collects on the boolean value true = 1.0, false = 0.0
     *
     * @param obs the observation to collect on
     */
    fun collect(obs: Boolean) {
        collect(obs.toDouble())
    }

    /**
     *  Collect on the double value return by the function
     *  @param fn the function to invoke to collect doubles
     */
    fun collect(fn : () -> Double){
        collect(fn.invoke())
    }

    /**
     * Collects on the Int value
     *
     * @param obs the observation to collect on
     */
    fun collect(obs: Int) {
        collect(obs.toDouble())
    }

    /**
     * Collects on the Long value
     *
     * @param obs the observation to collect on
     */
    fun collect(obs: Long) {
        collect(obs.toDouble())
    }

    /**
     * Collect on the supplied value. Double.NaN,
     * Double.NEGATIVE_INFINITY, and Double.POSITIVE_INFINITY values
     * are counted as missing. Null values are not permitted.
     *
     * @param obs a double representing the observation
     */
    fun collect(obs: Double)

    /**
     * Collects on the values in the supplied array.
     *
     * @param observations the values, must not be null
     */
    fun collect(observations: DoubleArray) {
        for (v in observations) {
            collect(v)
        }
    }

    /**
     * Collects on the values in the supplied array.
     *
     * @param observations the values, must not be null
     */
    fun collect(observations: IntArray) {
        for (v in observations) {
            collect(v)
        }
    }

    /**
     * Collects on the values in the supplied array.
     *
     * @param observations the values, must not be null
     */
    fun collect(observations: LongArray) {
        for (v in observations) {
            collect(v)
        }
    }

    /**
     * Collects on the values in the supplied array.
     *
     * @param observations the values, must not be null
     */
    fun collect(observations: BooleanArray) {
        for (v in observations) {
            collect(v)
        }
    }

    /**
     *  Collects on all the values in the supplied collection.
     */
    fun collect(observations: Collection<Double>){
        for(v in observations){
            collect(v)
        }
    }

    /**
     * Resets the collector as if no observations had been collected.
     */
    fun reset()
}