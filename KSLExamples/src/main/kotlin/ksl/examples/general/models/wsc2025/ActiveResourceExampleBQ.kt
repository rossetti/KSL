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
     //   serverOutputQ.receiverRequestSelector = serverOutputQ.FirstFillableRequest()
    }

    private lateinit var server: Server

    override fun initialize() {
        server = Server()
        activate(server.serverProcess)
    }

    override fun replicationEnded() {
        server.isNotShutDown = false
    }

    private inner class Customer() : Entity(name) {
        val customerProcess: KSLProcess = process(isDefaultProcess = true) {
            wip.increment()
            // signal server of arrival by sending a request for an item
            val item = QObject()
            serverInputQ.send(item)
            // wait for service activity to occur
            val items = waitForItems(serverOutputQ, 1, {it.id == item.id})
            timeInSystem.value = time - createTime
            wip.decrement()
            numCustomers.increment()
        }
    }

    private inner class Server() : Entity() {
        var isNotShutDown = true
        val serverProcess: KSLProcess = process {
            while (isNotShutDown) {
                val items = waitForItems(serverInputQ, 1)
                val item = items.first()
                myNumBusy.increment()
                delay(serviceTime)
                myNumBusy.decrement()
                serverOutputQ.send(item)
            }
        }
    }

}