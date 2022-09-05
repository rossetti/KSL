package examplepkg

import ksl.modeling.entity.EntityType
import ksl.modeling.entity.KSLProcess
import ksl.simulation.Model
import ksl.simulation.ModelElement

class TestProcessModeling(parent: ModelElement) : EntityType(parent, null) {

    private inner class Customer: Entity() {
        val someProcess : KSLProcess = process {
            delay(10.0)
            delay(20.0)
        }
    }

    override fun initialize() {
        val e = Customer()
        activate(e.someProcess)
    }
}

fun main(){
    val m = Model()
    val test = TestProcessModeling(m)

    m.lengthOfReplication = 100.0
    m.numberOfReplications = 1
    m.simulate()
}