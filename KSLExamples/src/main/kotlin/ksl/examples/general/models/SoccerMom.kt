package ksl.examples.general.models

import ksl.modeling.entity.ProcessModel
import ksl.simulation.Model
import ksl.simulation.ModelElement

fun main(){
    val model = Model()
    val sm = SoccerMom(model)
    model.lengthOfReplication = 150.0
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

        var errandsCompleted = false

        val momProcess = process {
            println("$time> starting mom = ${this@Mom.name}")
            println("$time> mom = ${this@Mom.name} driving to game")
            delay(30.0)
            println("$time> mom = ${this@Mom.name} arrived at game")
            val daughter = Daughter(this@Mom)
            activate(daughter.daughterProcess)
            println("$time> mom = ${this@Mom.name} suspending for daughter to exit van")
            suspend("mom suspended for daughter to exit van")
            println("$time> mom = ${this@Mom.name} running errands...")
            delay(45.0)
            println("$time> mom = ${this@Mom.name} completed errands")
            errandsCompleted = true
            if (daughter.isPlaying){
                println("$time> mom, ${this@Mom.name}, mom suspending because daughter is still playing")
                suspend("mom suspended for daughter playing")
            } else {
                println("$time> mom, ${this@Mom.name}, mom resuming daughter done playing after errands")
                daughter.resumeProcess()
                suspend("mom suspended for daughter entering van")
            }
            println("$time> mom = ${this@Mom.name} driving home")
            delay(30.0)
            println("$time> mom = ${this@Mom.name} arrived home")
        }
    }

    private inner class Daughter(val mom: Mom) : Entity() {

        var isPlaying = false

        val daughterProcess = process {
            println("$time> starting daughter ${this@Daughter.name}")
            println("$time> daughter, ${this@Daughter.name}, exiting the van")
            delay(2.0)
            println("$time> daughter, ${this@Daughter.name}, exited the van")
            println("$time> daughter, ${this@Daughter.name}, resuming mom")
            mom.resumeProcess()
            println("$time> daughter, ${this@Daughter.name}, starting playing")
            isPlaying = true
            delay(30.0)
          //  delay(60.0)
            isPlaying = false
            println("$time> daughter, ${this@Daughter.name}, finished playing")
            if (!mom.errandsCompleted){
                println("$time> daughter, ${this@Daughter.name}, mom errands not completed suspending")
                suspend("daughter waiting on mom to complete errand")
            }
            println("$time> daughter, ${this@Daughter.name}, entering van")
            delay(2.0)
            println("$time> daughter, ${this@Daughter.name}, entered van")
            println("$time> daughter, ${this@Daughter.name}, entered van, resuming mom")
            mom.resumeProcess()
        }
    }
}