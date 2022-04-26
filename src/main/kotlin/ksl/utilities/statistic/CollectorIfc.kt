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
package ksl.utilities.statistic

import ksl.utilities.GetValueIfc
import ksl.utilities.random.rvariable.toDouble


/**
 * This interface represents a general set of methods for data collection. The
 * collect() method takes in the supplied data and collects it in some manner as
 * specified by the collector.
 *
 * @author rossetti
 */
interface CollectorIfc {

    /**
     * The last value collected
     */
    var value : Double

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
     * @param value the value to collect on
     */
    fun collect(value: Boolean) {
        collect(value.toDouble())
    }

    /**
     * Collect on the supplied value
     *
     * @param value a double representing the observation
     */
    fun collect(value: Double)

    /**
     * Collects on the values in the supplied array.
     *
     * @param values the values, must not be null
     */
    fun collect(values: DoubleArray) {
        for (v in values) {
            collect(v)
        }
    }

    /**
     * Resets the collector as if no observations had been collected.
     */
    fun reset()
}