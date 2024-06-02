package ksl.examples.book.chapter5

import ksl.controls.experiments.Scenario
import ksl.controls.experiments.ScenarioRunner
import ksl.examples.book.chapter4.DriveThroughPharmacy
import ksl.examples.book.chapter4.DriveThroughPharmacyWithQ
import ksl.simulation.Model

/**
 *  Example 5.10
 *  Illustrate how to create and run scenarios.
 */
fun main() {
    val scenarioRunner = ScenarioRunner("Example5_10", buildScenarios())
    scenarioRunner.simulate()
    for (s in scenarioRunner.scenarioList) {
        val sr = s.simulationRun?.statisticalReporter()
        val r = sr?.halfWidthSummaryReport(title = s.name)
        println(r)
        println()
    }
}

fun buildScenarios() : List<Scenario> {
    val model = Model("Pallet Model", autoCSVReports = true)
    // add the model element to the main model
    val palletWorkCenter = PalletWorkCenter(model, name = "PWC")
    // set up the model
    model.resetStartStreamOption = true
    model.numberOfReplications = 30

    val sim1Inputs = mapOf(
        "ProcessingTimeRV.mode" to 14.0,
        "PWC.numWorkers" to 1.0,
        )

    val sim2Inputs = mapOf(
        "ProcessingTimeRV.mode" to 14.0,
        "PWC.numWorkers" to 2.0,
    )

    val sim3Inputs = mapOf(
        "ProcessingTimeRV.mode" to 14.0,
        "PWC.numWorkers" to 3.0,
    )

    val dtpModel = Model("DTP Model", autoCSVReports = true)
    dtpModel.numberOfReplications = 30
    dtpModel.lengthOfReplication = 20000.0
    dtpModel.lengthOfReplicationWarmUp = 5000.0
    val dtp = DriveThroughPharmacyWithQ(dtpModel, name = "DTP")
    val sim4Inputs = mapOf(
        "DTP.numPharmacists" to 2.0,
    )

    val s1 = Scenario(model = model, inputs = sim1Inputs, name = "One Worker")
    val s2 = Scenario(model = model, inputs = sim2Inputs, name = "Two Worker")
    val s3 = Scenario(model = model, inputs = sim3Inputs, name = "Three Worker")
    val s4 = Scenario(model = dtpModel, inputs = sim4Inputs, name = "DTP_Experiment")

    return listOf(s1, s2, s3, s4)
}