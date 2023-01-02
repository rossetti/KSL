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
    model.numberOfReplications = 1
    model.lengthOfReplication = 1000000.0
    model.lengthOfReplicationWarmUp = 100000.0
    val batchingElement = model.statisticalBatching()
    model.simulate()
    val sr = batchingElement.statisticReporter
    println(sr.halfWidthSummaryReport())
}