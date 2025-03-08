package ksl.examples.general.models.wsc2025

import ksl.modeling.entity.BlockingQueue
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.variable.*
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.io.KSL
import ksl.utilities.io.MarkDown
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.ExponentialRV

fun main() {
    KSL.out.OUTPUT_ON = false
    val m = Model("Active Resource Example")
    val example = MM1ViaActiveResourceViaBQ(m, name = "ActiveResourceViaBQ")
    m.numberOfReplications = 30
    m.lengthOfReplication = 20000.0
    m.lengthOfReplicationWarmUp = 5000.0
//    m.numberOfReplications = 1
//    m.lengthOfReplication = 50.0 //TODO for some reason they are getting stuck after 2nd customer departs
    m.simulate()
    m.print()
    val r = m.simulationReporter
    val out = m.outputDirectory.createPrintWriter("ActiveResourceViaHQ.md")
    r.writeHalfWidthSummaryReportAsMarkDown(out, df = MarkDown.D3FORMAT)
}

class MM1ViaActiveResourceViaBQ(
    parent: ModelElement,
    numServers: Int = 1,
    ad: RandomIfc = ExponentialRV(1.0, 1),
    sd: RandomIfc = ExponentialRV(0.7, 2),
    name: String? = null
) : ProcessModel(parent, name) {

    init {
        require(numServers > 0) { "The number of servers must be >= 1" }
    }

    private val serviceTime: RandomVariable = RandomVariable(this, sd)
    val serviceRV: RandomSourceCIfc
        get() = serviceTime
    private val timeBetweenArrivals: RandomVariable = RandomVariable(parent, ad)
    val arrivalRV: RandomSourceCIfc
        get() = timeBetweenArrivals
    private val wip: TWResponse = TWResponse(this, "${this.name}:NumInSystem")
    val numInSystem: TWResponseCIfc
        get() = wip
    private val timeInSystem: Response = Response(this, "${this.name}:TimeInSystem")
    val systemTime: ResponseCIfc
        get() = timeInSystem
    private val numCustomers: Counter = Counter(this, "${this.name}:NumServed")
    val numCustomersServed: CounterCIfc
        get() = numCustomers

    private val myNumBusy: TWResponse = TWResponse(this, "NumBusy")

    private val generator = EntityGenerator(::Customer, timeBetweenArrivals, timeBetweenArrivals)

    private val serverInputQ: BlockingQueue<QObject> = BlockingQueue(this, name = "ServerInputQ")
    private val serverOutputQ: BlockingQueue<QObject> = BlockingQueue(this, name = "ServerOutputQ")

    init {
        serverOutputQ.receiverRequestSelector = serverOutputQ.FirstFillableRequest()
    }

    private lateinit var server: Server

    var custCount = 0

    override fun initialize() {
        server = Server()
        activate(server.serverProcess)
    }

    private inner class Customer(name: String? = "C${++custCount}") : Entity(name) {

        val customerProcess: KSLProcess = process(isDefaultProcess = true) {
            wip.increment()
            // signal server of arrival by sending a request for an item
            val item = QObject(this@Customer.name)
            KSL.out.println("$time > ARRIVAL : customer = ${this@Customer.name}")
            serverInputQ.send(item)
            // wait for service activity to occur
 //           println("$time > customer = ${this@Customer.name} waiting for service of item = ${item.name}")
            val items = waitForItems(serverOutputQ, 1, {it.id == item.id})
 //           println("$time > customer = ${this@Customer.name} received item = ${items.first().name} from server")
            KSL.out.println("$time > DEPARTURE : customer = ${this@Customer.name}")
            timeInSystem.value = time - createTime
            wip.decrement()
            numCustomers.increment()
        }
    }

    private inner class Server() : Entity() {

        val serverProcess: KSLProcess = process {

            while (model.isRunning) {
 //               println("$time > server = ${this@Server.name} waiting for an item")
                val items = waitForItems(serverInputQ, 1)
                val item = items.first()
 //               println("$time > server = ${this@Server.name} received item = ${item.name} for processing")
                myNumBusy.increment()
                val dt = serviceTime.value
                KSL.out.println("$time > BEGIN SERVICE : customer = ${item.name}")
 //               println("$time > server = ${this@Server.name} performing service for item = ${item.name} for $dt time units, end of service will be : ${time + dt}")
                delay(dt)
                KSL.out.println("$time > END SERVICE : customer = ${item.name}")
 //               println("$time > server = ${this@Server.name} completed service of item = ${item.name}")
                myNumBusy.decrement()
 //               println("$time > server = ${this@Server.name} returning item = ${item.name}")
                serverOutputQ.send(item)
            }
            // wait for customer's signal

            // indicate start of service
            // delay for service
            // indicate end of service

            // check for customers
        }
    }

}