package ksl.examples.book.chapter8

import ksl.modeling.elements.EventGeneratorRVCIfc
import ksl.modeling.entity.Conveyor
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.spatial.*
import ksl.modeling.variable.*
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.TriangularRV
import ksl.utilities.random.rvariable.UniformRV


fun main(){
    val m = Model()
    val tq = TandemQueueWithConveyorsViaDelay(m, name = "TandemQueueWithConveyorViaDelay")
    m.numberOfReplications = 30
    m.lengthOfReplication = 20000.0
    m.lengthOfReplicationWarmUp = 5000.0
    m.simulate()
    m.print()
}

class TandemQueueWithConveyorsViaDelay(parent: ModelElement, name: String? = null) : ProcessModel(parent, name) {

    private val worker1: ResourceWithQ = ResourceWithQ(this, "worker1")
    private val worker2: ResourceWithQ = ResourceWithQ(this, "worker2")

    private val tba = ExponentialRV(1.0, 1)

    private val st1 = RandomVariable(this, ExponentialRV(0.7, 2))
    val service1RV: RandomVariableCIfc
        get() = st1
    private val st2 = RandomVariable(this, ExponentialRV(0.9, 3))
    val service2RV: RandomVariableCIfc
        get() = st2
    private val myArrivalGenerator = EntityGenerator(::Part, tba, tba)
    val generator: EventGeneratorRVCIfc
        get() = myArrivalGenerator

    private val wip: TWResponse = TWResponse(this, "${this.name}:NumInSystem")
    val numInSystem: TWResponseCIfc
        get() = wip
    private val timeInSystem: Response = Response(this, "${this.name}:TimeInSystem")
    val systemTime: ResponseCIfc
        get() = timeInSystem

    private val myLoadingTime = RandomVariable(this, UniformRV(0.5, 0.8))
    val loadingTimeRV: RandomVariableCIfc
        get() = myLoadingTime
    private val myUnLoadingTime = RandomVariable(this, UniformRV(0.25, 0.5))
    val unloadingTimeRV: RandomVariableCIfc
        get() = myUnLoadingTime

    private inner class Part : Entity() {
        val tandemQProcess: KSLProcess = process(isDefaultProcess = true) {
            wip.increment()
            timeStamp = time
            delay(delayDuration = 70.0/30.0) // 30 fpm for 70 ft
            use(resource = worker1, delayDuration = st1)
            delay(delayDuration = 40.0/30.0) // 30 fpm for 40 ft
            use(resource = worker2, delayDuration = st2)
            delay(delayDuration = 60.0/30.0) // 30 fpm for 60 ft
            timeInSystem.value = time - timeStamp
            wip.decrement()
        }
    }
}

