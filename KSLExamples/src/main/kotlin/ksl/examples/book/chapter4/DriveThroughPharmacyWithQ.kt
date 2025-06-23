/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
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
package ksl.examples.book.chapter4

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.modeling.elements.*
import ksl.modeling.queue.Queue
import ksl.modeling.queue.QueueCIfc
import ksl.modeling.variable.*
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.RVariableIfc
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
    name: String? = null
) :
    ModelElement(parent, name = name) {

    @set:KSLControl(
        controlType = ControlType.INTEGER,
        lowerBound = 1.0
    )
    var numPharmacists = numServers
        set(value) {
            require(value > 0)
            require(!model.isRunning) { "Cannot change the number of pharmacists while the model is running!" }
            field = value
        }

    private var myServiceRV: RandomVariable = RandomVariable(this, rSource = ExponentialRV(0.5, 2))
    val serviceRV: RandomVariableCIfc
        get() = myServiceRV

    private val myNumBusy: TWResponse = TWResponse(parent = this, name = "NumBusy")
    val numBusyPharmacists: TWResponseCIfc
        get() = myNumBusy

    private val myNS: TWResponse = TWResponse(parent = this, name = "Num in System")
    val numInSystem: TWResponseCIfc
        get() = myNS
    private val mySysTime: Response = Response(parent = this, name = "System Time")
    val systemTime: ResponseCIfc
        get() = mySysTime

    private val myNumCustomers: Counter = Counter(parent = this, name = "Num Served")
    val numCustomersServed: CounterCIfc
        get() = myNumCustomers
    private val myWaitingQ: Queue<QObject> = Queue(parent = this, name = "PharmacyQ")
    val waitingQ: QueueCIfc<QObject>
        get() = myWaitingQ

    private val mySysTimeHistogram: HistogramResponse = HistogramResponse(theResponse = mySysTime)
    val systemTimeHistogram: HistogramIfc
        get() = mySysTimeHistogram.histogram

    private val mySTGT4: IndicatorResponse = IndicatorResponse(
        predicate = { x -> x >= 4.0 },
        observedResponse = mySysTime,
        name = "SysTime >= 4 minutes"
    )
    val probSystemTimeGT4Minutes: ResponseCIfc
        get() = mySTGT4

    private val ad = ExponentialRV(1.0, 1)
    private val myArrivalGenerator: EventGenerator = EventGenerator(
        parent = this,
        generateAction = Arrivals(),
        timeUntilFirstRV = ad,
        timeBtwEventsRV = ad
    )
    val arrivalGenerator: EventGeneratorRVCIfc
        get() = myArrivalGenerator

    private val endServiceEvent = this::endOfService

    private val myInQ = IntegerFrequencyResponse(parent = this, name = "NQ Upon Arrival")

    private inner class Arrivals : GeneratorActionIfc {
        override fun generate(generator: EventGeneratorIfc) {
           // println("$time > arriving customer")
            myNS.increment() // new customer arrived
            val arrivingCustomer = QObject()
            myInQ.value = myWaitingQ.numInQ.value.toInt()
            myWaitingQ.enqueue(arrivingCustomer) // enqueue the newly arriving customer
            if (myNumBusy.value < numPharmacists) { // server available
                myNumBusy.increment() // make server busy
                val customer: QObject? = myWaitingQ.removeNext() //remove the next customer
                // schedule end of service, include the customer as the event's message
                schedule(endServiceEvent, myServiceRV, customer)
            }
        }
    }

    private fun endOfService(event: KSLEvent<QObject>) {
        myNumBusy.decrement() // customer is leaving server is freed
        if (!myWaitingQ.isEmpty) { // queue is not empty
            val customer: QObject? = myWaitingQ.removeNext() //remove the next customer
            myNumBusy.increment() // make server busy
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

