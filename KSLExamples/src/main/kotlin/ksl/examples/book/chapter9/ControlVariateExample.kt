package ksl.examples.book.chapter9

import ksl.examples.book.chapter5.PalletWorkCenter
import ksl.observers.ControlVariateDataCollector
import ksl.simulation.Model
import ksl.utilities.io.write

fun main(){
    val model = Model("CV Example")
    model.numberOfReplications = 100
    val palletWorkCenter = PalletWorkCenter(model)
    val cvCollector = ControlVariateDataCollector(model)
    cvCollector.addResponse(palletWorkCenter.totalProcessingTime, "TotalTime")
    cvCollector.addControlVariate(palletWorkCenter.processingTimeRV, (8.0+ 12.0+ 15.0)/3.0, "PalletTime")
    cvCollector.addControlVariate(palletWorkCenter.numPalletsRV, (100.0*0.8), "NumPallets")
    model.simulate()
    model.print()
    println(cvCollector)
    val regressionData = cvCollector.collectedData("TotalTime", 20)
    println(regressionData)
    println()
    val regressionResults = cvCollector.regressionResults(regressionData)
    println(regressionResults)

}

