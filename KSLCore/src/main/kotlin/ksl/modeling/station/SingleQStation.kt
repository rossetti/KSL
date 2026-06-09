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
import ksl.modeling.entity.CapacitySchedule
import ksl.modeling.queue.Queue
import ksl.modeling.queue.QueueCIfc
import ksl.modeling.variable.*
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.GetValueIfc
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
@Suppress("unused")
open class SingleQStation @JvmOverloads constructor(
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
    @JvmOverloads
    constructor(
        parent: ModelElement,
        activityTime: RVariableIfc = ConstantRV.ZERO,
        initialCapacity: Int,
        nextReceiver: QObjectReceiverIfc = NotImplementedReceiver,
        name: String? = null
    ) : this(parent, activityTime, null, nextReceiver, name) {
        require(initialCapacity > 0) { "The initial capacity must be positive." }
        myResource.initialCapacity = initialCapacity
    }

    /**
     *  If true, the instance will attempt to use the QObject that is experiencing
     *  the activity to determine the activity time by referencing the QObject's
     *  valueObject. If false (the default), the supplied activity time will be used
     */
    @set:KSLControl(controlType = ControlType.BOOLEAN)
    var useQObjectForActivityTime: Boolean = false

    /**
     *  If set and the station does not use the qObject for determining the activity time,
     *  then the supplied function will be used.
     */
    var activityTime: StationActivityTimeIfc? = null

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
     *  Optional sequence-dependent setup (changeover) rule. When set, a setup time
     *  is incurred before serving each QObject, based on the previously served type
     *  and the arriving type. The setup precedes the activity time within the same
     *  service.
     */
    var setupTimeRule: SetupTimeIfc? = null

    private var myLastServedType: Int? = null

    private val mySetupTime: Response = Response(this, "${this.name}:SetupTime")

    /** The setup time incurred per served QObject (zero when no changeover occurs). */
    val setupTimeResponse: ResponseCIfc
        get() = mySetupTime

    /**
     *  Optional patience (reneging) time. When set, each enqueued QObject will
     *  leave the queue if it has not begun service within a sampled patience time,
     *  and is routed to [renegeReceiver] (or dropped if that is null). Note: if
     *  reneged instances are dropped rather than routed to a sink, the owning
     *  network's number-in-system will not reflect their departure.
     */
    var renegeTime: GetValueIfc? = null

    /** Where reneging (impatient) instances are sent; null drops them. */
    var renegeReceiver: QObjectReceiverIfc? = null

    private val myRenegeEvents = mutableMapOf<QObject, KSLEvent<QObject>>()

    private val myNumReneged: Counter = Counter(this, "${this.name}:NumReneged")

    /** The number of instances that reneged (left the queue out of impatience). */
    val numReneged: CounterCIfc
        get() = myNumReneged

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

    // tracks in-service jobs so they can be preempted on a failure
    private class ServiceSlot(val event: KSLEvent<QObject>, val endTime: Double)
    private val myInService = mutableMapOf<QObject, ServiceSlot>()

    // jobs preempted by a failure, awaiting resumption with their remaining time
    private class PreemptedJob(val qObject: QObject, val remaining: Double)
    private val myPreempted = ArrayDeque<PreemptedJob>()

    init {
        // When the resource gains units (e.g., a capacity increase from a schedule
        // or after a repair), resume any preempted jobs and serve the waiting queue.
        myResource.attachUnitsAvailableListener { serveWaitingCustomers() }
        // When the resource fails under preempt-resume, interrupt in-service work.
        myResource.attachResourceFailureListener { preemptInService() }
    }

    /** Sets the failure effect to preempt-resume (the default). */
    fun usePreemptResumeEffect() {
        myResource.failureEffect = FailureEffect.PREEMPT_RESUME
    }

    /** Sets the failure effect to finish-then-fail (in-service work completes first). */
    fun useFinishThenFailEffect() {
        myResource.failureEffect = FailureEffect.FINISH_THEN_FAIL
    }

    /**
     *  Preempts all in-service jobs: cancels each scheduled completion, banks the
     *  remaining service time, and frees the unit (without counting a completion).
     *  Resumption occurs after repair via [serveWaitingCustomers].
     */
    private fun preemptInService() {
        if (myInService.isEmpty()) return
        val now = time
        for ((job, slot) in myInService.toList()) {
            if (slot.event.isScheduled) {
                slot.event.cancel = true
            }
            val remaining = (slot.endTime - now).coerceAtLeast(0.0)
            myPreempted.addLast(PreemptedJob(job, remaining))
            myResource.preemptRelease()
        }
        myInService.clear()
    }

    /** Resumes preempted jobs (for their remaining time) while units are available. */
    private fun resumePreempted() {
        while (myPreempted.isNotEmpty() && isResourceAvailable) {
            val pj = myPreempted.removeFirst()
            myResource.seize()
            val event = schedule(this::endOfProcessing, pj.remaining, pj.qObject)
            myInService[pj.qObject] = ServiceSlot(event, time + pj.remaining)
        }
    }

    /**
     *  Drives this station's resource capacity from a [CapacitySchedule], enabling
     *  shift/availability modeling. Decreases use IGNORE-rule semantics (in-service
     *  units are not interrupted); increases immediately serve the waiting queue.
     */
    fun useCapacitySchedule(schedule: CapacitySchedule) {
        myResource.useSchedule(schedule)
    }

    /**
     *  Configures time-based (calendar-clock) failures for this station's resource:
     *  time-to-failure and time-to-repair are sampled from the supplied random
     *  variables. Failures are finish-then-fail and take the whole resource down.
     */
    fun useTimeBasedFailures(timeToFailure: RVariableIfc, timeToRepair: RVariableIfc) {
        myResource.useTimeBasedFailures(timeToFailure, timeToRepair)
    }

    /**
     *  Configures usage (count) based failures for this station's resource: the
     *  resource fails after a sampled number of completed services, then is down
     *  for a sampled repair time. Failures are finish-then-fail and full-down.
     */
    fun useCountBasedFailures(countToFailure: RVariableIfc, timeToRepair: RVariableIfc) {
        myResource.useCountBasedFailures(countToFailure, timeToRepair)
    }

    /**
     *  Configures operating-time (usage) based failures for this station's resource:
     *  the resource fails after a sampled amount of accumulated *busy* time and is
     *  down for a sampled repair time. The operating clock pauses while the station
     *  is idle, off-shift, or failed.
     */
    fun useOperatingTimeBasedFailures(operatingTimeToFailure: RVariableIfc, timeToRepair: RVariableIfc) {
        myResource.useOperatingTimeBasedFailures(operatingTimeToFailure, timeToRepair)
    }

    /**
     *  Receives the qObject instance for processing. Handle the queuing
     *  if the resource is not available and begins service for the next customer.
     */
    override fun process(arrivingQObject: QObject) {
        // enqueue the newly arriving qObject
        myWaitingQ.enqueue(arrivingQObject)
        // schedule reneging (patience) if configured; cancelled when service begins
        renegeTime?.let { rt ->
            myRenegeEvents[arrivingQObject] = schedule(this::renegeAction, rt.value, arrivingQObject)
        }
        if (isResourceAvailable) {
            serveNext()
        }
    }

    private fun renegeAction(event: KSLEvent<QObject>) {
        val qObject: QObject = event.message!!
        myRenegeEvents.remove(qObject)
        if (myWaitingQ.contains(qObject)) {
            myWaitingQ.remove(qObject)
            myNS.decrement() // left the station without being processed
            myNumReneged.increment()
            renegeReceiver?.receive(qObject)
        }
    }

    /**
     * Serves waiting customers while the resource has available units and the
     * queue is not empty. Invoked when the resource's capacity increases.
     */
    protected fun serveWaitingCustomers() {
        // resume any preempted work first, then serve the queue with leftover capacity
        resumePreempted()
        while (isResourceAvailable && isQueueNotEmpty) {
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
        // service is beginning: cancel any pending reneging for this customer
        myRenegeEvents.remove(nextCustomer)?.let { if (it.isScheduled) it.cancel = true }
        myResource.seize()
        // optional sequence-dependent setup precedes the activity within the service
        var setup = 0.0
        setupTimeRule?.let { rule ->
            setup = rule.setupTime(myLastServedType, nextCustomer.qObjectType, nextCustomer)
            mySetupTime.value = setup
        }
        myLastServedType = nextCustomer.qObjectType
        // schedule end of service, if the customer can supply a value,
        // use it otherwise use the processing time RV
        val st = setup + activityTime(nextCustomer)
        val event = schedule(this::endOfProcessing, st, nextCustomer)
        myInService[nextCustomer] = ServiceSlot(event, time + st)
    }

    /**
     *  Could be overridden to supply different approach for determining the activity delay.
     *  The current approach does the following.
     *  1. Checks the `useQObjectForActivityTime` option and if true uses the
     *  qObject's valueObject to determine the activity time. If this option is used, an illegal state
     *  exception will occur if the qObject's valueObject property has not been set.
     *  2. Checks if the `activityTime` property has been set and if so uses the
     *  supplied `StationActivityTimeIfc` to determine the activity time.
     *  3. If neither the first two options are used, then the activity time
     *  will be determined by the supplied `RVariableIfc`, which is zero by default.
     */
    protected open fun activityTime(qObject: QObject) : Double {
        if (useQObjectForActivityTime) {
            checkNotNull(qObject.valueObject) {"The station was told to use the qObject's valueObject for the activity time, but it was null"}
            return qObject.valueObject!!.value
        }
        activityTime?.let { return it.activityTime(qObject, this) }
        return myActivityTimeRV.value
    }

    /**
     *  The end of processing event actions. Collect departing statistics and send the qObject
     *  to its next receiver. If the queue is not empty, continue processing the next qObject.
     */
    private fun endOfProcessing(event: KSLEvent<QObject>) {
        val leaving: QObject = event.message!!
        myInService.remove(leaving)
        myResource.release()
        // The just-freed unit may be unavailable (failed, or capacity reduced by a
        // schedule), so re-check availability before serving the next customer.
        if (isResourceAvailable && isQueueNotEmpty) {
            serveNext()
        }
        sendToNextReceiver(leaving)
    }

    override fun initialize() {
        super.initialize()
        // clear per-replication service/preemption bookkeeping (events are cleared by the executive)
        myInService.clear()
        myPreempted.clear()
        myRenegeEvents.clear()
        myLastServedType = null
    }

}