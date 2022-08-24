package examplepkg

import ksl.modeling.variable.RandomVariable
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.io.KSL
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.random.rvariable.UniformRV

class TestModelWork {
}

fun main() {

    val m = Model()

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

    m.lengthOfReplication = 10.0
    m.lengthOfReplicationWarmUp = 5.0
    m.numberOfReplications = 3
    m.simulate()

    KSL.logger.info { "Writing to the log!" }

}