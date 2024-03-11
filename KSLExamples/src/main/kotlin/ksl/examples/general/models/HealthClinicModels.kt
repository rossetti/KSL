package ksl.examples.general.models

import ksl.simulation.Model

fun main(){
    val modelA = Model("Clinic A Model")
    val clinicA = ClinicDesignA(modelA, "Design A")
    modelA.numberOfReplications = 10
    modelA.simulate()
    modelA.print()

    val modelB = Model("Clinic B Model")
    val clinicB = ClinicDesignB(modelB, "Design B")
    modelB.resetStartStream()
    modelB.numberOfReplications = 10
    modelB.simulate()
    modelB.print()

}