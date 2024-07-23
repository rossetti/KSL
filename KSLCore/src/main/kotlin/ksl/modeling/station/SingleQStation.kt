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

package ksl.modeling.station

import ksl.modeling.queue.Queue
import ksl.modeling.queue.QueueCIfc
import ksl.modeling.variable.*
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc

open class SingleQStation(
    parent: ModelElement,
    capacity: Int = 1,
    processingTime: RandomIfc,
    nextReceiver: ReceiveQObjectIfc? = null,
    name: String? = null
) : Station(parent, nextReceiver, name = name) {

    private val myResource: SResource = SResource(this, capacity, "${this.name}:Resource")

    private var myProcessingTimeRV: RandomVariable = RandomVariable(this, processingTime)
    val processingTimeRV: RandomSourceCIfc
        get() = myProcessingTimeRV

    private val myNS: TWResponse = TWResponse(this, "${this.name}:NumInSystem")
    val numInSystem: TWResponseCIfc
        get() = myNS

    private val mySysTime: Response = Response(this, "${this.name}:SystemTime")
    val systemTime: ResponseCIfc
        get() = mySysTime

    private val myNumProcessed: Counter = Counter(this, "${this.name}:NumProcessed")
    val numProcessed: CounterCIfc
        get() = myNumProcessed

    private val myWaitingQ: Queue<QObject> = Queue(this, "${this.name}:WaitingQ")
    val waitingQ: QueueCIfc<QObject>
        get() = myWaitingQ

    override fun receive(qObject: QObject) {
        myNS.increment() // new qObject arrived
        myWaitingQ.enqueue(qObject) // enqueue the newly arriving qObject
        if (isResourceAvailable) { // server available
            serveNext()
        }
    }

    /**
     *
     * @return true if a resource has available units
     */
    val isResourceAvailable: Boolean
        get() {
            return myResource.hasAvailableUnits
        }

    /**
     * Called to determine which waiting QObject will be served next Determines
     * the next customer, seizes the resource, and schedules the end of the
     * service.
     */
    protected open fun serveNext() {
        val nextCustomer = myWaitingQ.removeNext()!! //remove the next customer
        myResource.seize()
        // schedule end of service
        schedule(this::endOfProcessing, myProcessingTimeRV, nextCustomer)
    }

    protected open fun endOfProcessing(event: KSLEvent<QObject>) {
        val leavingCustomer: QObject = event.message!!
        myNS.decrement() // customer departed
        myResource.release()
        if (isQueueNotEmpty) { // queue is not empty
            serveNext()
        }
        send(leavingCustomer)
    }

    override fun send(qObject: QObject) {
        mySysTime.value = (time - qObject.createTime)
        myNS.decrement() // part left the center
        myNumProcessed.increment()
        super.send(qObject)
    }


    /**
     * Whether the queue is empty
     *
     * @return Whether the queue is empty
     */
    val isQueueEmpty: Boolean
        get() {
            return myWaitingQ.isEmpty
        }

    /**
     * Whether the queue is not empty
     *
     * @return Whether the queue is not empty
     */
    val isQueueNotEmpty: Boolean
        get() {
            return myWaitingQ.isNotEmpty
        }

}