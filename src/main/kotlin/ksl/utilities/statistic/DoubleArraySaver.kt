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

import ksl.utilities.observers.ObservableIfc
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

    override fun update(theObserved: ObservableIfc<Double>, newValue: Double?) {
        if (newValue != null) {
            save(newValue)
        }
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