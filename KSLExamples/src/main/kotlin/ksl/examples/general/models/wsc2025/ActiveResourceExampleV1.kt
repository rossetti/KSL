package ksl.examples.general.models.wsc2025

import ksl.modeling.entity.BlockingQueue
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.variable.*
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.ExponentialRV

fun main() {
    val m = Model("Active Resource Example")
    val example = MM1ViaActiveResourceOLD(m, name = "ActiveResource")
    m.numberOfReplications = 30
    m.lengthOfReplication = 20000.0
    m.lengthOfReplicationWarmUp = 5000.0
    m.simulate()
    m.print()

}

class MM1ViaActiveResourceOLD(
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

  //  private val myCustomerQ: Queue<Entity> = Queue(this, "CustomerQ")

    private val itemQ: BlockingQueue<Customer> = BlockingQueue(this, name = "ItemQ")

    private lateinit var server: Server

    override fun initialize() {
        server = Server()
        activate(server.serverProcess)
    }

    private inner class Customer() : Entity() {

        val customerProcess: KSLProcess = process(isDefaultProcess = true) {
            wip.increment()
            // signal server of arrival
            itemQ.send(this@Customer)
            // wait for service activity to occur
            waitFor(server.serviceActivity)
            timeInSystem.value = time - createTime
            wip.decrement()
            numCustomers.increment()
        }
    }

    private inner class Server() : Entity() {

        val serviceActivity: BlockingActivity = BlockingActivity(serviceTime)

        val serverProcess: KSLProcess = process {

            while (model.isRunning) {
                val items = waitForItems(itemQ, 1)
                myNumBusy.increment()
                perform(serviceActivity)
                myNumBusy.decrement()
            }
            // wait for customer's signal

            // indicate start of service
            // delay for service
            // indicate end of service

            // check for customers
        }
    }

}