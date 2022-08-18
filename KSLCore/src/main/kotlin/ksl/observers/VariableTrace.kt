package ksl.observers

import ksl.modeling.variable.Variable
import ksl.utilities.io.KSL
import ksl.utilities.io.KSLFileUtil
import java.io.PrintWriter
import java.nio.file.Path

class VariableTrace(
    variable: Variable,
    pathToFile: Path = KSL.outDir.resolve(variable.name + "_Trace.csv"),
    header: Boolean = true
) :
    ModelElementObserver<Variable>(variable) {

    private val printWriter: PrintWriter = KSLFileUtil.createPrintWriter(pathToFile)
    private var count: Int = 0
    private var myRepCount: Int = 0
    private var myRepNum = 0.0
    
    init {
        if (header){
            writeHeader()
        }
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
        val v = observedModelElement
        count++
        printWriter.print(count)
        printWriter.print(",")
        printWriter.print(v.timeOfChange)
        printWriter.print(",")
        printWriter.print(v.value)
        printWriter.print(",")
        printWriter.print(v.previousTimeOfChange)
        printWriter.print(",")
        printWriter.print(v.previous)
        printWriter.print(",")
        if (myRepNum != v.currentReplicationNumber.toDouble()) {
            myRepCount = 0
        }
        myRepCount++
        myRepNum = v.currentReplicationNumber.toDouble()
        printWriter.print(myRepNum)
        printWriter.print(",")
        printWriter.print(myRepCount)
        printWriter.print(",")
        printWriter.print(v.simulationName)
        printWriter.print(",")
        printWriter.print(v.modelName)
        printWriter.print(",")
        printWriter.print(v.experimentName)
        printWriter.println()
    }
}