package ksl.examples.general.models.wsc2025

import ksl.modeling.entity.*
import ksl.modeling.variable.*
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.ExponentialRV

fun main() {
    val m = Model("Active Resource Example")
    val example = MM1ViaActiveResourceViaSignal(m, name = "ActiveResourceViaSignal")
    m.numberOfReplications = 30
    m.lengthOfReplication = 20000.0
    m.lengthOfReplicationWarmUp = 5000.0
    m.simulate()
    m.print()

}

class MM1ViaActiveResourceViaSignal(
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

    private val customerQSignal = Signal(this, "CustomerQ")
    private val customerInServiceQ = Signal(this, "CustomerInServiceQ")
    private val serverWaitingQSignal = Signal(this, "ServerWaitingQ")

    private lateinit var server: Server

    override fun initialize() {
        server = Server()
        activate(server.serverProcess)
    }

    private inner class Customer() : Entity() {

        val customerProcess: KSLProcess = process(isDefaultProcess = true) {
            wip.increment()
            // signal server of arrival
            serverWaitingQSignal.signal(server)
            // wait for service activity to occur
            waitFor(customerQSignal)
            waitFor(customerInServiceQ)
            timeInSystem.value = time - createTime
            wip.decrement()
            numCustomers.increment()
        }
    }

    private inner class Server() : Entity() {


        val serverProcess: KSLProcess = process {

            while (model.isRunning) {
                waitFor(serverWaitingQSignal)
                do {
                    customerQSignal.signal(rank = 0)
                    myNumBusy.increment()
                    delay(serviceTime)
                    customerInServiceQ.signal(rank = 0)
                    myNumBusy.decrement()
                } while(customerQSignal.waitingQ.isNotEmpty)
            }
            // wait for customer's signal

            // indicate start of service
            // delay for service
            // indicate end of service

            // check for customers
        }
    }

}

/*
Original
Half-Width Statistical Summary Report - Confidence Level (95.000)%

Name                                                                   	        Count 	      Average 	   Half-Width
------------------------------------------------------------------------------------------------------------------------
Servers:InstantaneousUtil                                              	           30 	       0.6983 	       0.0023
Servers:NumBusyUnits                                                   	           30 	       0.6983 	       0.0023
Servers:ScheduledUtil                                                  	           30 	       0.6983 	       0.0023
Servers:Q:NumInQ                                                       	           30 	       1.5977 	       0.0369
Servers:Q:TimeInQ                                                      	           30 	       1.5995 	       0.0355
Servers:WIP                                                            	           30 	       2.2960 	       0.0387
MM1:NumInSystem                                                        	           30 	       2.2960 	       0.0387
MM1:TimeInSystem                                                       	           30 	       2.2986 	       0.0366
Servers:SeizeCount                                                     	           30 	   14982.0333 	      40.6024
MM1:NumServed                                                          	           30 	   14981.8333 	      40.6399
------------------------------------------------------------------------------------------------------------------------

via Signal
Half-Width Statistical Summary Report - Confidence Level (95.000)%

Name                                                                   	        Count 	      Average 	   Half-Width
------------------------------------------------------------------------------------------------------------------------
ActiveResource:NumInSystem                                             	           30 	       2.2960 	       0.0387
ActiveResource:TimeInSystem                                            	           30 	       2.2986 	       0.0366
NumBusy                                                                	           30 	       0.6983 	       0.0023
CustomerQ:HoldQ:NumInQ                                                 	           30 	       1.5977 	       0.0369
CustomerQ:HoldQ:TimeInQ                                                	           30 	       1.5995 	       0.0355
CustomerInServiceQ:HoldQ:NumInQ                                        	           30 	       0.6983 	       0.0023
CustomerInServiceQ:HoldQ:TimeInQ                                       	           30 	       0.6991 	       0.0018
ServerWaitingQ:HoldQ:NumInQ                                            	           30 	       0.3017 	       0.0023
ServerWaitingQ:HoldQ:TimeInQ                                           	           30 	       1.0010 	       0.0062
ActiveResource:NumServed                                               	           30 	   14981.8333 	      40.6399
------------------------------------------------------------------------------------------------------------------------

via BlockingQ implementation

Half-Width Statistical Summary Report - Confidence Level (95.000)%

Name                                                                   	        Count 	      Average 	   Half-Width
------------------------------------------------------------------------------------------------------------------------
ActiveResource:NumInSystem                                             	           30 	       2.2960 	       0.0387
ActiveResource:TimeInSystem                                            	           30 	       2.2986 	       0.0366
NumBusy                                                                	           30 	       0.6983 	       0.0023
ServerInputQ:RequestQ:NumInQ                                           	           30 	       0.3017 	       0.0023
ServerInputQ:RequestQ:TimeInQ                                          	           30 	       0.3022 	       0.0029
ServerInputQ:ChannelQ:NumInQ                                           	           30 	       1.5977 	       0.0369
ServerInputQ:ChannelQ:TimeInQ                                          	           30 	       1.5995 	       0.0355
ServerOutputQ:RequestQ:NumInQ                                          	           30 	       2.2960 	       0.0387
ServerOutputQ:RequestQ:TimeInQ                                         	           30 	       2.2986 	       0.0366
ServerOutputQ:ChannelQ:NumInQ                                          	           30 	       0.0000 	       0.0000
ServerOutputQ:ChannelQ:TimeInQ                                         	           30 	       0.0000 	       0.0000
ActiveResource:NumServed                                               	           30 	   14981.8333 	      40.6399
------------------------------------------------------------------------------------------------------------------------
 */