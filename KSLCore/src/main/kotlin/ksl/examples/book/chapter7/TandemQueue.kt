package ksl.examples.book.chapter7

import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.*
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

class TandemQueue(parent: ModelElement, name: String? = null) : ProcessModel(parent, name)  {

    private val worker1: ResourceWithQ = ResourceWithQ(this, "worker1")
    private val worker2: ResourceWithQ = ResourceWithQ(this, "worker2")

    private val tba = ExponentialRV(1.0, 1)

    private val st1 = RandomVariable(this, ExponentialRV(0.7, 2))
    val service1RV: RandomSourceCIfc
        get() = st1
    private val st2 = RandomVariable(this, ExponentialRV(0.9, 3))
    val service2RV: RandomSourceCIfc
        get() = st2
    private val myArrivalGenerator = EntityGenerator(::Customer, tba, tba)

    private val wip: TWResponse = TWResponse(this, "${this.name}:NumInSystem")
    val numInSystem: TWResponseCIfc
        get() = wip
    private val timeInSystem: Response = Response(this, "${this.name}:TimeInSystem")
    val systemTime: ResponseCIfc
        get() = timeInSystem

    private inner class Customer : Entity(){
        val tandemQProcess : KSLProcess = process {
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
