package examplepkg

import ksl.modeling.entity.EntityType
import ksl.modeling.entity.KSLProcess
import ksl.simulation.Model
import ksl.simulation.ModelElement

class TestProcessModeling(parent: ModelElement) : EntityType(parent, null) {

    private inner class Customer: Entity() {
        val someProcess : KSLProcess = process {
            println("\t time = $time before the first delay in ${this@Customer}")
            delay(10.0)
            println("\t time = $time after the first delay in ${this@Customer}")
            println("\t time = $time before the second delay in ${this@Customer}")
            delay(20.0)
            println("\t time = $time after the second delay in ${this@Customer}")
        }
    }

    override fun initialize() {
        val e = Customer()
        activate(e.someProcess)
        val c = Customer()
        activate(c.someProcess, 1.0)
    }
}

fun main(){
    val m = Model()
    val test = TestProcessModeling(m)

    m.lengthOfReplication = 100.0
    m.numberOfReplications = 1
    m.simulate()
}