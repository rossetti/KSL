package ksl.examples.general.models

import ksl.examples.book.chapter5.PalletWorkCenter
import ksl.observers.ControlVariateDataCollector
import ksl.simulation.Model
import ksl.utilities.io.write

fun main(){
    val model = Model("CV Example")
    model.numberOfReplications = 10
    val palletWorkCenter = PalletWorkCenter(model)
    val cvCollector = ControlVariateDataCollector(model)
    cvCollector.addResponse(palletWorkCenter.totalProcessingTime, "TotalTime")
    cvCollector.addControlVariate(palletWorkCenter.processingTimeRV, (8.0+ 12.0+ 15.0)/3.0, "PalletTime")
    cvCollector.addControlVariate(palletWorkCenter.numPalletsRV, (100.0*0.8), "NumPallets")
    model.simulate()
    model.print()
    println(cvCollector)
    val matrix = cvCollector.collectedData()
    matrix.write()
}
