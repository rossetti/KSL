package ksl.examples.general.models.wsc2025

import ksl.modeling.entity.*
import ksl.modeling.variable.*
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.io.MarkDown
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.RVariableIfc

fun main() {
    val m = Model("Active Resource Example")
    val example = MM1ViaActiveResourceViaHQ(m, name = "ActiveResourceViaHQ")
    m.numberOfReplications = 30
    m.lengthOfReplication = 20000.0
    m.lengthOfReplicationWarmUp = 5000.0
    m.simulate()
    m.print()
    val r = m.simulationReporter
    val out = m.outputDirectory.createPrintWriter("ActiveResourceViaHQ.md")
    r.writeHalfWidthSummaryReportAsMarkDown(out, df = MarkDown.D3FORMAT)
}

class MM1ViaActiveResourceViaHQ(
    parent: ModelElement,
    numServers: Int = 1,
    ad: RVariableIfc = ExponentialRV(1.0, 1),
    sd: RVariableIfc = ExponentialRV(0.7, 2),
    name: String? = null
) : ProcessModel(parent, name) {

    init {
        require(numServers > 0) { "The number of servers must be >= 1" }
    }

    private val serviceTime: RandomVariable = RandomVariable(this, sd)
    val serviceRV: RandomVariableCIfc
        get() = serviceTime

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

    private val generator = EntityGenerator(::Customer, ad, ad)
    private val customerWaitingQ = HoldQueue(this, "CustomerWaitingQ")
    private val customerInServiceQ = HoldQueue(this, "CustomerInServiceQ")
    private val serverWaitingQ = HoldQueue(this, "ServerWaitingQ")

    private lateinit var server: Server

    override fun initialize() {
        server = Server()
        activate(server.serverProcess)
    }

    override fun replicationEnded() {
        server.isNotShutDown = false
    }

    private inner class Customer() : Entity() {
        val customerProcess: KSLProcess = process(isDefaultProcess = true) {
            wip.increment()
            // signal server of arrival
            server.callServer()
            // wait for service activity to occur
            hold(customerWaitingQ)
            hold(customerInServiceQ)
            timeInSystem.value = time - createTime
            wip.decrement()
            numCustomers.increment()
        }
    }

    private inner class Server() : Entity() {
        var isNotShutDown = true

        fun callServer() {
            if (serverWaitingQ.isNotEmpty) {
                val idleServer = serverWaitingQ.peekNext()!!
                serverWaitingQ.removeAndResume(idleServer)
            }
        }

        val serverProcess: KSLProcess = process {
            while (isNotShutDown) {
                hold(serverWaitingQ)
                do {
                    val nextCustomer = customerWaitingQ.peekNext()!!
                    customerWaitingQ.removeAndResume(nextCustomer)
                    myNumBusy.increment()
                    delay(serviceTime)
                    customerInServiceQ.removeAndResume(nextCustomer)
                    myNumBusy.decrement()
                } while (customerWaitingQ.isNotEmpty)
            }
        }
    }

}

/*
via seize-delay-release
-------------------------------
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
-------------------------------
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

via HoldQueue
-------------------------------
Half-Width Statistical Summary Report - Confidence Level (95.000)%

Name                                                                   	        Count 	      Average 	   Half-Width
------------------------------------------------------------------------------------------------------------------------
ActiveResourceHQ:NumInSystem                                           	           30 	       2.2960 	       0.0387
ActiveResourceHQ:TimeInSystem                                          	           30 	       2.2986 	       0.0366
NumBusy                                                                	           30 	       0.6983 	       0.0023
CustomerWaitingQ:NumInQ                                                	           30 	       1.5977 	       0.0369
CustomerWaitingQ:TimeInQ                                               	           30 	       1.5995 	       0.0355
CustomerInServiceQ:NumInQ                                              	           30 	       0.6983 	       0.0023
CustomerInServiceQ:TimeInQ                                             	           30 	       0.6991 	       0.0018
ServerWaitingQ:NumInQ                                                  	           30 	       0.3017 	       0.0023
ServerWaitingQ:TimeInQ                                                 	           30 	       1.0010 	       0.0062
ActiveResourceHQ:NumServed                                             	           30 	   14981.8333 	      40.6399
------------------------------------------------------------------------------------------------------------------------

via BlockingQ implementation
-------------------------------
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