package ksl.examples.book.chapter6.interaction

import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.Signal
import ksl.simulation.Model
import ksl.simulation.ModelElement


fun main(){
    val model = Model()
    val sm = SoccerMomViaSignal(model)
    model.lengthOfReplication = 150.0
    model.numberOfReplications = 1
    model.simulate()
    model.print()
}

class SoccerMomViaSignal(
    parent: ModelElement,
    name: String? = null
) : ProcessModel(parent, name) {

    private val daughterToExitSignal = Signal(this, name = "DaughterToExitSignal")
    private val daughterToPlaySignal = Signal(this, name = "DaughterToPlaySignal")
    private val daughterToLoadSignal = Signal(this, name = "DaughterToLoadSignal")
    private val momToShopSignal = Signal(this, name = "MomToShopSignal")

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
            waitFor(daughterToExitSignal)
            println("$time> mom = ${this@Mother.name} running errands...")
            delay(45.0)
            println("$time> mom = ${this@Mother.name} completed errands")
            if (momToShopSignal.waitingQ.contains(daughter)){
                println("$time> mom, ${this@Mother.name}, mom resuming daughter done playing after errands")
                momToShopSignal.signal(daughter)
            } else {
                println("$time> mom, ${this@Mother.name}, mom suspending because daughter is still playing")
                waitFor(daughterToPlaySignal)
            }
            waitFor(daughterToLoadSignal)
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
            daughterToExitSignal.signal(mother)
            println("$time> daughter, ${this@Daughter.name}, starting playing")
//            delay(30.0)
            delay(60.0) //TODO
            println("$time> daughter, ${this@Daughter.name}, finished playing")
            // suspend if mom isn't here
            if (daughterToPlaySignal.waitingQ.contains(mother)){
                // mom's errand was completed and mom suspended because daughter was playing
                daughterToPlaySignal.signal(mother)
            } else {
                println("$time> daughter, ${this@Daughter.name}, mom errands not completed suspending")
                waitFor(momToShopSignal)
            }
            println("$time> daughter, ${this@Daughter.name}, entering van")
            delay(2.0)
            println("$time> daughter, ${this@Daughter.name}, entered van")
            //resume mom process
            println("$time> daughter, ${this@Daughter.name}, entered van, resuming mom")
            daughterToLoadSignal.signal(mother)
        }
    }
}