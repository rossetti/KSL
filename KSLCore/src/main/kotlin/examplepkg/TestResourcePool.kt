package examplepkg

import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ResourcePoolWithQ
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

class TestResourcePool(parent: ModelElement) : ProcessModel(parent, null) {
    private val pool: ResourcePoolWithQ = ResourcePoolWithQ(this, 3, name = "pool")
    private val tba = RandomVariable(this, ExponentialRV(6.0, 1), "Arrival RV")
    private val st = RandomVariable(this, ExponentialRV(3.0, 2), "Service RV")
    private val wip = TWResponse(this, "${name}:WIP")
    private val tip = Response(this, "${name}:TimeInSystem")
    private val arrivals = Arrivals()

    private inner class Customer: Entity() {
        val mm1: KSLProcess = process("MM1"){
            wip.increment()
            timeStamp = time
            val a  = seize(pool, 3)
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
    val test = TestResourcePool(m)
    m.numberOfReplications = 30
    m.lengthOfReplication = 20000.0
    m.lengthOfReplicationWarmUp = 5000.0
    m.simulate()
    m.print()
}