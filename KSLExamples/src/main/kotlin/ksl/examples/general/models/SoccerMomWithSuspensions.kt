package ksl.examples.general.models

import ksl.modeling.entity.ProcessModel
import ksl.simulation.Model
import ksl.simulation.ModelElement


fun main(){
    val model = Model()
    val sm = SoccerMomWithSuspensions(model)
    model.lengthOfReplication = 150.0
    model.numberOfReplications = 1
    model.simulate()
}

class SoccerMomWithSuspensions(
    parent: ModelElement,
    name: String? = null
) : ProcessModel(parent, name) {


    override fun initialize() {
        val m = Mother()
        activate(m.momProcess)
    }

    private inner class Mother : Entity() {

        val daughterExiting: Suspension = Suspension(name = "Suspend for daughter to exit van")
        val daughterPlaying: Suspension = Suspension(name = "Suspend for daughter playing")
        val daughterLoading: Suspension = Suspension(name = "Suspend for daughter entering van")

        val momProcess = process {
            println("$time> starting mom = ${this@Mother.name}")
            println("$time> mom = ${this@Mother.name} driving to game")
            delay(30.0)
            println("$time> mom = ${this@Mother.name} arrived at game")
            val daughter = Daughter(this@Mother)
            activate(daughter.daughterProcess)
            println("$time> mom = ${this@Mother.name} suspending for daughter to exit van")
            //suspend mom's process
            suspendFor(daughterExiting) 
            println("$time> mom = ${this@Mother.name} running errands...")
            delay(45.0)
            println("$time> mom = ${this@Mother.name} completed errands")
            if (daughter.motherShopping.isSuspended){
                println("$time> mom, ${this@Mother.name}, mom resuming daughter done playing after errands")
                resume(daughter.motherShopping)
            } else {
                println("$time> mom, ${this@Mother.name}, mom suspending because daughter is still playing")
                suspendFor(daughterPlaying)
            }
            suspendFor(daughterLoading)
            println("$time> mom = ${this@Mother.name} driving home")
            delay(30.0)
            println("$time> mom = ${this@Mother.name} arrived home")
        }
    }

    private inner class Daughter(val mother: Mother) : Entity() {

        val motherShopping: Suspension = Suspension("Suspend for mother shopping")

        val daughterProcess = process {
            println("$time> starting daughter ${this@Daughter.name}")
            println("$time> daughter, ${this@Daughter.name}, exiting the van")
            delay(2.0)
            println("$time> daughter, ${this@Daughter.name}, exited the van")
            // resume mom process
            println("$time> daughter, ${this@Daughter.name}, resuming mom")
            resume(mother.daughterExiting)
            println("$time> daughter, ${this@Daughter.name}, starting playing")
//            delay(30.0)
            delay(60.0) //TODO
            println("$time> daughter, ${this@Daughter.name}, finished playing")
            // suspend if mom isn't here
            if (mother.daughterPlaying.isSuspended){
                // mom's errand was completed and mom suspended because daughter was playing
                resume(mother.daughterPlaying)
            } else {
                println("$time> daughter, ${this@Daughter.name}, mom errands not completed suspending")
                suspendFor(motherShopping)
            }
            println("$time> daughter, ${this@Daughter.name}, entering van")
            delay(2.0)
            println("$time> daughter, ${this@Daughter.name}, entered van")
            //TODO resume mom process
            println("$time> daughter, ${this@Daughter.name}, entered van, resuming mom")
            resume(mother.daughterLoading)
        }
    }
}