/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.book.appendixD

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.modeling.elements.EventGenerator
import ksl.modeling.elements.EventGeneratorCIfc
import ksl.modeling.elements.GeneratorActionIfc
import ksl.modeling.queue.Queue
import ksl.modeling.queue.QueueCIfc
import ksl.modeling.variable.*
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.RVariableIfc

class GIGcQueue(
    parent: ModelElement,
    numServers: Int = 1,
    ad: RVariableIfc = ExponentialRV(1.0, 1),
    sd: RVariableIfc = ExponentialRV(0.5, 2),
    name: String? = null
) :
    ModelElement(parent, name = name) {

    @set:KSLControl(
        controlType = ControlType.INTEGER,
        lowerBound = 1.0
    )
    var numServers = numServers
        set(value) {
            require(value > 0)
            require(!model.isRunning) { "Cannot change the number of servers while the model is running!" }
            field = value
        }

    private var myServiceRV: RandomVariable = RandomVariable(this, sd, name = "${parent.name}:ServiceTime")
    val serviceRV: RandomVariableCIfc
        get() = myServiceRV

    private val myNumBusy: TWResponse = TWResponse(this, "NumBusy")
    val numBusyPharmacists: TWResponseCIfc
        get() = myNumBusy

    private val myNS: TWResponse = TWResponse(this, "Num in System")
    val numInSystem: TWResponseCIfc
        get() = myNS
    private val mySysTime: Response = Response(this, "System Time")
    val systemTime: ResponseCIfc
        get() = mySysTime

    private val myNumCustomers: Counter = Counter(this, "Num Served")
    val numCustomersServed: CounterCIfc
        get() = myNumCustomers
    private val myWaitingQ: Queue<QObject> = Queue(this, "WaitingQ")
    val waitingQ: QueueCIfc<QObject>
        get() = myWaitingQ

    private val myArrivalGenerator: EventGenerator = EventGenerator(this, Arrivals(), ad, ad)
    val arrivalGenerator: EventGeneratorCIfc
        get() = myArrivalGenerator

    private val endServiceEvent = this::endOfService

    private inner class Arrivals : GeneratorActionIfc {
        override fun generate(generator: EventGenerator) {
            myNS.increment() // new customer arrived
            val arrivingCustomer = QObject()
            myWaitingQ.enqueue(arrivingCustomer) // enqueue the newly arriving customer
            if (myNumBusy.value < numServers) { // server available
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