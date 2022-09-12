package examplepkg

import ksl.modeling.entity.EntityType
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.Resource
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

class SimpleProcessQ(parent: ModelElement) : EntityType(parent, null) {

    val worker: Resource = Resource(this, "worker")
    val tba = RandomVariable(this, ExponentialRV(6.0, 1))
    val st = RandomVariable(this, ExponentialRV(3.0, 2))
    val wip = TWResponse(this, "${name}:WIP")
    val tip = Response(this, "${name}:TimeInSystem")
    private val arrivals = Arrivals()

    private inner class Customer: Entity() {
        val mm1: KSLProcess = process("MM1"){
            wip.increment()
            timeStamp = time
            val a  = seize(worker)
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
    val test = SimpleProcessQ(m)
    m.numberOfReplications = 30
    m.lengthOfReplication = 20000.0
    m.lengthOfReplicationWarmUp = 5000.0
    m.simulate()
}