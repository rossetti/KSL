package ksl.examples.general.running

import ksl.controls.experiments.Scenario
import ksl.controls.experiments.ScenarioRunner
import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.Model


fun main(){
    val scenarioRunner = ScenarioRunner("ScenarioRunner", buildScenarios())
    scenarioRunner.simulate()
    for(s in scenarioRunner.scenarioList) {
        val sr = s.simulationRun?.statisticalReporter()
        val r = sr?.halfWidthSummaryReport(title = s.name)
        println(r)
        println()
    }

    //this repeated call cause experiment name error because it should
//    scenarioRunner.simulate(clearAllData = false)
//    for(s in scenarioRunner.scenarioList) {
//        val sr = s.simulationRun?.statisticalReporter()
//        val r = sr?.halfWidthSummaryReport(title = s.experimentName)
//        println(r)
//        println()
//    }
}

fun buildScenarios() : List<Scenario> {
    val sim1 = Model("MM1 Test1", autoCSVReports = true)
    sim1.numberOfReplications = 3
    sim1.lengthOfReplication = 100.0
    sim1.lengthOfReplicationWarmUp = 50.0
    GIGcQueue(sim1, 1, name = "MM1Q")
    val sim1Inputs = mapOf("MM1Q.numServers" to 2.0)

    val sim2 = Model("MM1 Test2", autoCSVReports = true)
    sim2.numberOfReplications = 10
    sim2.lengthOfReplication = 1000.0
    sim2.lengthOfReplicationWarmUp = 50.0
    GIGcQueue(sim2, 1, name = "MM1Q")
    val sim2Inputs = mapOf("MM1Q.numServers" to 3.0)

    val s1 = Scenario(model = sim1, inputs = sim1Inputs, name = "MM1_Test1")
    val s2 = Scenario(model = sim2, inputs = sim2Inputs, name = "MM1_Test2")
    val list = listOf(s1, s2)
    return list
}