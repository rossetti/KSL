package examplepkg

import ksl.modeling.variable.RandomVariable
import ksl.simulation.ModelElement
import ksl.simulation.Simulation
import ksl.utilities.io.KSL
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.random.rvariable.UniformRV

class TestModelWork {
}

fun main() {

    val sim = Simulation()

    val m = sim.model

    m.replicationEndedOption

    val me = ModelElement(m, "something")// can only make because of internal

    // it is interesting that the rv is actually usable outside the model
    val rv = RandomVariable(m, ExponentialRV())

    rv.randomSource = NormalRV()
    rv.initialRandomSource = UniformRV()

    for (i in 1..10){
        println("$i ${rv.value}")
    }
    println()

    println(m.modelElementsAsString)

    sim.lengthOfReplication = 10.0
    sim.lengthOfWarmUp = 5.0
    sim.numberOfReplications = 3
    sim.run()

    KSL.logger.info { "Writing to the log!" }

    println(sim)
}