package examplepkg

import ksl.examples.book.chapter6.SchedulingEventExamples
import ksl.modeling.entity.EntityType
import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.Resource
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement

class TestProcessModeling(parent: ModelElement) : EntityType(parent, null) {

    val resource: Resource = Resource(this, "test resource")

    val holdQueue = HoldQueue(this, "hold")

    private val myEventActionOne: EventActionOne = EventActionOne()

    private inner class Customer: Entity() {
        val someProcess : KSLProcess = process("test") {
            println("time = $time before the first delay in ${this@Customer}")
            hold(holdQueue)
            delay(10.0)
            println("time = $time after the first delay in ${this@Customer}")
            println("time = $time before the second delay in ${this@Customer}")
            delay(20.0)
            println("time = $time after the second delay in ${this@Customer}")
        }

        val seizeTest: KSLProcess = process("test seize"){
            val a  = seize(resource)
            delay(10.0)
            release(a)
        }
    }

    private var customer: Customer? = null

    override fun initialize() {
        val e = Customer()
        customer = e
        activate(e.someProcess)
        val c = Customer()
        activate(c.someProcess, 1.0)

//        val t = Customer()
//        activate(t.seizeTest)
//        activate(c.seizeTest, 1.0)
        schedule(myEventActionOne, 5.0)
    }

    private inner class EventActionOne : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            println("EventActionOne at time : $time")
           // customer?.terminateProcess()
            holdQueue.removeAllAndResume()
        }
    }
}

fun main(){
    val m = Model()
    val test = TestProcessModeling(m)

    m.lengthOfReplication = 100.0
    m.numberOfReplications = 1
    m.simulate()
}