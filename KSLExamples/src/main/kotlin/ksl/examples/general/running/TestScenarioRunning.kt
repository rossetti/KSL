package ksl.examples.general.running

import ksl.controls.experiments.ScenarioRunner
import ksl.simulation.Model


fun main(){
   // val scenarioRunner = ScenarioRunner()


}

fun buildScenarios() {
    val sim1 = Model("MM1 Test1")
    sim1.numberOfReplications = 3
    sim1.lengthOfReplication = 100.0
    sim1.lengthOfReplicationWarmUp = 50.0
    GIGcQueue(sim1, 1, name = "MM1Q")

    val sim2 = Model("MM1 Test2")
    sim2.numberOfReplications = 10
    sim2.lengthOfReplication = 1000.0
    sim2.lengthOfReplicationWarmUp = 50.0
    GIGcQueue(sim2, 1, name = "MM1Q")

    
}