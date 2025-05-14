package ksl.examples.book.chapter7

import ksl.modeling.elements.EventGeneratorCIfc
import ksl.modeling.elements.EventGeneratorRVCIfc
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.*
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.io.KSL
import ksl.utilities.io.MarkDown
import ksl.utilities.random.rvariable.ExponentialRV

fun main(){

//    version1()
    version2()
}

fun version1(){
    val m = Model()
    val tq = TandemQueue(m, name = "TandemQModel")

    m.numberOfReplications = 30
    m.lengthOfReplication = 20000.0
    m.lengthOfReplicationWarmUp = 5000.0
    m.simulate()
    m.print()
}

fun version2(){
    val m = Model()
    val tq = TandemQueueV2(m, name = "TandemQModel")

    m.numberOfReplications = 30
    m.lengthOfReplication = 20000.0
    m.lengthOfReplicationWarmUp = 5000.0
    m.simulate()
    m.print()
    val r = m.simulationReporter
    r.writeHalfWidthSummaryReportAsMarkDown(KSL.out, df = MarkDown.D3FORMAT)
}

class TandemQueue(parent: ModelElement, name: String? = null) : ProcessModel(parent, name)  {

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
    val generator: EventGeneratorRVCIfc
        get() = myArrivalGenerator

    private val wip: TWResponse = TWResponse(this, "${this.name}:NumInSystem")
    val numInSystem: TWResponseCIfc
        get() = wip
    private val timeInSystem: Response = Response(this, "${this.name}:TimeInSystem")
    val systemTime: ResponseCIfc
        get() = timeInSystem

    private inner class Customer : Entity(){
        val tandemQProcess : KSLProcess = process(isDefaultProcess = true) {
            wip.increment()
            timeStamp = time
            seize(worker1)
            delay(st1)
            release(worker1)
            seize(worker2)
            delay(st2)
            release(worker2)
            timeInSystem.value = time - timeStamp
            wip.decrement()
        }
    }
}

class TandemQueueV2(parent: ModelElement, name: String? = null) : ProcessModel(parent, name)  {

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
    val generator: EventGeneratorRVCIfc
        get() = myArrivalGenerator

    private val wip: TWResponse = TWResponse(this, "${this.name}:NumInSystem")
    val numInSystem: TWResponseCIfc
        get() = wip
    private val timeInSystem: Response = Response(this, "${this.name}:TimeInSystem")
    val systemTime: ResponseCIfc
        get() = timeInSystem

    private inner class Customer : Entity(){
        val tandemQProcess : KSLProcess = process(isDefaultProcess = true) {
            wip.increment()
            timeStamp = time
            use(worker1, delayDuration = st1)
            use(worker2, delayDuration = st2)
            timeInSystem.value = time - timeStamp
            wip.decrement()
        }
    }
}