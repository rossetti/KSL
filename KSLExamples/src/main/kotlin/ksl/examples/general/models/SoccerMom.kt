package ksl.examples.general.models

import ksl.modeling.entity.ProcessModel
import ksl.simulation.Model
import ksl.simulation.ModelElement

fun main(){
    val model = Model()
    val sm = SoccerMom(model)
    model.lengthOfReplication = 120.0
    model.numberOfReplications = 1
    model.simulate()
}

class SoccerMom(
    parent: ModelElement,
    name: String? = null
) : ProcessModel(parent, name) {


    override fun initialize() {
        val m = Mom()
        activate(m.momProcess)
    }

    private inner class Mom : Entity() {

        val momProcess = process {
            println("$time> starting mom = ${this@Mom.name}")
            println("$time> mom = ${this@Mom.name} driving to game")
            delay(30.0)
            println("$time> mom = ${this@Mom.name} arrived at game")
            val daughter = Daughter(this@Mom)
            activate(daughter.daughterProcess)
            println("$time> mom = ${this@Mom.name} suspending for daughter to exit van")
            //TODO suspend mom's process
            println("$time> mom = ${this@Mom.name} running errands...")
            delay(45.0)
            println("$time> mom = ${this@Mom.name} completed errands")
            //TODO suspend if daughter isn't done playing

            println("$time> mom = ${this@Mom.name} driving home")
            delay(30.0)
            println("$time> mom = ${this@Mom.name} arrived home")
        }
    }

    private inner class Daughter(val mom: Mom) : Entity() {

        val daughterProcess = process {
            println("$time> starting daughter ${this@Daughter.name}")
            println("$time> daughter, ${this@Daughter.name}, exiting the van")
            delay(2.0)
            println("$time> daughter, ${this@Daughter.name}, exited the van")
            //TODO resume mom process
            println("$time> daughter, ${this@Daughter.name}, starting playing")
            delay(60.0)
            println("$time> daughter, ${this@Daughter.name}, finished playing")
            //TODO suspend if mom isn't here
            println("$time> daughter, ${this@Daughter.name}, entering van")
            delay(2.0)
            println("$time> daughter, ${this@Daughter.name}, entered van")
            //TODO resume mom process
        }
    }
}