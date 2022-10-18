package examplepkg

import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

class TestWaitForProcess(parent: ModelElement) : ProcessModel(parent, null) {
    private val worker: ResourceWithQ = ResourceWithQ(this, "worker", 1)
    private val tba = RandomVariable(this, ExponentialRV(6.0, 1), "Arrival RV")
    private val st = RandomVariable(this, ExponentialRV(3.0, 2), "Service RV")
    private val wip = TWResponse(this, "${name}:WIP")
    private val tip = Response(this, "${name}:TimeInSystem")
    private val arrivals = Arrivals()
    private val total = 2
    private var n = 1

    private inner class Customer: Entity() {
        val mm1: KSLProcess = process("MM1"){
            println("\t $time > starting mm1 process for ${this@Customer}")
            wip.increment()
            timeStamp = time
            val a  = seize(worker)
            delay(st)
            release(a)
            tip.value = time - timeStamp
            wip.decrement()
            println("\t $time > completed mm1 process for ${this@Customer}")
        }

        val wfp = process{
            val c = Customer()
            println("$time > before waitFor process")
            schedule(KillIt(), .1, this@Customer)
            waitFor(c.mm1)
            println("$time > after waitFor process")
        }
    }

    private inner class KillIt: EventAction<Customer>() {
        override fun action(event: KSLEvent<Customer>) {
            val c = event.message
            if (c!!.isSuspended){
                c.terminateProcess()
            }
        }
    }

    override fun initialize() {
        arrivals.schedule(tba)
    }

    private inner class Arrivals: EventAction<Nothing>(){
        override fun action(event: KSLEvent<Nothing>) {
            if (n <= total) {
                val c = Customer()
//                schedule(KillIt(), .1, c)
                activate(c.wfp)
                schedule(tba)
                n++
            }
        }
    }

}

fun main(){
    val m = Model()
    val test = TestWaitForProcess(m)
    m.numberOfReplications = 1
    m.lengthOfReplication = 200.0
//    m.lengthOfReplicationWarmUp = 5000.0
    m.simulate()
    m.print()
}