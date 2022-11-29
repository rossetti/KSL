/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package ksl.examples.book.chapter5

import ksl.modeling.queue.Queue
import ksl.modeling.queue.QueueCIfc
import ksl.modeling.variable.*
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.statistic.HistogramIfc


/**
 * This model element illustrates how to model a simple multiple server
 * queueing system. The number of servers can be supplied. In
 * addition, the user can supply the distribution associated with the time
 * between arrivals and the service time distribution.
 * Statistics are collected on the average number of busy servers,
 * the average number of customers in the system, the average system
 * time, the average number of customers waiting, the average waiting
 * time of the customers, and the number of customers served.
 */
class DriveThroughPharmacyWithQ(
    parent: ModelElement,
    numServers: Int = 1,
    ad: RandomIfc = ExponentialRV(1.0, 1),
    sd: RandomIfc = ExponentialRV(0.5, 2)
) :
    ModelElement(parent, theName = null) {

    private var numPharmacists = numServers
    private var myServiceRV: RandomVariable = RandomVariable(this, sd)
    val serviceRV: RandomSourceCIfc
        get() = myServiceRV
    private var myArrivalRV: RandomVariable = RandomVariable(parent, ad)
    val arrivalRV: RandomSourceCIfc
        get() = myArrivalRV
    private val myNumBusy: TWResponse = TWResponse(this, "NumBusy")
    private val myNS: TWResponse = TWResponse(this, "# in System")
    private val mySysTime: Response = Response(this, "System Time")
    private val myArrivalEventAction: ArrivalEventAction = ArrivalEventAction()
    private val myEndServiceEventAction: EndServiceEventAction = EndServiceEventAction()
    private val myNumCustomers: Counter = Counter(this, "Num Served")
    private val myWaitingQ: Queue<QObject> = Queue(this, "PharmacyQ")
    val waitingQ: QueueCIfc<QObject>
        get() = myWaitingQ

    private val myTotal: AggregateTWResponse = AggregateTWResponse(this, "aggregate # in system")
    private val mySysTimeHistogram: ResponseHistogram = ResponseHistogram(mySysTime, theBreakPointMinDataSize = 200)
    private val mySTGT3: IndicatorResponse = IndicatorResponse({ x -> x > 4.0 }, mySysTime, "SysTime>4.0")
    val systemTimeHistogram: HistogramIfc
        get() = mySysTimeHistogram.histogram

    init {
        myTotal.observe(myWaitingQ.numInQ)
        myTotal.observe(myNumBusy)
    }

    val systemTimeResponse: ResponseCIfc
        get() = mySysTime
    val numInSystemResponse: TWResponseCIfc
        get() = myNS
    val numberOfServers: Int
        get() = numPharmacists

    fun setNumberOfPharmacists(n: Int) {
        require(n >= 0)
        numPharmacists = n
    }

    protected override fun initialize() {
        super.initialize()
        // start the arrivals
        schedule(myArrivalEventAction, myArrivalRV)
    }

    private inner class ArrivalEventAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            //	 schedule the next arrival
            schedule(myArrivalEventAction, myArrivalRV)
            enterSystem()
        }
    }

    private fun enterSystem() {
        myNS.increment() // new customer arrived
        val arrivingCustomer = QObject()
        myWaitingQ.enqueue(arrivingCustomer) // enqueue the newly arriving customer
        if (myNumBusy.value < numPharmacists) { // server available
            myNumBusy.increment() // make server busy
            val customer: QObject? = myWaitingQ.removeNext() //remove the next customer
            // schedule end of service, include the customer as the event's message
            schedule(myEndServiceEventAction, myServiceRV, customer)
        }
    }

    private inner class EndServiceEventAction : EventActionIfc<QObject> {
        override fun action(event: KSLEvent<QObject>) {
            myNumBusy.decrement() // customer is leaving server is freed
            if (!myWaitingQ.isEmpty) { // queue is not empty
                val customer: QObject? = myWaitingQ.removeNext() //remove the next customer
                myNumBusy.increment() // make server busy
                // schedule end of service
                schedule(myEndServiceEventAction, myServiceRV, customer)
            }
            departSystem(event.message!!)
        }
    }

    private fun departSystem(departingCustomer: QObject) {
        mySysTime.value = (time - departingCustomer.createTime)
        myNS.decrement() // customer left system
        myNumCustomers.increment()
    }
}

fun main() {
    val sim = Model("Drive Through Pharmacy")
    sim.numberOfReplications = 30
    sim.lengthOfReplication = 20000.0
    sim.lengthOfReplicationWarmUp = 5000.0
    // add DriveThroughPharmacy to the main model
    val dtp = DriveThroughPharmacyWithQ(sim, 1)
    dtp.arrivalRV.initialRandomSource = ExponentialRV(6.0, 1)
    dtp.serviceRV.initialRandomSource = ExponentialRV(3.0, 2)
    sim.simulate()
    sim.print()
//    val reporter: SimulationReporter = sim.simulationReporter
//    reporter.printAcrossReplicationSummaryStatistics()

    println(dtp.systemTimeHistogram)
}