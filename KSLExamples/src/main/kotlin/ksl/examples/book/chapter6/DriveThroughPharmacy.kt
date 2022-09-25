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
package ksl.examples.book.chapter6

import ksl.modeling.variable.Counter
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.TWResponse
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

    var numberOfPharmacists: Int = numPharmacists
        set(value) {
            require(value > 0) { "The number of pharmacists must be >= 1" }
            field = value
        }

    private val myServiceRV: RandomVariable = RandomVariable(this, serviceTime, "Service RV")
    private val myArrivalRV: RandomVariable = RandomVariable(this, timeBtwArrivals, "Arrival RV")
    private val myQ: TWResponse = TWResponse(this, name = "PharmacyQ")
    private val myNumBusy: TWResponse = TWResponse(this, name = "NumBusy")
    private val myNS: TWResponse = TWResponse(this, name = "# in System")
    private val myNumCustomers: Counter = Counter(this, name = "Num Served")
    private val myArrivalEventAction: ArrivalEventAction = ArrivalEventAction()
    private val myEndServiceEventAction: EndServiceEventAction = EndServiceEventAction()


    fun setServiceTimeRandomSource(serviceTime: RandomIfc) {
        myServiceRV.initialRandomSource = serviceTime
    }

    fun setTimeBtwArrivalRandomSource(timeBtwArrivals: RandomIfc) {
        myArrivalRV.initialRandomSource = timeBtwArrivals
    }

    override fun initialize() {
        super.initialize()
        // start the arrivals
        schedule(myArrivalEventAction, myArrivalRV)
    }

    private inner class ArrivalEventAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            myNS.increment() // new customer arrived
            if (myNumBusy.value < numberOfPharmacists) { // server available
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