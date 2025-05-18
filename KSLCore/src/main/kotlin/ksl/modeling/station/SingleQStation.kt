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

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.modeling.queue.Queue
import ksl.modeling.queue.QueueCIfc
import ksl.modeling.variable.*
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.RVariableIfc

/**
 *  Models a simple work station that has a single queue for holding received qObjects
 *  for processing and a simple resource that is used during the processing.
 *
 *  A QObject may have an object that implements the GetValueIfc attached. If so,
 *  the current value from this object is used as the processing time at the station. If
 *  not attached, then the specified processing time for the station will be used. Thus,
 *  a processed QObject instance can bring its own processing time.  In addition, a QObject
 *  may have an instance of a QObjectSenderIfc interface attached. If one is attached, the
 *  sender will be used to determine where to send the qObject. If a sender is
 *  not attached, then the specified next receiver will be used. Thus,
 *  a processed QObject instance can determine where it goes to next after processing.
 *
 *  @param parent the model element serving as this element's parent
 *  @param activityTime the processing time at the station. The default is a 0.0 delay.
 *  @param resource the resource to use at the station. The default of null will cause
 *  a resource of capacity 1 to be created and used at the station
 *  @param nextReceiver the receiving location that will receive the processed qObjects
 *  once the processing has been completed. A default of NotImplementedReceiver, indicates that there is no
 *  receiver implemented. If no receiver is present, there will be a run-time error.
 *  @param name the name of the station
 */
open class SingleQStation(
    parent: ModelElement,
    activityTime: RVariableIfc = ConstantRV.ZERO,
    resource: SResource? = null,
    nextReceiver: QObjectReceiverIfc = NotImplementedReceiver,
    name: String? = null
) : Station(parent, nextReceiver, name = name), SingleQStationCIfc {

    /**
     * Allows the single queue station to be created with an initial capacity specification
     * for its resource.
     *
     *  @param parent the model element serving as this element's parent
     *  @param activityTime the processing time at the station. The default is a 0.0 delay.
     *  @param initialCapacity the initial capacity of the resource at the station.
     *  @param nextReceiver the receiving location that will receive the processed qObjects
     *  once the processing has been completed. A default of NotImplementedReceiver, indicates that there is no
     *  receiver implemented. If no receiver is present, there will be a run-time error.
     *  @param name the name of the station
     */
    constructor(
        parent: ModelElement,
        activityTime: RVariableIfc = ConstantRV.ZERO,
        initialCapacity: Int,
        nextReceiver: QObjectReceiverIfc = NotImplementedReceiver,
        name: String? = null
    ) : this(parent, activityTime, null, nextReceiver, name) {
        require(initialCapacity > 0) { "initialCapacity must be positive." }
        myResource.initialCapacity = initialCapacity
    }

    /**
     *  If true, the instance will attempt to use the QObject that is experiencing
     *  the activity to determine the activity time by referencing the QObject's
     *  valueObject. If false (the default), the supplied activity time will be used
     */
    @set:KSLControl(controlType = ControlType.BOOLEAN)
    var useQObjectForActivityTime: Boolean = false

    protected val myResource: SResource = resource ?: SResource(this, 1, "${this.name}:R")
    override val resource: SResourceCIfc
        get() = myResource

    protected var myActivityTimeRV: RandomVariable = RandomVariable(this, activityTime, name = "${this.name}:ActivityRV")
    override val activityTimeRV: RandomVariableCIfc
        get() = myActivityTimeRV

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
     *  Receives the qObject instance for processing. Handle the queuing
     *  if the resource is not available and begins service for the next customer.
     */
    override fun process(arrivingQObject: QObject) {
        // enqueue the newly arriving qObject
        myWaitingQ.enqueue(arrivingQObject)
        if (isResourceAvailable) {
            serveNext()
        }
    }

    /**
     * Called to determine which waiting QObject will be served next Determines
     * the next customer, seizes the resource, and schedules the end of the
     * service.
     */
    protected fun serveNext() {
        //remove the next customer
        val nextCustomer = myWaitingQ.removeNext()!!
        myResource.seize()
        // schedule end of service, if the customer can supply a value,
        // use it otherwise use the processing time RV
        schedule(this::endOfProcessing, activityTime(nextCustomer), nextCustomer)
    }

    /**
     *  Could be overridden to supply different approach for determining the service delay
     */
    protected open fun activityTime(qObject: QObject) : Double {
        if (useQObjectForActivityTime) {
            return qObject.valueObject?.value ?: myActivityTimeRV.value
        }
        return myActivityTimeRV.value
    }

    /**
     *  The end of processing event actions. Collect departing statistics and send the qObject
     *  to its next receiver. If the queue is not empty, continue processing the next qObject.
     */
    private fun endOfProcessing(event: KSLEvent<QObject>) {
        val leaving: QObject = event.message!!
        myResource.release()
        if (isQueueNotEmpty) { // queue is not empty
            serveNext()
        }
        sendToNextReceiver(leaving)
    }

}