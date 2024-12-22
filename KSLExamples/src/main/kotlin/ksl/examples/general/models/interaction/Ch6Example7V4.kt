package ksl.examples.general.models.interaction

import ksl.modeling.entity.ProcessModel
import ksl.simulation.Model
import ksl.simulation.ModelElement

fun main(){
    val model = Model()
    val sm = SoccerMomV4(model)
    model.lengthOfReplication = 150.0
    model.numberOfReplications = 2
    model.simulate()
}

class SoccerMomV4(
    parent: ModelElement,
    name: String? = null
) : ProcessModel(parent, name) {


    override fun initialize() {
        val m = Mom()
        activate(m.momProcess)
    }

    private inner class Mom : Entity() {

        var errandsCompleted = false
        val shopping: BlockingActivity = BlockingActivity(45.0)

        val momProcess = process {
            println("$time> starting mom = ${this@Mom.name}")
            println("$time> mom = ${this@Mom.name} driving to game")
            delay(30.0)
            println("$time> mom = ${this@Mom.name} arrived at game")
            val daughter = Daughter(this@Mom)
            activate(daughter.daughterProcess)
            println("$time> mom = ${this@Mom.name} suspending for daughter to exit van")
            waitFor(daughter.unloading)
            println("$time> mom = ${this@Mom.name} running errands...")
            perform(shopping)
            println("$time> mom = ${this@Mom.name} completed errands")
            errandsCompleted = true
            if (daughter.isPlaying){
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

        val unloading: BlockingActivity = BlockingActivity(2.0)
        //val playing: BlockingActivity = BlockingActivity(30.0)
        val playing: BlockingActivity = BlockingActivity(60.0)
        val loading: BlockingActivity = BlockingActivity(2.0)

        val daughterProcess = process {
            println("$time> starting daughter ${this@Daughter.name}")
            println("$time> daughter, ${this@Daughter.name}, exiting the van")
            perform(unloading)
            println("$time> daughter, ${this@Daughter.name}, exited the van")
            println("$time> daughter, ${this@Daughter.name}, resuming mom")
            println("$time> daughter, ${this@Daughter.name}, starting playing")
            isPlaying = true
            perform(playing)
            isPlaying = false
            println("$time> daughter, ${this@Daughter.name}, finished playing")
            if (!mom.errandsCompleted){
                println("$time> daughter, ${this@Daughter.name}, mom errands not completed suspending")
//                suspend("daughter waiting on mom to complete errand")
            }else {
                // mom's errand was completed and mom suspended because daughter was playing
//                mom.resumeProcess()
            }
            waitFor(mom.shopping)
            println("$time> daughter, ${this@Daughter.name}, entering van")
            perform(loading)
            println("$time> daughter, ${this@Daughter.name}, entered van")
            println("$time> daughter, ${this@Daughter.name}, entered van, resuming mom")
//            mom.resumeProcess()
        }
    }
}