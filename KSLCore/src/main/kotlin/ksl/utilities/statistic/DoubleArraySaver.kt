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
package ksl.utilities.statistic

import ksl.utilities.observers.ObserverIfc
import java.io.PrintWriter

/**
 * A class to save data to an expanding array.
 */
class DoubleArraySaver : ObserverIfc<Double> {

    /**
     * The default increment for the array size
     *
     */
    var arraySizeIncrement = 1000
        set(value) {
            require(value > 0) { "Default array growth size must be > 0" }
            field = value
        }

    /**
     * A flag to indicate whether or not the saver should save the data as
     * it is collected.  If this flag is true, the data will be saved
     * when the save() method is called.
     */
    var saveOption: Boolean = true

    /**
     * The array to collect the data if the saved flag is true
     * Uses lazy initialization. Doesn't allocate array until
     * save is attempted and save option is on.
     */
    private var myData: DoubleArray = DoubleArray(arraySizeIncrement)

    /**
     * Counts the number of data points that were saved to the save array
     */
    var saveCount = 0
        private set

    /**
     *  Clears any data that was saved
     */
    fun clearData() {
        myData = DoubleArray(arraySizeIncrement)
        saveCount = 0
    }

    /**
     * @return the saved data as an array
     */
    fun savedData(): DoubleArray {
        if (saveCount == 0) {
            return doubleArrayOf()
        }
        return myData.copyOf(saveCount)
    }

    /**
     *  @param x the data point to save
     */
    fun save(x: Double) {
        if (!saveOption) {
            return
        }
        // need to save x into the array
        saveCount++
        if (saveCount > myData.size) {
            // need to grow the array
            myData = myData.copyOf(myData.size + arraySizeIncrement)
        }
        myData[saveCount - 1] = x
    }

    override fun onChange(newValue: Double) {
        save(newValue)
    }

    /** Writes out the saved data to a file.
     *
     * @param out the place to write the data
     */
    fun write(out: PrintWriter) {
        var i = 1
        for (x in myData) {
            //out.println("$i, $x")
            out.println(x)
            if (i == saveCount) {
                out.flush()
                return
            }
            i++
        }
        out.flush()
    }

}