package ksl.observers

import ksl.modeling.variable.Response
import ksl.simulation.ModelElement
import ksl.utilities.io.KSLFileUtil
import java.io.PrintWriter
import java.nio.file.Path

class ResponseTrace(
    theResponse: Response,
    pathToFile: Path = theResponse.myModel.outputDirectory.outDir.resolve(theResponse.name + "_Trace.csv"),
    header: Boolean = true
) :
    ModelElementObserver(theResponse.name) {

    private val printWriter: PrintWriter = KSLFileUtil.createPrintWriter(pathToFile)
    private var count: Double = 0.0
    private var myRepObservationCount: Int = 0
    private var myRepNum = 0.0
    private val variable = theResponse
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