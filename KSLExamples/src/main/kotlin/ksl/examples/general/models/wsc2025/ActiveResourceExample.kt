package ksl.examples.general.models.wsc2025

import ksl.examples.book.chapter6.DriveThroughPharmacy
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.variable.*
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.ExponentialRV

fun main() {
    val m = Model("Active Resource Example")
    val example = MM1ViaActiveResource(m, name = "ActiveResource")
    m.numberOfReplications = 30
    m.lengthOfReplication = 20000.0
    m.lengthOfReplicationWarmUp = 5000.0
    m.simulate()
    m.print()

}

class MM1ViaActiveResource(
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

    private inner class Customer() : Entity() {

        val customerProcess: KSLProcess = process {
            // signal server of arrival

            // wait for service to begin

            // wait during service

            // depart
        }
    }

    private inner class Server() : Entity () {

        val serverProcess: KSLProcess = process {
            // wait for customer's signal

            // indicate start of service
            // delay for service
            // indicate end of service

            // check for customers
        }
    }

}