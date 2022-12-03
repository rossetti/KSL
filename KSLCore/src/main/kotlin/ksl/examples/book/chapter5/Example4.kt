package ksl.examples.book.chapter5

import ksl.examples.book.chapter4.DriveThroughPharmacyWithQ
import ksl.observers.welch.WelchFileObserver
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ExponentialRV
import java.text.DecimalFormat

fun main(){
    val model = Model("Drive Through Pharmacy")
    // add DriveThroughPharmacy to the main model
    val dtp = DriveThroughPharmacyWithQ(model, 1)
    dtp.arrivalRV.initialRandomSource = ExponentialRV(1.0, 1)
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
//    val out = model.outputDirectory.createPrintWriter("hwSummary.md")
//    val fmt = DecimalFormat(".####")
//    model.simulationReporter.writeHalfWidthSummaryReportAsMarkDown(out, df = fmt)
}