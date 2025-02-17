package ksl.examples.general.misc

import ksl.modeling.queue.Queue
import ksl.modeling.queue.QueueCIfc
import ksl.modeling.variable.*
import ksl.observers.ResponseTrace
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.GetValueIfc
import ksl.utilities.io.plotting.StateVariablePlot
import ksl.utilities.statistic.TimeWeightedStatistic

fun main(){

 //   numberInQPlot()

    runModel()
}

fun runModel(){
    val m = Model("Tool Crib Example")
    // setup historical variables
    val arrivals = doubleArrayOf(
        3.00, 8.00, 2.00, 1.00, 3.00, 2.00, 2.00, 6.00, 5.00, 3.00, 3.00, 7.00, 5.00, 3.00, 2.00
    )
    val a = HistoricalVariable(m, arrivals, "arrivals")
    val serviceTimes = doubleArrayOf(
        4.00, 4.00, 4.00, 3.00, 2.00, 4.00, 3.00, 2.00, 2.00, 4.00, 3.00, 2.00, 3.00, 4.00, 4.00
    )
    val s = HistoricalVariable(m, serviceTimes, "serviceTimes")

    val hq = ToolCrib(m, timeBtwArrivals = a, serviceTime = s)
    val nOft = ResponseTrace(hq.numInSystem)
    val qOft = ResponseTrace(hq.waitingQ.numInQ)
    m.lengthOfReplication = 31.0
    m.numberOfReplications = 1
    m.simulate()
    m.print()

    val nsPlot = StateVariablePlot(nOft, repNum = 1)
    nsPlot.showInBrowser()

    val nqPlot = StateVariablePlot(qOft, repNum = 1)
    nqPlot.showInBrowser()
}

fun numberInQPlot() {
    val t = doubleArrayOf(0.0, 13.0, 14.0, 15.0, 17.0, 19.0, 19.0, 21.0, 22.0, 24.0, 27.0, 28.0, 31.0)
    val n = doubleArrayOf(0.0, 1.0, 2.0, 1.0, 2.0, 1.0, 2.0, 3.0, 2.0, 1.0, 2.0, 1.0, 0.0)
    val plot = StateVariablePlot(n, t, "Q(t)")
    plot.showInBrowser()
    plot.saveToFile("StateVariableDemo", plotTitle = "State Variable Plot")

    val tws3 = TimeWeightedStatistic(n, t)
    println(tws3)
}

class ToolCrib(
    parent: ModelElement,
    numServers: Int = 1,
    private val timeBtwArrivals: GetValueIfc,
    private val serviceTime: GetValueIfc,
    name: String? = null
) : ModelElement(parent, name) {
    init {
        require(numServers >= 1) { "The number of servers must be >= 1" }
    }

    var numServers = numServers
        set(value) {
            require(value >= 1) { "The number of servers must be >= 1" }
            field = value
        }

    private val myNumBusy: TWResponse = TWResponse(this, "NumBusy")
    val numBusy: TWResponseCIfc
        get() = myNumBusy
    private val myNS: TWResponse = TWResponse(this, "Num in System")
    val numInSystem: TWResponseCIfc
        get() = myNS
    private val myNumCustomers: Counter = Counter(this, name = "Num Served")

    private val mySysTime: Response = Response(this, "System Time")
    val systemTime: ResponseCIfc
        get() = mySysTime
    private val myWaitingQ: Queue<QObject> = Queue(this, "ServiceQ")
    val waitingQ: QueueCIfc<QObject>
        get() = myWaitingQ

    private val myArrivalEventAction: ArrivalEventAction = ArrivalEventAction()
    private val myEndServiceEventAction: EndServiceEventAction = EndServiceEventAction()
    private val repEndTime = Response(this, "Rep End Time")
    override fun initialize() {
        super.initialize()
        // start the arrivals
        schedule(myArrivalEventAction, timeBtwArrivals)
    }

    override fun replicationEnded() {
        repEndTime.value = time
    }

    private inner class ArrivalEventAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            myNS.increment() // new customer arrived
            val arrivingCustomer = QObject()
            myWaitingQ.enqueue(arrivingCustomer) // enqueue the newly arriving customer
            if (myNumBusy.value < numServers) { // server available
                myNumBusy.increment() // make server busy
                val customer: QObject? = myWaitingQ.removeNext() //remove the next customer
                // schedule end of service, include the customer as the event's message
                schedule(myEndServiceEventAction, serviceTime, customer)
            }
            // always schedule the next arrival
            schedule(myArrivalEventAction, timeBtwArrivals)
        }
    }

    private inner class EndServiceEventAction : EventAction<QObject>() {
        override fun action(event: KSLEvent<QObject>) {
            myNumBusy.decrement() // customer is leaving server is freed
            if (!myWaitingQ.isEmpty) { // queue is not empty
                val customer: QObject? = myWaitingQ.removeNext() //remove the next customer
                myNumBusy.increment() // make server busy
                // schedule end of service
                schedule(myEndServiceEventAction, serviceTime, customer)
            }
            val departingCustomer = event.message!!
            mySysTime.value = (time - departingCustomer.createTime)
            myNS.decrement() // customer left system
            myNumCustomers.increment()
        }
    }
}