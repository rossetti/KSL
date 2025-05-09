package ksl.examples.book.chapter7

import ksl.modeling.elements.EventGeneratorCIfc
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.*
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.io.KSL
import ksl.utilities.io.MarkDown
import ksl.utilities.random.rvariable.ExponentialRV

fun main() {
    val m = Model()
    val tq = TandemQueueWithBlocking(m, name = "TandemQModelWithBlocking")

    m.numberOfReplications = 30
    m.lengthOfReplication = 20000.0
    m.lengthOfReplicationWarmUp = 5000.0
    m.simulate()
    m.print()
    val r = m.simulationReporter
    r.writeHalfWidthSummaryReportAsMarkDown(KSL.out, df = MarkDown.D3FORMAT)

}

class TandemQueueWithBlocking(parent: ModelElement, name: String? = null) : ProcessModel(parent, name) {

    private val buffer: ResourceWithQ = ResourceWithQ(this, "buffer", capacity = 1)
    private val worker1: ResourceWithQ = ResourceWithQ(this, "worker1")
    private val worker2: ResourceWithQ = ResourceWithQ(this, "worker2")

    private val tba = ExponentialRV(2.0, 1)

    private val st1 = RandomVariable(this, ExponentialRV(0.7, 2))
    val service1RV: RandomVariableCIfc
        get() = st1
    private val st2 = RandomVariable(this, ExponentialRV(0.9, 3))
    val service2RV: RandomVariableCIfc
        get() = st2
    private val myArrivalGenerator = EntityGenerator(::Customer, tba, tba)
    val generator: EventGeneratorCIfc
        get() = myArrivalGenerator

    private val wip: TWResponse = TWResponse(this, "${this.name}:NumInSystem")
    val numInSystem: TWResponseCIfc
        get() = wip
    private val timeInSystem: Response = Response(this, "${this.name}:TimeInSystem")
    val systemTime: ResponseCIfc
        get() = timeInSystem

    private inner class Customer : Entity() {
        val tandemQProcess: KSLProcess = process(isDefaultProcess = true) {
            wip.increment()
            timeStamp = time
            val a1 = seize(worker1)
            delay(st1)
            val b = seize(buffer)
            release(a1)
            val a2 = seize(worker2)
            release(b)
            delay(st2)
            release(a2)
            timeInSystem.value = time - timeStamp
            wip.decrement()
        }
    }
}