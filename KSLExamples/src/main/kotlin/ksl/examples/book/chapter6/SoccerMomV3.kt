package ksl.examples.book.chapter6

import ksl.modeling.entity.ProcessModel
import ksl.simulation.Model
import ksl.simulation.ModelElement


fun main(){
    val model = Model()
    val sm = SoccerMomV3(model)
    model.lengthOfReplication = 150.0
    model.numberOfReplications = 1
    model.simulate()
}

/**
 * A variant of the soccer-mom process-interaction scenario ([SoccerMomWithSuspensions]) that coordinates
 * the mother and daughter using boolean state flags plus on-demand `Suspension` objects rather than
 * pre-created ones. Same rendezvous around drop-off, play, errands, and pickup. Console-traced.
 */
class SoccerMomV3(
    parent: ModelElement,
    name: String? = null
) : ProcessModel(parent, name) {


    override fun initialize() {
        val m = Mother()
        activate(m.momProcess)
    }

    private inner class Mother : Entity() {

        var errandsCompleted = false
        val forDaughterExiting: Suspension = Suspension(name = "Suspend for daughter to exit van")
        var forDaughterPlaying: Suspension? = null
        var forDaughterLoading: Suspension? = null

        val momProcess = process {
            println("$time> starting mom = ${this@Mother.name}")
            println("$time> mom = ${this@Mother.name} driving to game")
            delay(30.0)
            println("$time> mom = ${this@Mother.name} arrived at game")
            val daughter = Daughter(this@Mother)
            activate(daughter.daughterProcess)
            println("$time> mom = ${this@Mother.name} suspending for daughter to exit van")
            //suspend mom's process
            suspendFor(forDaughterExiting) //TODO suspendFor instead??
            println("$time> mom = ${this@Mother.name} running errands...")
            delay(45.0)
            println("$time> mom = ${this@Mother.name} completed errands")
            errandsCompleted = true
            // suspend if daughter isn't done playing
            if (daughter.isPlaying){
                println("$time> mom, ${this@Mother.name}, mom suspending because daughter is still playing")
                forDaughterPlaying = Suspension(name = "Suspend for daughter playing")
                suspendFor(forDaughterPlaying!!)
            } else {
                println("$time> mom, ${this@Mother.name}, mom resuming daughter done playing after errands")
                resume(daughter.forMotherShopping!!)
            }
            forDaughterLoading = Suspension(name = "Suspend for daughter entering van")
            suspendFor(forDaughterLoading!!)
            println("$time> mom = ${this@Mother.name} driving home")
            delay(30.0)
            println("$time> mom = ${this@Mother.name} arrived home")
        }
    }

    private inner class Daughter(val mother: Mother) : Entity() {

        var isPlaying = false
        var forMotherShopping: Suspension? = null

        val daughterProcess = process {
            println("$time> starting daughter ${this@Daughter.name}")
            println("$time> daughter, ${this@Daughter.name}, exiting the van")
            delay(2.0)
            println("$time> daughter, ${this@Daughter.name}, exited the van")
            // resume mom process
            println("$time> daughter, ${this@Daughter.name}, resuming mom")
            resume(mother.forDaughterExiting)
            println("$time> daughter, ${this@Daughter.name}, starting playing")
            isPlaying = true
            delay(30.0)
           // delay(60.0) //TODO
            isPlaying = false
            println("$time> daughter, ${this@Daughter.name}, finished playing")
            // suspend if mom isn't here
            if (!mother.errandsCompleted){
                println("$time> daughter, ${this@Daughter.name}, mom errands not completed suspending")
                forMotherShopping = Suspension("Suspend for mother shopping")
                suspendFor(forMotherShopping!!)
            } else {
                // mom's errand was completed and mom suspended because daughter was playing
                resume(mother.forDaughterPlaying!!)
            }
            println("$time> daughter, ${this@Daughter.name}, entering van")
            delay(2.0)
            println("$time> daughter, ${this@Daughter.name}, entered van")
            //TODO resume mom process
            println("$time> daughter, ${this@Daughter.name}, entered van, resuming mom")
            resume(mother.forDaughterLoading!!)
        }
    }
}