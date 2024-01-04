package ksl.examples.general.models

import ksl.examples.book.chapter5.PalletWorkCenter
import ksl.observers.ControlVariateDataCollector
import ksl.simulation.Model
import ksl.utilities.KSLArrays
import ksl.utilities.concatenateTo1DArray
import ksl.utilities.io.write
//import org.hipparchus.stat.regression.OLSMultipleLinearRegression

//import org.apache.commons.math3.stat.regression.OLSMultipleLinearRegression

//import org.hipparchus.stat.regression.OLSMultipleLinearRegression

fun main(){
    val model = Model("CV Example")
    model.numberOfReplications = 10
    model.experimentName = "Two Workers"
    // add the model element to the main model
    val palletWorkCenter = PalletWorkCenter(model)

    val cvCollector = ControlVariateDataCollector(model)
    cvCollector.addResponse(palletWorkCenter.totalProcessingTime, "TotalTime")
    cvCollector.addControlVariate(palletWorkCenter.processingTimeRV, (8.0+ 12.0+ 15.0)/3.0, "PalletTime")
    cvCollector.addControlVariate(palletWorkCenter.numPalletsRV, (100.0*0.8), "NumPallets")
    model.simulate()

    println(cvCollector)

    val matrix = cvCollector.collectedData()
    matrix.write()

    val nObs = matrix.size
    val nVars = KSLArrays.numColumns(matrix) - 1
    require(nObs > nVars){"The number of observations must be greater than the number of estimated parameters"}
    val data = matrix.concatenateTo1DArray()
    val regression = OLSMultipleLinearRegression()
    regression.newSampleData(data, nObs, nVars)

}

