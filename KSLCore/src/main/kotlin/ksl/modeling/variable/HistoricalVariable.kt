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
import ksl.utilities.KSLArrays
import ksl.utilities.PreviousValueIfc
import ksl.utilities.random.rvariable.ConstantRV
import java.io.FileNotFoundException
import java.nio.file.Path
import java.util.*
import kotlin.collections.ArrayList


class HistoricalVariable(
    parent: ModelElement,
    val pathToFile: Path,
    val arrayOption: Boolean = true,
    defaultValue: GetValueIfc? = null,
    val endStreamOption: EndStreamOption = EndStreamOption.STOP,
    name: String? = null
) : ModelElement(parent, name), GetValueIfc, PreviousValueIfc {

    enum class EndStreamOption {
        REPEAT, STOP, USE_LAST, USE_DEFAULT
    }

    private var myPreviousValue: Double = 0.0
    private var dataArray = ArrayList<Double>()
    private lateinit var myScanner: Scanner
    private lateinit var arrayIterator: Iterator<Double>
//    private lateinit var stringIterator: Iterator<String>

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
        if (endStreamOption == EndStreamOption.USE_DEFAULT) {
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
        // if not using the array option then ensure a new scanner is made
        if (!arrayOption) {
            myScanner = Scanner(pathToFile)
        } else {
            arrayIterator = dataArray.iterator()
        }
    }

    override fun afterExperiment() {
        // always close the scanner if it was used
        if (!arrayOption) {
            myScanner.reset()
        }
    }

    private fun nextValue(): Double {
        if (arrayOption) {
            // use array
            if (arrayIterator.hasNext()) {
                return arrayIterator.next()
            } else {
                // check the options
            }
        } else {
            // use scanner
            if (myScanner.hasNextDouble()) {
                return myScanner.nextDouble()
            } else {
                // check the options
            }
        }
        TODO("Not yet implemented")
    }
}