package ksl.examples.general.running

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.modeling.elements.EventGenerator
import ksl.modeling.queue.Queue
import ksl.modeling.queue.QueueCIfc
import ksl.modeling.variable.*
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.io.KSL
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.ExponentialRV

fun main() {
    val model = Model("Demo_Logging")
    model.numberOfReplications = 1
    model.lengthOfReplication = 20.0
    val dtp = DemoLogging(model, 1)
    // set OUTPUT_ON to true for writing to kslOutput.txt, false stops the writing
    KSL.out.OUTPUT_ON = false
    model.simulate()
    model.print()
}

/**
 *  This illustrates two straightforward methods for tracing/logging
 *  a simulation model.
 */
class DemoLogging(
    parent: ModelElement,
    numServers: Int = 1,
    ad: RandomIfc = ExponentialRV(1.0, 1),
    sd: RandomIfc = ExponentialRV(0.5, 2),
    name: String? = null
) :
    ModelElement(parent, name = name) {

    @set:KSLControl(
        controlType = ControlType.INTEGER,
        lowerBound = 1.0
    )
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
    private val myWaitingQ: Queue<QObject> = Queue(this, "PharmacyQ")
    val waitingQ: QueueCIfc<QObject>
        get() = myWaitingQ

    private val myArrivalGenerator: EventGenerator = EventGenerator(this, this::arrival, myArrivalRV, myArrivalRV)
    private val endServiceEvent = this::endOfService

    private fun arrival(generator: EventGenerator){
        KSL.logger.info { "$time started arrival function" }
        myNS.increment() // new customer arrived
        val arrivingCustomer = QObject()
        KSL.out.println("t> $time : arrival: customer ${arrivingCustomer.id}")
        myWaitingQ.enqueue(arrivingCustomer) // enqueue the newly arriving customer
        if (myNumBusy.value < numPharmacists) { // server available
            myNumBusy.increment() // make server busy
            val customer: QObject? = myWaitingQ.removeNext() //remove the next customer
            // schedule end of service, include the customer as the event's message
            KSL.out.println("t> $time : scheduled service for: customer ${customer?.id}")
            schedule(endServiceEvent, myServiceRV, customer)
        }
    }

    private fun endOfService(event: KSLEvent<QObject>) {
        KSL.logger.info { "$time started departure function" }
        val departingCustomer = event.message!!
        KSL.out.println("t> $time : departure: customer ${departingCustomer.id}")
        myNumBusy.decrement() // customer is leaving server is freed
        KSL.out.println("t> $time : queue has ${myWaitingQ.numInQ.value} customers waiting")
        if (!myWaitingQ.isEmpty) { // queue is not empty
            val customer: QObject? = myWaitingQ.removeNext() //remove the next customer
            myNumBusy.increment() // make server busy
            // schedule end of service
            KSL.out.println("t> $time : scheduled service for: customer ${customer?.id}")
            schedule(endServiceEvent, myServiceRV, customer)
        }
        departSystem(departingCustomer)
    }

    private fun departSystem(departingCustomer: QObject) {
        mySysTime.value = (time - departingCustomer.createTime)
        KSL.out.println("t> $time : system time of customer ${departingCustomer.id} = ${mySysTime.value}")
        myNS.decrement() // customer left system
        myNumCustomers.increment()
    }
}