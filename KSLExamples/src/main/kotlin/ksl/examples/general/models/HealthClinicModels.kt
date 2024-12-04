package ksl.examples.general.models

import ksl.controls.experiments.Scenario
import ksl.controls.experiments.ScenarioRunner
import ksl.observers.ReplicationDataCollector
import ksl.simulation.Model
import ksl.utilities.statistic.MultipleComparisonAnalyzer

fun main(){

   // usingReplicationDataCollector()
    usingScenarios()

}

fun usingReplicationDataCollector(){
    val modelA = Model("Clinic A Model")
    val clinicA = ClinicDesignA(modelA, "Design A")
    modelA.numberOfReplications = 528

    val rdc1 = ReplicationDataCollector(modelA)
    rdc1.addResponse(clinicA.systemTime)
    modelA.simulate()
    modelA.print()

    val modelB = Model("Clinic B Model")
    val clinicB = ClinicDesignB(modelB, "Design B")
    modelB.resetStartStream()
    modelB.numberOfReplications = 528

    val rdc2 = ReplicationDataCollector(modelB)
    rdc2.addResponse(clinicB.systemTime)

    modelB.simulate()
    modelB.print()

    val dataA = rdc1.replicationData(clinicA.systemTime.name)
    val dataB = rdc2.replicationData(clinicB.systemTime.name)

    val dataMap = mutableMapOf(
        "Design A System Time" to dataA, "Design B System Time" to dataB )

    val mc = MultipleComparisonAnalyzer(dataMap)

    println(mc)
}

fun usingScenarios(){
    val modelA = Model("Clinic A Model")
    val clinicA = ClinicDesignA(modelA, "Clinic")
    modelA.numberOfReplications = 528

    val modelB = Model("Clinic B Model")
    val clinicB = ClinicDesignB(modelB, "Clinic")
    modelB.resetStartStream()
    modelB.numberOfReplications = 528

    val s1 = Scenario(model = modelA,  name = "DesignA")
    val s2 = Scenario(model = modelB,  name = "DesignB")
    val list = listOf(s1, s2)
    val scenarioRunner = ScenarioRunner("HealthClinicScenarios", list)
    scenarioRunner.simulate()

    val expNames = listOf("DesignA", "DesignB")
    val responseName = "Clinic:TimeInSystem"
    val mc = scenarioRunner.kslDb.multipleComparisonAnalyzerFor(expNames, responseName)
    println(mc)
}