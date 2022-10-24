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

package ksl.observers

import ksl.modeling.variable.Counter
import ksl.simulation.ModelElement
import ksl.utilities.io.KSLFileUtil
import java.io.PrintWriter
import java.nio.file.Path

class CounterTrace(
    theCounter: Counter,
    pathToFile: Path = theCounter.myModel.outputDirectory.outDir.resolve(theCounter.name + "_Trace.csv"),
    header: Boolean = true
) :
    ModelElementObserver(theCounter.name) {

    private val printWriter: PrintWriter = KSLFileUtil.createPrintWriter(pathToFile)
    private var count: Double = 0.0
    private var myRepObservationCount: Int = 0
    private var myRepNum = 0.0
    private val variable = theCounter
    var maxNumReplications: Int = Int.MAX_VALUE
    var maxNumObsPerReplication: Long = Long.MAX_VALUE
    var maxNumObservations: Double = Double.MAX_VALUE
    
    init {
        if (header){
            writeHeader()
        }
        variable.attachModelElementObserver(this)
    }

    private fun writeHeader() {
        printWriter.print("n")
        printWriter.print(",")
        printWriter.print("t")
        printWriter.print(",")
        printWriter.print("x(t)")
        printWriter.print(",")
        printWriter.print("t(n-1)")
        printWriter.print(",")
        printWriter.print("x(t(n-1))")
        printWriter.print(",")
        printWriter.print("w")
        printWriter.print(",")
        printWriter.print("r")
        printWriter.print(",")
        printWriter.print("nr")
        printWriter.print(",")
        printWriter.print("sim")
        printWriter.print(",")
        printWriter.print("model")
        printWriter.print(",")
        printWriter.print("exp")
        printWriter.println()
    }

    override fun update(modelElement: ModelElement) {
        val model = variable.model
        count++
        if (count >= maxNumObservations){
            return
        }
        if (myRepNum != model.currentReplicationNumber.toDouble()) {
            myRepObservationCount = 0
        }
        myRepObservationCount++
        if (myRepObservationCount >= maxNumObsPerReplication){
            return
        }
        myRepNum = model.currentReplicationNumber.toDouble()
        if (myRepNum > maxNumReplications){
            return
        }
        printWriter.print(count)
        printWriter.print(",")
        printWriter.print(variable.timeOfChange)
        printWriter.print(",")
        printWriter.print(variable.value)
        printWriter.print(",")
        printWriter.print(variable.previousTimeOfChange)
        printWriter.print(",")
        printWriter.print(variable.previousValue)
        printWriter.print(",")
        printWriter.print(variable.timeOfChange - variable.previousTimeOfChange)
        printWriter.print(",")
        printWriter.print(myRepNum)
        printWriter.print(",")
        printWriter.print(myRepObservationCount)
        printWriter.print(",")
        printWriter.print(model.simulationName)
        printWriter.print(",")
        printWriter.print(model.name)
        printWriter.print(",")
        printWriter.print(model.experimentName)
        printWriter.println()
    }
}