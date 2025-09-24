package ksl.examples.book.chapter6.interaction

import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.ProcessModel
import ksl.simulation.Model
import ksl.simulation.ModelElement


fun main(){
    val model = Model()
    val sm = SoccerMomViaHoldQ(model)
    model.lengthOfReplication = 150.0
    model.numberOfReplications = 1
    model.simulate()
    model.print()
}

class SoccerMomViaHoldQ(
    parent: ModelElement,
    name: String? = null
) : ProcessModel(parent, name) {

    private val waitForDaughterToExitQ = HoldQueue(this, name = "WaitForDaughterToExitQ")
    private val waitForDaughterToPlayQ = HoldQueue(this, name = "WaitForDaughterToPlayQ")
    private val waitForDaughterToLoadQ = HoldQueue(this, name = "WaitForDaughterToLoadQ")
    private val waitForMomToShopQ = HoldQueue(this, name = "WaitForMomToShopQ")

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
            hold(waitForDaughterToExitQ)
            println("$time> mom = ${this@Mother.name} running errands...")
            delay(45.0)
            println("$time> mom = ${this@Mother.name} completed errands")
            if (waitForMomToShopQ.contains(daughter)){
                println("$time> mom, ${this@Mother.name}, mom resuming daughter done playing after errands")
                waitForMomToShopQ.removeAndResume(daughter)
            } else {
                println("$time> mom, ${this@Mother.name}, mom suspending because daughter is still playing")
                hold(waitForDaughterToPlayQ)
            }
            hold(waitForDaughterToLoadQ)
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
            waitForDaughterToExitQ.removeAndResume(mother)
            println("$time> daughter, ${this@Daughter.name}, starting playing")
            delay(30.0)
//            delay(60.0) //TODO
            println("$time> daughter, ${this@Daughter.name}, finished playing")
            // suspend if mom isn't here
            if (waitForDaughterToPlayQ.contains(mother)){
                // mom's errand was completed and mom suspended because daughter was playing
                waitForDaughterToPlayQ.removeAndResume(mother)
            } else {
                println("$time> daughter, ${this@Daughter.name}, mom errands not completed suspending")
                hold(waitForMomToShopQ)
            }
            println("$time> daughter, ${this@Daughter.name}, entering van")
            delay(2.0)
            println("$time> daughter, ${this@Daughter.name}, entered van")
            //TODO resume mom process
            println("$time> daughter, ${this@Daughter.name}, entered van, resuming mom")
            waitForDaughterToLoadQ.removeAndResume(mother)
        }
    }
}