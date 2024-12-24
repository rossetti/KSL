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

import ksl.modeling.elements.EventGenerator
import ksl.modeling.elements.GeneratorActionIfc
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

fun main() {
    val model = Model("Drive Through Pharmacy")
    model.numberOfReplications = 30
    model.lengthOfReplication = 20000.0
    model.lengthOfReplicationWarmUp = 5000.0
    // add the model element to the main model
    val dtp = DriveThroughPharmacyWithResource(model, 1, name = "Pharmacy")
    dtp.arrivalRV.initialRandomSource = ExponentialRV(6.0, 1)
    dtp.serviceRV.initialRandomSource = ExponentialRV(3.0, 2)
    model.simulate()
    model.print()
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
    ad: RandomIfc = ExponentialRV(1.0, 1),
    sd: RandomIfc = ExponentialRV(0.5, 2),
    name: String? = null
) :
    ModelElement(parent, name = name) {

    private val myPharmacists: SResource = SResource(this, numServers, "${this.name}:Pharmacists")
    val resource: SResourceCIfc
        get() = myPharmacists

    private var myServiceRV: RandomVariable = RandomVariable(this, sd)
    val serviceRV: RandomSourceCIfc
        get() = myServiceRV
    private var myArrivalRV: RandomVariable = RandomVariable(parent, ad)
    val arrivalRV: RandomSourceCIfc
        get() = myArrivalRV

    private val myNS: TWResponse = TWResponse(this, "${this.name}:NumInSystem")
    val numInSystem: TWResponseCIfc
        get() = myNS
    private val mySysTime: Response = Response(this, "${this.name}:SystemTime")
    val systemTime: ResponseCIfc
        get() = mySysTime

    private val myNumCustomers: Counter = Counter(this, "${this.name}:NumServed")
    val numCustomersServed: CounterCIfc
        get() = myNumCustomers
    private val myWaitingQ: Queue<QObject> = Queue(this, "${this.name}:PharmacyQ")
    val waitingQ: QueueCIfc<QObject>
        get() = myWaitingQ

    private val myArrivalGenerator: EventGenerator = EventGenerator(
        this, this::arrival, myArrivalRV, myArrivalRV)

    private fun arrival(generator: EventGenerator) {
        myNS.increment() // new customer arrived
        val arrivingCustomer = QObject()
        myWaitingQ.enqueue(arrivingCustomer) // enqueue the newly arriving customer
        if (myPharmacists.hasAvailableUnits) {
            myPharmacists.seize()
            val customer: QObject? = myWaitingQ.removeNext() //remove the next customer
            // schedule end of service, include the customer as the event's message
            schedule(this::endOfService, myServiceRV, customer)
        }
    }

    private fun endOfService(event: KSLEvent<QObject>) {
        myPharmacists.release()
        if (!myWaitingQ.isEmpty) { // queue is not empty
            myPharmacists.seize()
            val customer: QObject? = myWaitingQ.removeNext() //remove the next customer
            // schedule end of service
            schedule(this::endOfService, myServiceRV, customer)
        }
        departSystem(event.message!!)
    }

    private fun departSystem(departingCustomer: QObject) {
        mySysTime.value = (time - departingCustomer.createTime)
        myNS.decrement() // customer left system
        myNumCustomers.increment()
    }
}

