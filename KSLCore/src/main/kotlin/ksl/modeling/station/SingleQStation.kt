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


interface SingleQStationCIfc {
    val resource: SResourceCIfc
    val processingTimeRV: RandomSourceCIfc
    val numAtStation: TWResponseCIfc
    val stationTime: ResponseCIfc
    val numProcessed: CounterCIfc
    val waitingQ: QueueCIfc<ModelElement.QObject>

    /**
     *  Indicates if the resource has units available.
     */
    val isResourceAvailable: Boolean

    /**
     *  Indicates if the queue is empty.
     */
    val isQueueEmpty: Boolean

    /**
     * Indicates if the queue is not empty
     */
    val isQueueNotEmpty: Boolean
}

/**
 *  Models a simple work station that has a single queue for holding received qObjects
 *  for processing and a simple resource that is used during the processing.
 *
 *  A QObject may have an object that implements the GetValueIfc attached. If so,
 *  the current value from this object is used as the processing time at the station. If
 *  not attached, then the specified processing time for the station will be used. Thus,
 *  a processed QObject instance can bring its own processing time.  In addition, a QObject
 *  may have a ListIterator<QObjectReceiverIfc> attached. If one is attached, the
 *  iterator will be used to determine where to send the qObject. If an iterator is
 *  not attached, then the specified next receiver (if not null)  will be used. Thus,
 *  a processed QObject instance can determine where it goes to next after processing.
 *
 *  @param parent the model element serving as this element's parent
 *  @param processingTime the processing time at the station
 *  @param resource the resource to use at the station. The default of null will cause
 *  a resource of capacity 1 to be created and used at the station
 *  @param nextReceiver the receiving location that will receive the processed qObjects
 *  once the processing has been completed. A default of null, indicates that there is no
 *  receiver. If no receiver is present, the processed qObject are sent silently nowhere.
 *  @param name the name of the station
 */
open class SingleQStation(
    parent: ModelElement,
    processingTime: RandomIfc,
    resource: SResource? = null,
    nextReceiver: QObjectReceiverIfc? = null,
    name: String? = null
) : Station(parent, nextReceiver, name = name), SingleQStationCIfc {

    protected val myResource: SResource = resource ?: SResource(this, 1, "${this.name}:R")
    override val resource:  SResourceCIfc
        get() = myResource

    protected var myProcessingTimeRV: RandomVariable = RandomVariable(this, processingTime)
    override val processingTimeRV: RandomSourceCIfc
        get() = myProcessingTimeRV

    protected val myNS: TWResponse = TWResponse(this, "${this.name}:NS")
    override val numAtStation: TWResponseCIfc
        get() = myNS

    protected val myStationTime: Response = Response(this, "${this.name}:StationTime")
    override val stationTime: ResponseCIfc
        get() = myStationTime

    protected val myNumProcessed: Counter = Counter(this, "${this.name}:NumProcessed")
    override val numProcessed: CounterCIfc
        get() = myNumProcessed

    protected val myWaitingQ: Queue<QObject> = Queue(this, "${this.name}:Q")
    override val waitingQ: QueueCIfc<QObject>
        get() = myWaitingQ

    /**
     *  Indicates if the resource has units available.
     */
    override val isResourceAvailable: Boolean
        get() = myResource.hasAvailableUnits

    /**
     *  Indicates if the queue is empty.
     */
    override val isQueueEmpty: Boolean
        get() = myWaitingQ.isEmpty

    /**
     * Indicates if the queue is not empty
     */
    override val isQueueNotEmpty: Boolean
        get() = myWaitingQ.isNotEmpty

    /**
     *  This function can be overridden to provide logic
     *  upon entry to the station before any other state
     *  changes occur.
     */
    open fun onEntry(qObject: QObject){

    }

    /**
     *  Receives the qObject instance for processing. Handle the queuing
     *  if the resource is not available and begins service for the next customer.
     */
    final override fun receive(qObject: QObject) {
        onEntry(qObject)
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
    protected fun serveNext() {
        val nextCustomer = myWaitingQ.removeNext()!! //remove the next customer
        myResource.seize()
        // schedule end of service, if the customer can supply a value, use it otherwise use the processing time RV
        schedule(this::endOfProcessing, delayTime(nextCustomer), nextCustomer)
    }

    /**
     *  Could be overridden to supply different approach for determining the service delay
     */
    protected fun delayTime(qObject: QObject) : Double {
        return qObject.valueObject?.value ?: myProcessingTimeRV.value
    }

    /**
     *  The end of processing event actions. Collect departing statistics and send the qObject
     *  to its next receiver. If the queue is not empty, continue processing the next qObject.
     */
    private fun endOfProcessing(event: KSLEvent<QObject>) {
        val leaving: QObject = event.message!!
        myNS.decrement() // qObject completed
        myNumProcessed.increment()
        myStationTime.value = (time - leaving.timeStamp)
        myResource.release()
        if (isQueueNotEmpty) { // queue is not empty
            serveNext()
        }
        onExit(leaving)
        sendToNextReceiver(leaving)
    }

    /**
     *  This function can be overridden to provide logic
     *  upon exit from the station before being sent to the
     *  next receiver.
     */
    open fun onExit(qObject: QObject){

    }

}