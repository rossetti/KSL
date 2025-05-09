package ksl.examples.general.lectures.week6

import ksl.modeling.queue.Queue
import ksl.modeling.queue.QueueCIfc
import ksl.modeling.variable.*
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.io.dbutil.KSLDatabaseObserver
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.RVariableIfc


fun main() {
    val model = Model("Drill5", autoCSVReports = true)
    model.numberOfReplications = 30
    model.lengthOfReplication = 600.0

    model.resetStartStreamOption = true
    val kslDatabaseObserver = KSLDatabaseObserver(model)

    val atm = ATM(model, numUnits = 1, name = "ATM System")
    model.experimentName = "1 ATM"
    model.simulate()
    model.print()

    model.experimentName = "2 ATM"
    atm.numATMs = 2
    atm.serviceRV.initialRandomSource = ExponentialRV(1.8, 2)
    model.simulate()
    model.print()

    val responseName =atm.probWaitTimeGT5Minutes.name
    val db = kslDatabaseObserver.db
    val expNames = listOf("1 ATM", "2 ATM")
    val comparisonAnalyzer =db.multipleComparisonAnalyzerFor(expNames, responseName)
    println(comparisonAnalyzer)
}

class ATM(
    parent: ModelElement,
    numUnits: Int = 1,
    ad: RVariableIfc = ExponentialRV(1.0, 1),
    sd: RVariableIfc = ExponentialRV(0.9, 2),
    name: String? = null
) : ModelElement(parent, name = name) {

    var numATMs = numUnits
        set(value) {
            require(value > 0)
            require(!model.isRunning) { "Cannot change the number of ATMs while the model is running!" }
            field = value
        }

    private var myServiceTime: RandomVariable = RandomVariable(this, sd)
    val serviceRV:RandomVariableCIfc
        get() = myServiceTime
    private var myArrivalRV: RandomVariable = RandomVariable(this , ad)
    val arrivalRV: RandomVariableCIfc
        get() = myArrivalRV

    private val myNumBusy: TWResponse = TWResponse(this, "NumBusy")
    val numBusy: TWResponseCIfc
        get() = myNumBusy

    private val myNS: TWResponse = TWResponse(this, "NumInSystem")
    val numInSystem: TWResponseCIfc
        get() = myNS
    private val mySysTime: Response = Response(this, "System Time")
    val systemTime: ResponseCIfc
        get() = mySysTime

    private val myWaitingQ: Queue<QObject> = Queue(this, "WaitQ")
    val waitQ: QueueCIfc<QObject>
        get() = myWaitingQ

    private val myWTGT3: IndicatorResponse = IndicatorResponse({ x -> x >= 3.0 }, myWaitingQ.timeInQ as Response,
        "WaitTime >= 3 minutes")
    val probWaitTimeGT5Minutes: ResponseCIfc
        get() = myWTGT3

    private val myArrivalEvent = this::arrivalEvent
    private val myEndServiceEvent = this::endOfService

    override fun initialize() {
        // start the arrivals
        schedule(myArrivalEvent, myArrivalRV)
    }

    private fun arrivalEvent(event: KSLEvent<Nothing>){
        myNS.increment() // new customer arrived
        val arrivingPassenger = QObject()
        myWaitingQ.enqueue(arrivingPassenger) // enqueue the newly arriving customer
        if (myNumBusy.value < numATMs) { // server available
            myNumBusy.increment() // make server busy
            val nextCustomer: QObject? = myWaitingQ.removeNext() //remove the next customer
            // schedule end of service, include the passenger as the event's message
            schedule(myEndServiceEvent, myServiceTime, nextCustomer)
        }
        // always schedule the next arrival
        schedule(myArrivalEvent, myArrivalRV)
    }

    private fun endOfService(event: KSLEvent<QObject>) {
        myNumBusy.decrement() // customer ended ATM service
        if (!myWaitingQ.isEmpty) { // queue is not empty
            val nextCustomer: QObject? = myWaitingQ.removeNext() //remove the next customer
            myNumBusy.increment() // make server busy
            // schedule end of service
            schedule(myEndServiceEvent, myServiceTime, nextCustomer)
        }
        departSystem(event.message!!)
    }

    private fun departSystem(completed: QObject) {
        mySysTime.value = (time - completed.createTime)
        myNS.decrement() // customer left system
    }
}
