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

import ksl.modeling.elements.*
import ksl.modeling.queue.Queue
import ksl.modeling.queue.QueueCIfc
import ksl.modeling.station.SResource
import ksl.modeling.station.SResourceCIfc
import ksl.modeling.variable.*
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.statistic.HistogramIfc

fun main() {
    val model = Model("Drive Through Pharmacy")
    model.numberOfReplications = 30
    model.lengthOfReplication = 20000.0
    model.lengthOfReplicationWarmUp = 5000.0
    // add the model element to the main model
    val dtp = DriveThroughPharmacyWithResource(model, 1, name = "Pharmacy")
    dtp.arrivalGenerator.initialTimeBtwEvents = ExponentialRV(6.0, 1)
    dtp.serviceRV.initialRandomSource = ExponentialRV(3.0, 2)
    model.simulate()
    model.print()
//    val hp = dtp.systemTimeHistogram.histogramPlot()
//    hp.showInBrowser("System Time Histogram")
}

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
class DriveThroughPharmacyWithResource(
    parent: ModelElement,
    numServers: Int = 1,
    name: String? = null
) : ModelElement(parent, name = name) {

    private val myPharmacists: SResource = SResource(
        parent = this,
        capacity = numServers,
        name = "${this.name}:Pharmacists"
    )
    val resource: SResourceCIfc
        get() = myPharmacists

    private var myServiceRV: RandomVariable = RandomVariable(parent = this, rSource = ExponentialRV(0.5, 2))
    val serviceRV: RandomVariableCIfc
        get() = myServiceRV

    private val myNS: TWResponse = TWResponse(parent = this, name = "${this.name}:NumInSystem")
    val numInSystem: TWResponseCIfc
        get() = myNS
    private val mySysTime: Response = Response(parent = this, name = "${this.name}:SystemTime")
    val systemTime: ResponseCIfc
        get() = mySysTime

    private val myNumCustomers: Counter = Counter(parent = this, name = "${this.name}:NumServed")
    val numCustomersServed: CounterCIfc
        get() = myNumCustomers

    private val myWaitingQ: Queue<QObject> = Queue(parent = this, name = "${this.name}:PharmacyQ")
    val waitingQ: QueueCIfc<QObject>
        get() = myWaitingQ

    private val mySTGT4: IndicatorResponse = IndicatorResponse(
        predicate = { x -> x >= 4.0 },
        observedResponse = mySysTime,
        name = "SysTime >= 4 minutes"
    )
    val probSystemTimeGT4Minutes: ResponseCIfc
        get() = mySTGT4

    private val mySysTimeHistogram: HistogramResponse = HistogramResponse(theResponse = mySysTime)
    val systemTimeHistogram: HistogramIfc
        get() = mySysTimeHistogram.histogram

    private val myInQ = IntegerFrequencyResponse(parent = this, name = "${this.name}:NQUponArrival")

    private val endServiceEvent = this::endOfService

    private val ad  = ExponentialRV(1.0, 1)
    private val myArrivalGenerator: EventGenerator = EventGenerator(
        parent = this, generateAction = this::arrival, timeUntilFirstRV = ad, timeBtwEventsRV = ad
    )
    val arrivalGenerator: EventGeneratorRVCIfc
        get() = myArrivalGenerator

    private fun arrival(generator: EventGeneratorIfc) {
        myNS.increment() // new customer arrived
        myInQ.value = myWaitingQ.numInQ.value.toInt()
        val arrivingCustomer = QObject()
        myWaitingQ.enqueue(qObject = arrivingCustomer) // enqueue the newly arriving customer
        if (myPharmacists.hasAvailableUnits) {
            myPharmacists.seize()
            val customer: QObject? = myWaitingQ.removeNext() //remove the next customer
            // schedule end of service, include the customer as the event's message
            schedule(eventAction = endServiceEvent, timeToEvent = myServiceRV, message = customer)
        }
    }

    private fun endOfService(event: KSLEvent<QObject>) {
        myPharmacists.release()
        if (!myWaitingQ.isEmpty) { // queue is not empty
            myPharmacists.seize()
            val customer: QObject? = myWaitingQ.removeNext() //remove the next customer
            // schedule end of service
            schedule(eventAction = endServiceEvent, timeToEvent = myServiceRV, message = customer)
        }
        departSystem(departingCustomer = event.message!!)
    }

    private fun departSystem(departingCustomer: QObject) {
        mySysTime.value = (time - departingCustomer.createTime)
        myNS.decrement() // customer left system
        myNumCustomers.increment()
    }
}

