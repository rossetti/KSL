package ksl.observers

import ksl.modeling.variable.Variable
import ksl.utilities.io.KSLFileUtil
import java.io.PrintWriter
import java.nio.file.Path

class VariableTrace(
    theVariable: Variable,
    pathToFile: Path = theVariable.myModel.outputDirectory.outDir.resolve(theVariable.name + "_Trace.csv"),
    header: Boolean = true
) :
    ModelElementObserver(theVariable.name) {

    private val printWriter: PrintWriter = KSLFileUtil.createPrintWriter(pathToFile)
    private var count: Int = 0
    private var myRepCount: Int = 0
    private var myRepNum = 0.0
    private val variable = theVariable
    
    init {
        if (header){
            writeHeader()
        }
        attach(variable)
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

    override fun update() {
        val model = variable.model
        count++
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
        if (myRepNum != model.currentReplicationNumber.toDouble()) {
            myRepCount = 0
        }
        myRepCount++
        myRepNum = model.currentReplicationNumber.toDouble()
        printWriter.print(myRepNum)
        printWriter.print(",")
        printWriter.print(myRepCount)
        printWriter.print(",")
        printWriter.print(model.simulationName)
        printWriter.print(",")
        printWriter.print(model.name)
        printWriter.print(",")
        printWriter.print(model.experimentName)
        printWriter.println()
    }
}