package examplepkg

import ksl.modeling.entity.CapacitySchedule
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

class TestResourceSchedule(parent: ModelElement) : ProcessModel(parent, null) {
    private val resource: ResourceWithQ = ResourceWithQ(this, name = "Resource", capacity = 1)
    private val tba = RandomVariable(this, ExponentialRV(6.0, 1), "Arrival RV")
    private val st = RandomVariable(this, ExponentialRV(3.0, 2), "Service RV")
    private val wip = TWResponse(this, "${name}:WIP")
    private val tip = Response(this, "${name}:TimeInSystem")
    private val arrivals = Arrivals()
    private val schedule: CapacitySchedule

    init {
        schedule= CapacitySchedule(this, 0.0)
        schedule.addItem(capacity = 2, duration = 15.0)
        schedule.addItem(capacity = 1, duration = 35.0)
        schedule.addItem(capacity = 0, duration = 20.0)
        schedule.addItem(capacity = 2, duration = 30.0)
        resource.useSchedule(schedule)
    }

    private inner class Customer: Entity() {
        val mm1: KSLProcess = process("MM1"){
            wip.increment()
            timeStamp = time
            val a  = seize(resource, 1)
            delay(st)
            release(a)
            tip.value = time - timeStamp
            wip.decrement()
        }
    }

    override fun initialize() {
        arrivals.schedule(tba)
    }

    private inner class Arrivals: EventAction<Nothing>(){
        override fun action(event: KSLEvent<Nothing>) {
            val c = Customer()
            activate(c.mm1)
            schedule(tba)
        }
    }

}

fun main(){
    val m = Model()
    val test = TestResourceSchedule(m)
    m.numberOfReplications = 2
    m.lengthOfReplication = 100.0
    m.simulate()
    m.print()
}