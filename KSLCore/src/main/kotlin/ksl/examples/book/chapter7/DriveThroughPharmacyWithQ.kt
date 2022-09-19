/*
 * Copyright (c) 2018. Manuel D. Rossetti, rossetti@uark.edu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package ksl.examples.book.chapter7

import ksl.modeling.queue.QObject
import ksl.modeling.queue.Queue
import ksl.modeling.variable.*
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.simulation.SimulationReporter
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
    ModelElement(parent, name = null) {

    private var myNumPharmacists = numServers
    private var myServiceRS: RandomIfc = sd
    private var myArrivalRS: RandomIfc = ad
    private var myServiceRV: RandomVariable = RandomVariable(this, sd)
    private var myArrivalRV: RandomVariable = RandomVariable(parent, ad)
    private val myNumBusy: TWResponse = TWResponse(this, "NumBusy")
    private val myNS: TWResponse = TWResponse(this, "# in System")
    private val mySysTime: Response = Response(this, "System Time")
    private val myArrivalEventAction: ArrivalEventAction = ArrivalEventAction()
    private val myEndServiceEventAction: EndServiceEventAction = EndServiceEventAction()
    private val myNumCustomers: Counter = Counter(this, "Num Served")
    private val myWaitingQ: Queue<QObject> = Queue(this, "PharmacyQ")
    private val myTotal: AggregateTWResponse = AggregateTWResponse(this, "aggregate # in system")
    private val mySysTimeHistogram: ResponseHistogram = ResponseHistogram(mySysTime, theBreakPointMinDataSize = 200)
    private val mySTGT3: IndicatorResponse = IndicatorResponse({ x -> x > 4.0 }, mySysTime, "SysTime>4.0")
    val systemTimeHistogram: HistogramIfc
        get() = mySysTimeHistogram.histogram

    init {
        myTotal.observe(myWaitingQ.numInQ)
        myTotal.observe(myNumBusy)
    }

    val systemTimeResponse: Response
        get() = mySysTime
    val numInSystemResponse: TWResponse
        get() = myNS
    val numberOfServers: Int
        get() = myNumPharmacists

    fun setNumberOfPharmacists(n: Int) {
        require(n >= 0)
        myNumPharmacists = n
    }

    fun setServiceRS(d: RandomIfc) {
        myServiceRS = d
        myServiceRV.initialRandomSource = myServiceRS
    }

    fun setArrivalRS(d: RandomIfc) {
        myArrivalRS = d
        myArrivalRV.initialRandomSource = myArrivalRS
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
        val arrivingCustomer = QObject(time)
        myWaitingQ.enqueue(arrivingCustomer) // enqueue the newly arriving customer
        if (myNumBusy.value < myNumPharmacists) { // server available
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
    dtp.setArrivalRS(ExponentialRV(6.0, 1))
    dtp.setServiceRS(ExponentialRV(3.0, 2))
    sim.simulate()
    val reporter: SimulationReporter = sim.simulationReporter
    reporter.printAcrossReplicationSummaryStatistics()

    println(dtp.systemTimeHistogram)
}