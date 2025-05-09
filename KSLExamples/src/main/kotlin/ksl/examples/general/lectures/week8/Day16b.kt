package ksl.examples.general.lectures.week8

import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.queue.Queue
import ksl.modeling.queue.QueueCIfc
import ksl.modeling.variable.*
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.PoissonRV
import ksl.utilities.random.rvariable.RVariableIfc

fun main() {
    val sim = Model("DTP_With_Q")
    sim.numberOfReplications = 1
    sim.lengthOfReplication = 50.0
    val dtp = DTPWithQProcessView(sim, 1)
    dtp.arrivalRV.initialRandomSource = ExponentialRV(6.0, 1)
    dtp.serviceRV.initialRandomSource = ExponentialRV(3.0, 2)
    sim.simulate()
    sim.print()
}

class DTPWithQProcessView(
    parent: ModelElement,
    numServers: Int = 1,
    ad: RVariableIfc = ExponentialRV(1.0, 1),
    sd: RVariableIfc = ExponentialRV(0.5, 2),
    name: String? = null
) :
    ProcessModel(parent, name = name) {

    var numPharmacists = numServers
        set(value) {
            require(value > 0)
            require(!model.isRunning) { "Cannot change the number of pharmacists while the model is running!" }
            field = value
        }

    private var myServiceRV: RandomVariable = RandomVariable(this, sd)
    val serviceRV: RandomVariableCIfc
        get() = myServiceRV
    private var myArrivalRV: RandomVariable = RandomVariable(parent, ad)
    val arrivalRV: RandomVariableCIfc
        get() = myArrivalRV

    private val myNS: TWResponse = TWResponse(this, "${this.name}:NumInSystem")
    val numInSystem: TWResponseCIfc
        get() = myNS

    private val mySysTime: Response = Response(this, "${this.name}:SystemTime")
    val systemTime: ResponseCIfc
        get() = mySysTime

    private val myNumCustomers: Counter = Counter(this, "${this.name}:NumServed")
    val numCustomersServed: CounterCIfc
        get() = myNumCustomers

   private val pharmacists: ResourceWithQ = ResourceWithQ(this, "Pharmacists", numPharmacists)

    override fun initialize() {
        schedule(this::arrivalEvent, myArrivalRV)
    }

    private fun arrivalEvent(event: KSLEvent<Nothing>){
        myNS.increment() // new customer arrived
//TODO
        schedule(this::arrivalEvent, myArrivalRV)
    }

    private inner class Customer : Entity() {
        // the class can have attributes, functions, etc.

        val pharmacyProcess: KSLProcess = process() {
//TODO
            val a = seize(pharmacists)
            delay(myServiceRV)
            release(a)

        }
    }
}