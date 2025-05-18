package ksl.examples.general.variables

import ksl.modeling.elements.EventGenerator
import ksl.modeling.elements.EventGeneratorIfc
import ksl.modeling.elements.EventGeneratorRVCIfc
import ksl.modeling.queue.Queue
import ksl.modeling.queue.QueueCIfc
import ksl.modeling.station.SResource
import ksl.modeling.station.SResourceCIfc
import ksl.modeling.variable.*
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.io.dbutil.KSLDatabase
import ksl.utilities.io.dbutil.KSLDatabaseObserver
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.RVariableIfc
import org.jetbrains.kotlinx.dataframe.api.print


fun main() {
    val model = Model("Drive Through Pharmacy")
    model.numberOfReplications = 5
    model.lengthOfReplication = 500.0
//    model.lengthOfReplicationWarmUp = 5000.0
    // add the model element to the main model
    val dtp = TestTimeSeriesResponse(model, 1, name = "Pharmacy")
    dtp.arrivalGenerator.initialTimeBtwEvents = ExponentialRV(6.0, 1)
    dtp.serviceRV.initialRandomSource = ExponentialRV(3.0, 2)
    dtp.timeSeriesResponse.acrossRepStatisticsOption = true
    // demonstrate capturing data to database with an observer
    val kslDatabaseObserver = KSLDatabaseObserver(model)

    val sdb = KSLDatabase.createEmbeddedDerbyKSLDatabase("TestDerbyKSLDb", model.outputDirectory.dbDir)
//        val sdb = KSLDatabase.createPostgreSQLKSLDatabase(dbName = "postgres")
    val kdb = KSLDatabase(sdb)
    KSLDatabaseObserver(model, kdb)

    model.simulate()
    model.print()
    println()
    val df1 = dtp.timeSeriesResponse.allTimeSeriesPeriodDataAsDataFrame()
    df1.print(rowsLimit = 100)

    kdb.timeSeriesResponseViewData.print()

    println()

    dtp.timeSeriesResponse.allAcrossReplicationStatisticsByPeriodAsDataFrame().print()
}

class TestTimeSeriesResponse(
    parent: ModelElement,
    numServers: Int = 1,
    ad: RVariableIfc = ExponentialRV(1.0, 1),
    sd: RVariableIfc = ExponentialRV(0.5, 2),
    name: String? = null
) :
    ModelElement(parent, name = name) {

    private val myPharmacists: SResource = SResource(this, numServers, "${this.name}:Pharmacists")
    val resource: SResourceCIfc
        get() = myPharmacists

    private var myServiceRV: RandomVariable = RandomVariable(this, sd)
    val serviceRV: RandomVariableCIfc
        get() = myServiceRV

    private val myNS: TWResponse = TWResponse(this, "${this.name}:NumInSystem")
    val numInSystem: TWResponseCIfc
        get() = myNS
    private val mySysTime: Response = Response(this, "${this.name}:SystemTime")
    val systemTime: ResponseCIfc
        get() = mySysTime

    private val myTimeSeriesResponse = TimeSeriesResponse(this, 100.0, 5, setOf(mySysTime, myNS))
    val timeSeriesResponse: TimeSeriesResponseCIfc
        get() = myTimeSeriesResponse

    private val myNumCustomers: Counter = Counter(this, "${this.name}:NumServed")
    val numCustomersServed: CounterCIfc
        get() = myNumCustomers

    private val myWaitingQ: Queue<QObject> = Queue(this, "${this.name}:PharmacyQ")
    val waitingQ: QueueCIfc<QObject>
        get() = myWaitingQ

    private val endServiceEvent = this::endOfService

    private val myArrivalGenerator: EventGenerator = EventGenerator(
        this, this::arrival, ad, ad
    )
    val arrivalGenerator: EventGeneratorRVCIfc
        get() = myArrivalGenerator

    private fun arrival(generator: EventGeneratorIfc) {
        myNS.increment() // new customer arrived
        val arrivingCustomer = QObject()
        myWaitingQ.enqueue(arrivingCustomer) // enqueue the newly arriving customer
        if (myPharmacists.hasAvailableUnits) {
            myPharmacists.seize()
            val customer: QObject? = myWaitingQ.removeNext() //remove the next customer
            // schedule end of service, include the customer as the event's message
            schedule(endServiceEvent, myServiceRV, customer)
        }
    }

    private fun endOfService(event: KSLEvent<QObject>) {
        myPharmacists.release()
        if (!myWaitingQ.isEmpty) { // queue is not empty
            myPharmacists.seize()
            val customer: QObject? = myWaitingQ.removeNext() //remove the next customer
            // schedule end of service
            schedule(endServiceEvent, myServiceRV, customer)
        }
        departSystem(event.message!!)
    }

    private fun departSystem(departingCustomer: QObject) {
        mySysTime.value = (time - departingCustomer.createTime)
        myNS.decrement() // customer left system
        myNumCustomers.increment()
    }
}