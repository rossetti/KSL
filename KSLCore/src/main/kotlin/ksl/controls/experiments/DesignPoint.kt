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

package ksl.controls.experiments

import ksl.utilities.KSLArrays
import ksl.utilities.toMapOfLists
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.api.toDataFrame

/**
 *  A design point is a specification of the [settings] for the
 *  factors of an experiment.
 */
class DesignPoint(
    val design: ExperimentalDesignIfc,
    val number: Int,
    val settings: Map<Factor, Double>
) {
    init {
        require(number > 0) { "Number must be positive." }
    }

    /**
     *  The number of replications must be 0 or more. If 0,
     *  then the design point should not be executed. The default is 1.
     */
    var numReplications: Int = 1
        set(value) {
            require(value >= 1) { "number replications must be >= 1" }
            field = value
        }

    /**
     *  The raw values for each factor for this design point with ordering
     *  based on the order in which the factors are placed in the settings.
     */
    fun values(): DoubleArray {
        val list = mutableListOf<Double>()
        for ((f, v) in settings.entries) {
            list.add(v)
        }
        return list.toDoubleArray()
    }

    /**
     *  The coded values for each factor for this design point with ordering
     *  based on the order in which the factors are placed in the settings.
     */
    fun codedValues(): DoubleArray {
        val list = mutableListOf<Double>()
        for ((f, v) in settings.entries) {
            list.add(f.codedValue(v))
        }
        return list.toDoubleArray()
    }
}

/**
 *  Turns the list of design points into a data frame.
 *  The columns of the data frame are the factor names and the rows are the
 *  design points values.
 *  @param coded indicates if the points should be coded. The default is false.
 */
fun List<DesignPoint>.asDataFrame(coded: Boolean = false): AnyFrame {
    if (isEmpty()) {
        return AnyFrame.empty()
    }
    // get the points as arrays
    val points = if (coded) {
        List(size) { this[it].codedValues() }
    } else {
        List(size) { this[it].values() }
    }
    val cols = KSLArrays.to2DDoubleArray(points).toMapOfLists(first().design.factorNames)
    return cols.toDataFrame()
}
