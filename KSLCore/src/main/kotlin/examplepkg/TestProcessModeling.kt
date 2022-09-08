package examplepkg

import ksl.modeling.entity.EntityType
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.Resource
import ksl.simulation.Model
import ksl.simulation.ModelElement

class TestProcessModeling(parent: ModelElement) : EntityType(parent, null) {

    val resource: Resource = Resource(this, "test resource")
    private inner class Customer: Entity() {
        val someProcess : KSLProcess = process("test") {
            println("\t time = $time before the first delay in ${this@Customer}")
            delay(10.0)
            println("\t time = $time after the first delay in ${this@Customer}")
            println("\t time = $time before the second delay in ${this@Customer}")
            delay(20.0)
            println("\t time = $time after the second delay in ${this@Customer}")
        }

        val seizeTest: KSLProcess = process("test seize"){
            val a  = seize(resource)
            delay(10.0)
            release(a)
        }
    }

    override fun initialize() {
        val e = Customer()
//        activate(e.someProcess)
        val c = Customer()
//        activate(c.someProcess, 1.0)

        val t = Customer()
        activate(t.seizeTest)
        activate(c.seizeTest, 1.0)
    }
}

fun main(){
    val m = Model()
    val test = TestProcessModeling(m)

    m.lengthOfReplication = 100.0
    m.numberOfReplications = 1
    m.simulate()
}