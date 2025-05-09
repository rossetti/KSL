package ksl.examples.general.misc

import ksl.examples.book.chapter4.DriveThroughPharmacyWithQ
import ksl.observers.welch.WelchDataFileAnalyzer
import ksl.observers.welch.WelchFileObserver
import ksl.simulation.Model
import ksl.utilities.io.plotting.WelchPlot
import ksl.utilities.io.writeToFile
import ksl.utilities.random.rvariable.ExponentialRV

/**
 *  Example 5.4
 *  This code illustrates how to capture Welch plot data and to show the Welch
 * plot within a browser window.
 */
fun main(){
    val model = Model("Drive Through Pharmacy")
    // add DriveThroughPharmacy to the main model
    val dtp = DriveThroughPharmacyWithQ(model, 1)
    dtp.arrivalGenerator.initialTimeBtwEvents = ExponentialRV(6.0, 1)
    dtp.serviceRV.initialRandomSource = ExponentialRV(0.7, 2)

    val rvWelch = WelchFileObserver(dtp.systemTime, 1.0)
    val twWelch = WelchFileObserver(dtp.numInSystem, 10.0)
    model.numberOfReplications = 5
    model.lengthOfReplication = 50000.0

    model.simulate()
    model.print()
    println(rvWelch)
    println(twWelch)

    val rvFileAnalyzer = rvWelch.createWelchDataFileAnalyzer()
    val twFileAnalyzer = twWelch.createWelchDataFileAnalyzer()

    rvFileAnalyzer.createCSVWelchPlotDataFile()
    twFileAnalyzer.createCSVWelchPlotDataFile()

    val wp = WelchPlot(analyzer = rvFileAnalyzer)
    wp.defaultPlotDir = model.outputDirectory.plotDir
    wp.showInBrowser()
    wp.saveToFile("SystemTimeWelchPlot")

    val nObs = rvFileAnalyzer.minNumObservationsInReplications.toInt()
    println("nObs = $nObs")
    val avgs = rvFileAnalyzer.welchAveragesNE(nObs)
    avgs.writeToFile("WelchAverages.csv")
    val dList = WelchDataFileAnalyzer.computeMSER(avgs)
    dList.toDoubleArray().writeToFile("MSER_data.csv")

    val d = rvFileAnalyzer.recommendDeletionPoint()
    println("The recommended deletion point based on the MSE rule is: $d")
}