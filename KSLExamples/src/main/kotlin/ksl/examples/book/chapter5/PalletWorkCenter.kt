package ksl.examples.book.chapter5

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.modeling.queue.Queue
import ksl.modeling.queue.QueueCIfc
import ksl.modeling.variable.*
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.BinomialRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.random.rvariable.TriangularRV

class PalletWorkCenter(
    parent: ModelElement,
    numWorkers: Int = 2,
    name: String? = null
) :
    ModelElement(parent, name = name) {

    init {
        require(numWorkers >= 1) { "The number of workers must be >= 1" }
    }

    @set:KSLControl(
        controlType = ControlType.INTEGER,
        lowerBound = 1.0
    )
    var numWorkers = numWorkers
        set(value) {
            require(value >= 1) { "The number of workers must be >= 1" }
            require(!model.isRunning) { "Cannot change the number of workers while the model is running!" }
            field = value
        }

    private val myProcessingTimeRV: RandomVariable = RandomVariable(
        parent = this,
        rSource = TriangularRV(min = 8.0, mode = 12.0, max = 15.0, streamNum = 3), name = "ProcessingTimeRV"
    )
    val processingTimeRV: RandomVariableCIfc
        get() = myProcessingTimeRV
    private val myTransportTimeRV: RandomVariable = RandomVariable(
        parent = parent,
        rSource = ExponentialRV(5.0, 2), name = "TransportTimeRV"
    )
    val transportTimeRV: RandomVariableCIfc
        get() = myTransportTimeRV
    private val myNumPalletsRV: RandomVariable = RandomVariable(
        parent = parent,
        rSource = BinomialRV(0.8, 100, 1), name = "NumPalletsRV"
    )
    val numPalletsRV: RandomVariableCIfc
        get() = myNumPalletsRV
    private val myNumBusy: TWResponse = TWResponse(parent = this, name = "NumBusyWorkers")
    val numBusyWorkers: TWResponseCIfc
        get() = myNumBusy
    private val myUtil: TWResponseFunction = TWResponseFunction(
        function = { x -> x/(this.numWorkers) },
        observedResponse = myNumBusy, name = "Worker Utilization"
    )
    val workerUtilization: TWResponseCIfc
        get() = myUtil
    private val myPalletQ: Queue<QObject> = Queue(parent = this, name = "PalletQ")
    val palletQ: QueueCIfc<QObject>
        get() = myPalletQ
    private val myNS: TWResponse = TWResponse(parent = this, name = "Num Pallets at WC")
    val numInSystem: TWResponseCIfc
        get() = myNS
    private val mySysTime: Response = Response(parent = this, name = "System Time")
    val systemTime: ResponseCIfc
        get() = mySysTime
    private val myNumProcessed: Counter = Counter(parent = this, name = "Num Processed")
    val numPalletsProcessed: CounterCIfc
        get() = myNumProcessed
    private val myTotalProcessingTime = Response(parent = this, name = "Total Processing Time")
    val totalProcessingTime: ResponseCIfc
        get() = myTotalProcessingTime
    private val myOverTime: IndicatorResponse = IndicatorResponse(
        predicate = { x -> x >= 480.0 },
        observedResponse = myTotalProcessingTime,
        name = "P{total time > 480 minutes}"
    )
    val probOfOverTime: ResponseCIfc
        get() = myOverTime

    private val endServiceEvent = this::endOfService
    private val endTransportEvent = this::endTransport

    var numToProcess: Int = 0

    override fun initialize() {
        numToProcess = myNumPalletsRV.value.toInt()
        schedule(eventAction = endTransportEvent, timeToEvent = myTransportTimeRV)
    }

    private fun endTransport(event: KSLEvent<Nothing>) {
        if (numToProcess >= 1) {
            schedule(eventAction = endTransportEvent, timeToEvent = myTransportTimeRV)
            numToProcess = numToProcess - 1
        }
        val pallet = QObject()
        arrivalAtWorkCenter(pallet)
    }

    private fun arrivalAtWorkCenter(pallet: QObject) {
        myNS.increment() // new pallet arrived
        myPalletQ.enqueue(pallet) // enqueue the newly arriving pallet
        if (myNumBusy.value < numWorkers) { // server available
            myNumBusy.increment() // make server busy
            val nextPallet: QObject? = myPalletQ.removeNext() //remove the next pallet
            // schedule end of service, include the pallet as the event's message
            schedule(eventAction = endServiceEvent, timeToEvent = myProcessingTimeRV, message = nextPallet)
        }
    }

    private fun endOfService(event: KSLEvent<QObject>) {
        myNumBusy.decrement() // pallet is leaving server is freed
        if (!myPalletQ.isEmpty) { // queue is not empty
            val nextPallet: QObject? = myPalletQ.removeNext() //remove the next pallet
            myNumBusy.increment() // make server busy
            // schedule end of service
            schedule(eventAction = endServiceEvent, timeToEvent = myProcessingTimeRV, message = nextPallet)
        }
        departSystem(completedPallet = event.message!!)
    }

    private fun departSystem(completedPallet: QObject) {
        mySysTime.value = (time - completedPallet.createTime)
        myNS.decrement() // pallet left system
        myNumProcessed.increment()
    }

    override fun replicationEnded() {
        myTotalProcessingTime.value = time
    }
}