/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.general.variables

import ksl.modeling.queue.Queue
import ksl.modeling.queue.QueueCIfc
import ksl.modeling.variable.*
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.GetValueIfc
import ksl.utilities.io.KSLFileUtil


fun main(){
    val arrivals = doubleArrayOf(
        3.00, 8.00, 2.00, 1.00, 3.00, 2.00, 2.00, 6.00, 5.00, 3.00, 3.00, 7.00, 5.00, 3.00, 2.00
    )
    val serviceTimes = doubleArrayOf(
        4.00, 4.00, 4.00, 3.00, 2.00, 4.00, 3.00, 2.00, 2.00, 4.00, 3.00, 2.00, 3.00, 4.00, 4.00
    )
    val arrivalsPath = KSLFileUtil.writeToFile(arrivals,"arrivals")
    val serviceTimePath = KSLFileUtil.writeToFile(serviceTimes,"serviceTimes")

    val m = Model("Test Historical Variables")
    val a = HistoricalVariable(m, arrivalsPath)
    val s = HistoricalVariable(m, serviceTimePath)
    val hq = HistoricalQueue(m, timeBtwArrivals = a, serviceTime = s)

    m.lengthOfReplication = 31.0
 //   m.lengthOfReplication = 100.0
    m.numberOfReplications = 1
    m.simulate()
    m.print()
}

class HistoricalQueue(
    parent: ModelElement,
    numServers: Int = 1,
    private val timeBtwArrivals: GetValueIfc,
    private val serviceTime: GetValueIfc,
    name: String? = null
) : ModelElement(parent, name) {
    init {
        require(numServers >= 1) { "The number of servers must be >= 1" }
    }

    var numServers = numServers
        set(value) {
            require(value >= 1) { "The number of servers must be >= 1" }
            field = value
        }

    private val myNumBusy: TWResponse = TWResponse(this, "NumBusy")
    private val myNS: TWResponse = TWResponse(this, "# in System")
    private val myNumCustomers: Counter = Counter(this, name = "Num Served")

    private val mySysTime: Response = Response(this, "System Time")
    val systemTime: ResponseCIfc
        get() = mySysTime
    private val myWaitingQ: Queue<QObject> = Queue(this, "ServiceQ")
    val waitingQ: QueueCIfc<QObject>
        get() = myWaitingQ

    private val myArrivalEventAction: ArrivalEventAction = ArrivalEventAction()
    private val myEndServiceEventAction: EndServiceEventAction = EndServiceEventAction()
    private val repEndTime = Response(this, "Rep End Time")
    override fun initialize() {
        super.initialize()
        // start the arrivals
        schedule(myArrivalEventAction, timeBtwArrivals)
    }

    override fun replicationEnded() {
        repEndTime.value = time
    }

    private inner class ArrivalEventAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            myNS.increment() // new customer arrived
            val arrivingCustomer = QObject()
            myWaitingQ.enqueue(arrivingCustomer) // enqueue the newly arriving customer
            if (myNumBusy.value < numServers) { // server available
                myNumBusy.increment() // make server busy
                val customer: QObject? = myWaitingQ.removeNext() //remove the next customer
                // schedule end of service, include the customer as the event's message
                schedule(myEndServiceEventAction, serviceTime, customer)
            }
            // always schedule the next arrival
            schedule(myArrivalEventAction, timeBtwArrivals)
        }
    }

    private inner class EndServiceEventAction : EventAction<QObject>() {
        override fun action(event: KSLEvent<QObject>) {
            myNumBusy.decrement() // customer is leaving server is freed
            if (!myWaitingQ.isEmpty) { // queue is not empty
                val customer: QObject? = myWaitingQ.removeNext() //remove the next customer
                myNumBusy.increment() // make server busy
                // schedule end of service
                schedule(myEndServiceEventAction, serviceTime, customer)
            }
            val departingCustomer = event.message!!
            mySysTime.value = (time - departingCustomer.createTime)
            myNS.decrement() // customer left system
            myNumCustomers.increment()
        }
    }
}