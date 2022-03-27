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

/**
 * An interface for getting multi-variable samples, each sample has many values
 * held in an array
 */
interface MVSampleIfc {
    /**
     *
     * @return generates an array of random values
     */
    fun sample(): DoubleArray

    /**
     * Generates a list holding the randomly generated arrays of the given size
     *
     * @param sampleSize the amount to fill
     * @return A list holding the generated arrays
     */
    fun sample(sampleSize: Int): List<DoubleArray> {
        val list: MutableList<DoubleArray> = ArrayList()
        for (i in 0 until sampleSize) {
            list.add(sample())
        }
        return list
    }

    /**
     * Fills the supplied list of arrays with a randomly generated samples
     *
     * @param values the list to fill
     */
    fun sample(values: MutableList<DoubleArray>) {
        for (i in values.indices) {
            values.add(sample())
        }
    }
}