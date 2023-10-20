/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

/**
 *  Collects statistics for each dimension of the presented array.
 */
class MVStatistic(val names: List<String>) {

    companion object{
        fun makeNames(numNames: Int) : List<String>{
            require(numNames > 0) {"There must be at least 1 name"}
            return List(numNames){"stat_$it"}
        }
    }

    constructor(numNames: Int) : this(makeNames(numNames))

    init {
        require(names.isNotEmpty()) { "There must be at least one element for the names of the statistics" }
    }

    val statistics = List(names.size) { Statistic(name = names[it]) }

    /**
     *  Statistics are collected on the dimensions of the supplied array.
     *  For example, for each observation[0] presented statistics are collected
     *  across all presented values of observation[0]. For each array
     *  dimension, there will be a Statistic that summarizes the statistical
     *  properties of that dimension.
     */
    fun collect(observation: DoubleArray) {
        require(observation.size == names.size) { "The size of the observation array must match the dimension of the collector." }
        for ((i, x) in observation.withIndex()) {
            statistics[i].collect(x)
        }
    }

    fun reset() {
        for (s in statistics) {
            s.reset()
        }
    }

    /**
     *  Returns the sample averages for each dimension
     */
    val averages: DoubleArray
        get() {
            val a = DoubleArray(names.size)
            for ((i, s) in statistics.withIndex()) {
                a[i] = s.average
            }
            return a
        }

    /**
     *  Returns the sample variances for each dimension
     */
    val variances: DoubleArray
        get() {
            val v = DoubleArray(names.size)
            for ((i, s) in statistics.withIndex()) {
                v[i] = s.variance
            }
            return v
        }
}