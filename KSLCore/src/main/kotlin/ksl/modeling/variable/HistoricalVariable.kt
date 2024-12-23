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

package ksl.modeling.variable

import ksl.simulation.ModelElement
import ksl.utilities.GetValueIfc
import ksl.utilities.PreviousValueIfc
import ksl.utilities.io.KSLFileUtil
import java.io.FileNotFoundException
import java.nio.file.Path
import java.util.*
import kotlin.collections.ArrayList

fun interface StopActionIfc {
    fun stopAction(historicalVariable: HistoricalVariable)
}

enum class StoppingOption {
    REPEAT, STOP, USE_LAST, USE_DEFAULT
}

/**
 *   A historical variable returns values read from a file. These
 *   variables might be used in place of a RandomVariable to supply
 *   data values into a simulation model. The user must supply a
 *   valid path to the file [pathToFile]. The file should be a text
 *   file with a single column of double values, each value on a new row.
 *   All values in the file must be convertable to instances of Double.
 *
 *   The array option [arrayOption] indicates if the contents of the file
 *   are read into an array upon creation of the instance.  Consider using
 *   this option when the number of values can comfortably fit into memory.
 *   This avoids having an open file and should permit faster access to the
 *   values at the cost of increased memory requirements. The default for
 *   the array option is true. The file is closed immediately after reading
 *   all the values into the array.
 *
 *   If the array option is false, then values
 *   are read from the file during the execution of the simulation. The
 *   implication is that the file remains open during the simulation. The
 *   file will be opened automatically when the simulation experiment starts
 *   and closed automatically when the simulation experiment ends.
 *
 *   The user must specify what to do when there are no more values available
 *   in the stream via the end stream option [stoppingOption]. There are four options available:
 *
 *   REPEAT: When the end of values is detected, the values will automatically
 *   repeat with the next value returning to the first value available in the file.
 *
 *   STOP: When the end of values is detected, logic provided via the
 *   function [stopAction] will be executed. The default action is to stop the
 *   current replication and cause no future replications to be executed.  By
 *   providing a stop action function, the user may provide more specific
 *   actions that can occur when there are no more values in the stream.
 *   The value of [stopValue] will be returned as the last value.
 *   The default stopping value is Double.MAX_VALUE. The STOP option is the default behavior.
 *
 *   USE_LAST: When the end of values is detected, the last value from the file
 *   will be repeatedly returned for any future requests for values.
 *
 *   USE_DEFAULT: When the end of values is detected, the values will be
 *   returned from the supplied instance of the parameter defaultValue. In the case
 *   of specifying USE_DEFAULT, the user must supply an instance of the
 *   GetValueIfc interface to supply the values. If USE_DEFAULT is not specified,
 *   a default value is optional.
 *
 */
class HistoricalVariable(
    parent: ModelElement,
    val pathToFile: Path,
    val arrayOption: Boolean = true,
    val stoppingOption: StoppingOption = StoppingOption.STOP,
    val stopValue: Double = Double.MAX_VALUE,
    defaultValue: GetValueIfc? = null,
    name: String? = null
) : ModelElement(parent, name), GetValueIfc, PreviousValueIfc {

    /**
     * Creates a file based on the data and then creates the historical variable.
     * The file is created in the output directory of the model.
     *
     * @param data an array of the data (instead of a path to a file)
     * @param fileName the file name to use to represent the file
     */
    constructor(
        parent: ModelElement,
        data: DoubleArray,
        fileName: String,
        arrayOption: Boolean = true,
        stoppingOption: StoppingOption = StoppingOption.STOP,
        stopValue: Double = Double.MAX_VALUE,
        defaultValue: GetValueIfc? = null,
        name: String? = null
    ) : this(
        parent,
        arrayToFilePath(parent, fileName, data),
        arrayOption,
        stoppingOption,
        stopValue,
        defaultValue,
        name
    )

    companion object{

        private fun arrayToFilePath(parent: ModelElement, fileName: String, array: DoubleArray) : Path {
            val path = parent.model.outputDirectory.outDir.resolve(fileName)
            KSLFileUtil.writeToFile(array, path)
            return path
        }
    }

    var stopAction: (HistoricalVariable) -> Unit = ::stoppingAction

    private var myPreviousValue: Double = 0.0
    private var dataArray = ArrayList<Double>()
    private lateinit var myScanner: Scanner
    private lateinit var arrayIterator: Iterator<Double>

    private val myDefaultValue: GetValueIfc by lazy {
        require(defaultValue != null) { "If the stream option is to use the default value, it must be supplied" }
        defaultValue
    }

    init {
        if (arrayOption) {
            try {
                Scanner(pathToFile).use { scanner ->
                    val list = ArrayList<Double>()
                    while (scanner.hasNextDouble()) {
                        list.add(scanner.nextDouble())
                    }
                    dataArray = list
                }
            } catch (e: FileNotFoundException) {
                e.printStackTrace()
            }
        }
        if (stoppingOption == StoppingOption.USE_DEFAULT) {
            require(defaultValue != null) { "If the stream option is to use the default value, it must be supplied" }
        }
    }

    override fun value(): Double {
        previousValue = nextValue()
        notifyModelElementObservers(Status.UPDATE)
        return previousValue
    }

    override var previousValue: Double = 0.0
        private set

    override fun beforeExperiment() {
        if (!arrayOption) {
            myScanner.close()
            // if not using the array option then ensure a new scanner is made
            myScanner = Scanner(pathToFile)
        } else {
            // if using the array option then ensure a new iterator is assigned
            arrayIterator = dataArray.iterator()
        }
    }

    override fun afterExperiment() {
        // always close the scanner if it was used
        if (!arrayOption) {
            myScanner.close()
        }
    }

    private fun stoppingAction(historicalVariable: HistoricalVariable){
        executive.stop("Historical variable: ${historicalVariable.name}: Stopped replication = ${model.currentReplicationNumber} at time $time")
        model.endSimulation("Ended all replications by historical variable: $name")
    }

    private fun nextValue(): Double {
        if (arrayOption) {
            // use array
            if (arrayIterator.hasNext()) {
                return arrayIterator.next()
            } else {
                // check the options
                return when (stoppingOption) {
                    StoppingOption.REPEAT -> {
                        arrayIterator = dataArray.iterator()
                        arrayIterator.next()
                    }

                    StoppingOption.STOP -> {
                        stopAction.invoke(this)
                        return stopValue
                    }

                    StoppingOption.USE_LAST -> myPreviousValue
                    StoppingOption.USE_DEFAULT -> myDefaultValue.value
                }
            }
        } else {
            // use scanner
            if (myScanner.hasNextDouble()) {
                return myScanner.nextDouble()
            } else {
                // check the options
                return when (stoppingOption) {
                    StoppingOption.REPEAT -> {
                        myScanner.close()
                        myScanner = Scanner(pathToFile)
                        myScanner.nextDouble()
                    }

                    StoppingOption.STOP -> {
                        stopAction.invoke(this)
                        return stopValue
                    }

                    StoppingOption.USE_LAST -> myPreviousValue
                    StoppingOption.USE_DEFAULT -> myDefaultValue.value
                }
            }
        }
    }
}