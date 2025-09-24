package ksl.examples.book.chapter6.interaction

import ksl.modeling.entity.ProcessModel
import ksl.simulation.Model
import ksl.simulation.ModelElement

fun main(){
    val model = Model()
 //   val sm = SoccerMomViaBlockage(model)
    val sm = SoccerMomViaBlockageNoPrinting(model)
    model.lengthOfReplication = 150.0
    model.numberOfReplications = 1
    model.simulate()
}

class SoccerMomViaBlockage(
    parent: ModelElement,
    name: String? = null
) : ProcessModel(parent, name) {


    override fun initialize() {
        val m = Mom()
        activate(m.momProcess)
    }

    private inner class Mom : Entity() {

        var errandsCompleted = false
        val shopping: Blockage = Blockage("shopping")

        val momProcess = process {
            println("$time> starting mom = ${this@Mom.name}")
            println("$time> mom = ${this@Mom.name} driving to game")
            delay(30.0)
            println("$time> mom = ${this@Mom.name} arrived at game")
            val daughter = Daughter(this@Mom)
            activate(daughter.daughterProcess)
            println("$time> mom = ${this@Mom.name} suspending for daughter to exit van")
            waitFor(daughter.unloading)
            startBlockage(shopping)
            println("$time> mom = ${this@Mom.name} running errands...")
            delay(45.0)
            println("$time> mom = ${this@Mom.name} completed errands")
            clearBlockage(shopping)
            errandsCompleted = true
            if (daughter.isPlaying){
                daughter.playing.isActive
                println("$time> mom, ${this@Mom.name}, mom suspending because daughter is still playing")
            } else {
                println("$time> mom, ${this@Mom.name}, mom resuming daughter done playing after errands")
            }
            waitFor(daughter.playing)
            waitFor(daughter.loading)
            println("$time> mom = ${this@Mom.name} driving home")
            delay(30.0)
            println("$time> mom = ${this@Mom.name} arrived home")
        }
    }

    private inner class Daughter(val mom: Mom) : Entity() {

        var isPlaying = false
        val unloading: Blockage = Blockage("unloading")
        val playing: Blockage = Blockage("playing")
        val loading: Blockage = Blockage("loading")

        val daughterProcess = process {
            println("$time> starting daughter ${this@Daughter.name}")
            println("$time> daughter, ${this@Daughter.name}, exiting the van")
            startBlockage(unloading)
            delay(2.0)
            println("$time> daughter, ${this@Daughter.name}, exited the van")
            clearBlockage(unloading)
            println("$time> daughter, ${this@Daughter.name}, resuming mom")
            println("$time> daughter, ${this@Daughter.name}, starting playing")
            startBlockage(playing)
            isPlaying = true
           // delay(30.0)
            delay(60.0) //TODO
            isPlaying = false
            println("$time> daughter, ${this@Daughter.name}, finished playing")
            clearBlockage(playing)

            if (!mom.errandsCompleted){
                println("$time> daughter, ${this@Daughter.name}, mom errands not completed suspending")
//                suspend("daughter waiting on mom to complete errand")
            }else {
                // mom's errand was completed and mom suspended because daughter was playing
//                mom.resumeProcess()
            }
            waitFor(mom.shopping)
            startBlockage(loading)
            println("$time> daughter, ${this@Daughter.name}, entering van")
            delay(2.0)
            println("$time> daughter, ${this@Daughter.name}, entered van")
            clearBlockage(loading)
            println("$time> daughter, ${this@Daughter.name}, entered van, resuming mom")
//            mom.resumeProcess()
        }
    }
}

class SoccerMomViaBlockageNoPrinting(
    parent: ModelElement,
    name: String? = null
) : ProcessModel(parent, name) {


    override fun initialize() {
        val m = Mom()
        activate(m.momProcess)
    }

    private inner class Mom : Entity() {
        val shopping: Blockage = Blockage("shopping")

        val momProcess = process {
            delay(30.0)
            val daughter = Daughter(this@Mom)
            activate(daughter.daughterProcess)
            waitFor(daughter.unloading)
            startBlockage(shopping)
            delay(45.0)
            clearBlockage(shopping)
            waitFor(daughter.playing)
            waitFor(daughter.loading)
            delay(30.0)
            println("$time> mom = ${this@Mom.name} arrived home")
        }
    }

    private inner class Daughter(val mom: Mom) : Entity() {
        val unloading: Blockage = Blockage("unloading")
        val playing: Blockage = Blockage("playing")
        val loading: Blockage = Blockage("loading")

        val daughterProcess = process {
            startBlockage(unloading)
            delay(2.0)
            clearBlockage(unloading)
            startBlockage(playing)
            // delay(30.0)
            delay(60.0) //TODO
            clearBlockage(playing)
            waitFor(mom.shopping)
            startBlockage(loading)
            delay(2.0)
            clearBlockage(loading)
        }
    }
}