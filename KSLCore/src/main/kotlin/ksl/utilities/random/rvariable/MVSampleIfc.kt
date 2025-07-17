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

import ksl.utilities.transpose

/**
 * An interface for getting multi-variable samples, each sample has many values
 * held in an array. Clients need to implement the sample(array) function
 * in order to fill up the array with the sample values. The
 * array of size dimension represent 1 sample with elements as the sample values
 * for each coordinate of the dimension. For example, for 2-D, sample
 * returns an array {x0, x1} where x0 is the sample for the first coordinate
 * and x1 is the sample value for the second coordinate.
 */
interface MVSampleIfc {
    /**
     *
     * the expected size of the array from sample()
     */
    val dimension: Int

    /**
     *
     * @return generates an array of random values of size dimension
     */
    fun sample(): DoubleArray {
        val array = DoubleArray(dimension)
        sample(array)
        return array
    }

    /** Fills the supplied array with a sample of values. This method
     * avoids the creation of a new array.  The size of the array
     * must match dimension
     *
     * @param array the array to fill with the sample
     */
    fun sample(array: DoubleArray)

    /**
     * Generates a list holding the randomly generated arrays of the given dimension.
     * Thus, the elements of the list are the arrays holding the sampled values.
     *
     * @param sampleSize the amount to fill
     * @return A list holding the generated arrays. There will be [sampleSize]
     * arrays, each having the size of dimension.
     */
    fun sample(sampleSize: Int): List<DoubleArray> {
        val list: MutableList<DoubleArray> = ArrayList()
        for (i in 0 until sampleSize) {
            list.add(sample())
        }
        return list
    }

    /** Generates a sample by columns of values. The returned array will
     * hold arrays of values, with each element being an array of
     * size [sampleSize]. That is a 2-D array with nRows = dimension
     * and nColumns = sample size
     *
     */
    fun sampleByColumn(sampleSize: Int): Array<DoubleArray> {
        val data = Array(sampleSize){DoubleArray(dimension)}
        sample(data)
        return data.transpose()
    }

    /**
     * Fills the supplied array of arrays with randomly generated samples
     *
     * @param values the arrays to fill. Each array in the array of arrays, should
     * have dimension elements. The number of arrays represents the required
     * sample size
     */
    fun sample(values: Array<DoubleArray>) {
        for (i in values.indices) {
            values[i] = sample()
        }
    }
}