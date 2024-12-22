package ksl.examples.general.models.interaction

import ksl.modeling.entity.ProcessModel
import ksl.simulation.Model
import ksl.simulation.ModelElement

fun main(){
    val model = Model()
    val sm = SoccerMomViaBlockage(model)
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
                println("$time> mom, ${this@Mom.name}, mom suspending because daughter is still playing")
//                suspend("mom suspended for daughter playing")
            } else {
                println("$time> mom, ${this@Mom.name}, mom resuming daughter done playing after errands")
//                daughter.resumeProcess()
            }
            waitFor(daughter.playing)
 //           suspend("mom suspended for daughter entering van")
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
            //TODO resume mom process
            println("$time> daughter, ${this@Daughter.name}, resuming mom")
 //           mom.resumeProcess()
            println("$time> daughter, ${this@Daughter.name}, starting playing")
            startBlockage(playing)
            isPlaying = true
            delay(30.0)
         //   delay(60.0) //TODO
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