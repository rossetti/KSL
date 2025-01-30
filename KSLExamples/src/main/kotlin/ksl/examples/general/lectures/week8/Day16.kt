package ksl.examples.general.lectures.week8

import ksl.modeling.queue.Queue
import ksl.modeling.queue.QueueCIfc
import ksl.modeling.variable.*
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.PoissonRV

fun main() {
    val sim = Model("DTP_With_Q")
    sim.numberOfReplications = 1
    sim.lengthOfReplication = 50.0
    val dtp = DTPWithQ(sim, 1)
    dtp.arrivalRV.initialRandomSource = ExponentialRV(6.0, 1)
    dtp.serviceRV.initialRandomSource = ExponentialRV(3.0, 2)
    sim.simulate()
    sim.print()
}

class DTPWithQ(
    parent: ModelElement,
    numServers: Int = 1,
    ad: RandomIfc = ExponentialRV(1.0, 1),
    sd: RandomIfc = ExponentialRV(0.5, 2),
    name: String? = null
) :
    ModelElement(parent, name = name) {

    var numPharmacists = numServers
        set(value) {
            require(value > 0)
            require(!model.isRunning) { "Cannot change the number of pharmacists while the model is running!" }
            field = value
        }

    private var myServiceRV: RandomVariable = RandomVariable(this, sd)
    val serviceRV: RandomSourceCIfc
        get() = myServiceRV
    private var myArrivalRV: RandomVariable = RandomVariable(parent, ad)
    val arrivalRV: RandomSourceCIfc
        get() = myArrivalRV

    private val myNumBusy: TWResponse = TWResponse(this, "NumBusy")
    val numBusyPharmacists: TWResponseCIfc
        get() = myNumBusy

    private val myNS: TWResponse = TWResponse(this, "Num in System")
    val numInSystem: TWResponseCIfc
        get() = myNS
    private val mySysTime: Response = Response(this, "System Time")
    val systemTime: ResponseCIfc
        get() = mySysTime

    private val myNumCustomers: Counter = Counter(this, "Num Served")
    val numCustomersServed: CounterCIfc
        get() = myNumCustomers
    private val myWaitingQ: Queue<Customer> = Queue(this, "PharmacyQ")
    val waitingQ: QueueCIfc<Customer>
        get() = myWaitingQ

    private val myRV = RandomVariable(this, PoissonRV(10.0))

    private val endServiceEvent = this::endOfService

    inner class Customer() : QObject()
    {
        var storeSomething: Double = 0.0
    }

    private val myNumOfSomethings = Response(this, "Avg of Something")

    override fun initialize() {
        schedule(this::arrivalEvent, myArrivalRV)
    }

    private fun arrivalEvent(event: KSLEvent<Nothing>){
        myNS.increment() // new customer arrived
        enterSystem(Customer())
        schedule(this::arrivalEvent, myArrivalRV)
    }

    private fun enterSystem(arrivingCustomer: Customer){
        // can use attribute to hold information
        arrivingCustomer.storeSomething = myRV.value
        myWaitingQ.enqueue(arrivingCustomer) // enqueue the newly arriving customer
        if (myNumBusy.value < numPharmacists) { // server available
            myNumBusy.increment() // make server busy
            val customer: Customer? = myWaitingQ.removeNext() //remove the next customer
            // schedule end of service, include the customer as the event's message
            schedule(endServiceEvent, myServiceRV, customer)
        }
    }

    private fun endOfService(event: KSLEvent<Customer>) {
        myNumBusy.decrement() // customer is leaving server is freed
        if (!myWaitingQ.isEmpty) { // queue is not empty
            val customer: Customer? = myWaitingQ.removeNext() //remove the next customer
            myNumBusy.increment() // make server busy
            // schedule end of service
            schedule(endServiceEvent, myServiceRV, customer)
        }
        val departingCustomer = event.message!!
        departingCustomer.storeSomething = departingCustomer.storeSomething + 1.0
        departSystem(departingCustomer)
    }

    private fun departSystem(departingCustomer: Customer) {
        mySysTime.value = (time - departingCustomer.createTime)
        myNS.decrement() // customer left system
        myNumCustomers.increment()
        myNumOfSomethings.value = departingCustomer.storeSomething
        println("customer id = ${departingCustomer.id} with storeSomething = ${departingCustomer.storeSomething}")
    }
}
