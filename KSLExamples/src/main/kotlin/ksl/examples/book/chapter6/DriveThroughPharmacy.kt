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
package ksl.examples.book.chapter6

import ksl.modeling.variable.*
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.ExponentialRV

/**
 * This model element illustrates how to model a simple multiple server
 * queueing system. The number of servers can be supplied. In
 * addition, the user can supply the distribution associated with the time
 * between arrivals and the service time distribution.
 * Statistics are collected on the average number of busy servers,
 * the average number of customers in the system, the average number of customers waiting,
 * and the number of customers served.
 */
class DriveThroughPharmacy(
    parent: ModelElement, numPharmacists: Int = 1,
    timeBtwArrivals: RandomIfc = ExponentialRV(1.0),
    serviceTime: RandomIfc = ExponentialRV(0.5),
    name: String? = null
) : ModelElement(parent, name) {
    init {
        require(numPharmacists > 0) { "The number of pharmacists must be >= 1" }
    }

    var numPharmacists: Int = numPharmacists
        set(value) {
            require(value > 0) { "The number of pharmacists must be >= 1" }
            field = value
        }

    private val myServiceRV: RandomVariable = RandomVariable(this, serviceTime, "Service RV")
    val serviceRV: RandomSourceCIfc
        get() = myServiceRV

    private val myArrivalRV: RandomVariable = RandomVariable(this, timeBtwArrivals, "Arrival RV")
    val arrivalRV: RandomSourceCIfc
        get() = myArrivalRV

    private val myQ: TWResponse = TWResponse(this, name = "PharmacyQ")
    val numInQ : TWResponseCIfc
        get() = myQ

    private val myNumBusy: TWResponse = TWResponse(this, name = "NumBusy")
    val numBusy: TWResponseCIfc
        get() = myNumBusy

    private val myNS: TWResponse = TWResponse(this, name = "# in System")
    val numInSystem: TWResponseCIfc
        get() = myNS

    private val myNumCustomers: Counter = Counter(this, name = "Num Served")
    val numCustomers: CounterCIfc
        get() = myNumCustomers

    private val myArrivalEventAction: ArrivalEventAction = ArrivalEventAction()
    private val myEndServiceEventAction: EndServiceEventAction = EndServiceEventAction()

    override fun initialize() {
        super.initialize()
        // start the arrivals
        schedule(myArrivalEventAction, myArrivalRV)
    }

    private inner class ArrivalEventAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            myNS.increment() // new customer arrived
            if (myNumBusy.value < numPharmacists) { // server available
                myNumBusy.increment() // make server busy
                // schedule end of service
                schedule(myEndServiceEventAction, myServiceRV)
            } else {
                myQ.increment() // customer must wait
            }
            // always schedule the next arrival
            schedule(myArrivalEventAction, myArrivalRV)
        }
    }

    private inner class EndServiceEventAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            myNumBusy.decrement() // customer is leaving server is freed
            if (myQ.value > 0) { // queue is not empty
                myQ.decrement() //remove the next customer
                myNumBusy.increment() // make server busy
                // schedule end of service
                schedule(myEndServiceEventAction, myServiceRV)
            }
            myNS.decrement() // customer left system
            myNumCustomers.increment()
        }
    }
}