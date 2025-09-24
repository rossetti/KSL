package ksl.examples.book.chapter6.interaction

import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.ProcessModel
import ksl.simulation.Model
import ksl.simulation.ModelElement


fun main(){
    val model = Model()
    val sm = SoccerMomViaSyncWith(model)
    model.lengthOfReplication = 150.0
    model.numberOfReplications = 1
    model.simulate()
    model.print()
}

class SoccerMomViaSyncWith(
    parent: ModelElement,
    name: String? = null
) : ProcessModel(parent, name) {

    private val waitForDaughterToExitQ = HoldQueue(this, name = "WaitForDaughterToExitQ")
    private val waitForDaughterToPlayQ = HoldQueue(this, name = "WaitForDaughterToPlayQ")
    private val waitForDaughterToLoadQ = HoldQueue(this, name = "WaitForDaughterToLoadQ")
    private val waitForMomToShopQ = HoldQueue(this, name = "WaitForMomToShopQ")
    private val syncQ = HoldQueue(this, name = "SyncQ")

    override fun initialize() {
        val m = Mother()
        activate(m.momProcess)
    }

    private inner class Mother : Entity() {
        val momProcess = process {
            println("$time> starting mom = ${this@Mother.name}")
            println("$time> mom = ${this@Mother.name} driving to game")
            delay(30.0)
            println("$time> mom = ${this@Mother.name} arrived at game")
            val daughter = Daughter(this@Mother)
            activate(daughter.daughterProcess)
            println("$time> mom = ${this@Mother.name} suspending for daughter to exit van")
            //suspend mom's process
            syncWith(daughter, syncQ)
            println("$time> mom = ${this@Mother.name} running errands...")
            delay(45.0)
            println("$time> mom = ${this@Mother.name} completed errands")
            syncWith(daughter, syncQ)
            syncWith(daughter, syncQ)
            println("$time> mom = ${this@Mother.name} driving home")
            delay(30.0)
            println("$time> mom = ${this@Mother.name} arrived home")
        }
    }

    private inner class Daughter(val mother: Mother) : Entity() {

        val daughterProcess = process {
            println("$time> starting daughter ${this@Daughter.name}")
            println("$time> daughter, ${this@Daughter.name}, exiting the van")
            delay(2.0)
            println("$time> daughter, ${this@Daughter.name}, exited the van")
            // resume mom process
            println("$time> daughter, ${this@Daughter.name}, resuming mom")
            syncWith(mother, syncQ)
            println("$time> daughter, ${this@Daughter.name}, starting playing")
 //           delay(30.0)
            delay(60.0) //TODO
            println("$time> daughter, ${this@Daughter.name}, finished playing")
            syncWith(mother, syncQ)
            println("$time> daughter, ${this@Daughter.name}, entering van")
            delay(2.0)
            println("$time> daughter, ${this@Daughter.name}, entered van")
            //TODO resume mom process
            println("$time> daughter, ${this@Daughter.name}, entered van, resuming mom")
            syncWith(mother, syncQ)
        }
    }
}