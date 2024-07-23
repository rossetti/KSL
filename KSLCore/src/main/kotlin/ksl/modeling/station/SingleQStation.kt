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

class SingleQStation(
    parent: ModelElement,
    capacity: Int = 1,
    processingTime: RandomIfc,
    nextReceiver: ReceiveQObjectIfc,
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

    /**
     *  Indicates if the resource has units available.
     */
    val isResourceAvailable: Boolean
        get() = myResource.hasAvailableUnits

    /**
     *  Indicates if the queue is empty.
     */
    val isQueueEmpty: Boolean
        get() = myWaitingQ.isEmpty

    /**
     * Indicates if the queue is not empty
     */
    val isQueueNotEmpty: Boolean
        get() = myWaitingQ.isNotEmpty

    override fun receive(qObject: QObject) {
        myNS.increment() // new qObject arrived
        qObject.timeStamp = time
        myWaitingQ.enqueue(qObject) // enqueue the newly arriving qObject
        if (isResourceAvailable) { // server available
            serveNext()
        }
    }

    /**
     * Called to determine which waiting QObject will be served next Determines
     * the next customer, seizes the resource, and schedules the end of the
     * service.
     */
    private fun serveNext() {
        val nextCustomer = myWaitingQ.removeNext()!! //remove the next customer
        myResource.seize()
        // schedule end of service, if the customer can supply a value, use it otherwise use the processing time RV
        val delayTime = nextCustomer.valueObject?.value ?: myProcessingTimeRV.value
        schedule(this::endOfProcessing, delayTime, nextCustomer)
    }

    private fun endOfProcessing(event: KSLEvent<QObject>) {
        val leaving: QObject = event.message!!
        myNS.decrement() // qObject completed
        myNumProcessed.increment()
        mySysTime.value = (time - leaving.timeStamp)
        myResource.release()
        if (isQueueNotEmpty) { // queue is not empty
            serveNext()
        }
        send(leaving)
    }

}