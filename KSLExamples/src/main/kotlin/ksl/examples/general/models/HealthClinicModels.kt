package ksl.examples.general.models

import ksl.observers.ExperimentDataCollector
import ksl.observers.ReplicationDataCollector
import ksl.simulation.Model
import ksl.utilities.statistic.MultipleComparisonAnalyzer

fun main(){
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