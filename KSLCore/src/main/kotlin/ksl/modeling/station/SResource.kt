package ksl.modeling.station

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.modeling.entity.CapacitySchedule
import ksl.modeling.entity.CapacityChangeListenerIfc
import ksl.modeling.variable.*
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.GetValueIfc
import ksl.utilities.random.rvariable.RVariableIfc

/**
 *  Notified when a station resource gains available units (for example, after a
 *  capacity increase from a schedule), so that a waiting queue can be served.
 */
fun interface UnitsAvailableListenerIfc {
    fun unitsAvailable(resource: StationResourceIfc)
}

/**
 *  How an in-service unit is treated when a failure occurs:
 *  - [PREEMPT_RESUME]: interrupt the in-service work immediately; bank its
 *    remaining time and resume it (for that remaining time) after repair.
 *  - [FINISH_THEN_FAIL]: let in-service work complete first, then take the
 *    resource down for repair.
 */
enum class FailureEffect { PREEMPT_RESUME, FINISH_THEN_FAIL }

/**
 *  Notified when a resource fails so that a station can preempt its in-service
 *  work (cancel the in-service event, bank the remaining time, and free the
 *  unit). Resumption and queue service happen on repair via the
 *  [UnitsAvailableListenerIfc].
 */
fun interface ResourceFailureListenerIfc {
    fun resourceFailed(resource: StationResourceIfc)
}

/**
 *  The operational contract a station programs against for its resource. This is
 *  the seam that lets a station use either a simple [SResource] or, later, a
 *  resource pool, without depending on a concrete type. It adds the mutating
 *  operations (seize/release, capacity changes, schedule attachment, and an
 *  availability listener) to the read-only [SResourceCIfc].
 */
interface StationResourceIfc : SResourceCIfc {

    /** Seizes [amount] units. The caller must ensure availability first. */
    fun seize(amount: Int = 1)

    /** Releases [amount] units. */
    fun release(amount: Int = 1)

    /** Sets the current capacity to [value] (may be 0, e.g., off-shift). */
    fun changeCapacity(value: Int)

    /** Drives this resource's capacity from a [CapacitySchedule] (IGNORE-rule semantics). */
    fun useSchedule(schedule: CapacitySchedule)

    /** Registers a listener notified when available units increase. */
    fun attachUnitsAvailableListener(listener: UnitsAvailableListenerIfc)
}

interface SResourceCIfc {
    /**
     * The initial capacity of the resource at time just prior to 0.0
     */
    var initialCapacity: Int

    /**
     * The capacity of the resource at time any time t
     */
    val capacity: Int

    /**
     * Counts how many times the resource has units become busy
     */
    val numTimesSeized: Int

    /**
     * Counts how many times the resource has units become idle
     */
    val numTimesReleased: Int

    /**
     *  Response information on number of busy units
     */
    val numBusyUnits: TWResponseCIfc

    /**
     *  Response information on resource utilization
     */
    val utilization: TWResponseCIfc

    /**
     *  Time-weighted response of the resource's capacity (which may vary over time
     *  under a capacity schedule).
     */
    val capacityResponse: TWResponseCIfc

    /**
     *  Current number of available units
     */
    val numAvailableUnits: Int

    /**
     *  Indicates if resource has available units
     */
    val hasAvailableUnits: Boolean

    /** Checks if the resource is idle, has no units allocated
     */
    val isIdle: Boolean

    /** Checks to see if the resource is busy, has some units allocated
     */
    val isBusy: Boolean

    /** True if the resource is currently failed (down for repair). */
    val isFailed: Boolean

    /** Counts the number of failures (downtimes) experienced. */
    val numTimesFailed: CounterCIfc

    /**
     *  Time-weighted 0/1 indicator of the failed state; its average is the
     *  long-run fraction of time failed (unavailability due to failure).
     */
    val failedStateProportion: TWResponseCIfc

    /** Time-weighted 0/1 indicator of the idle state (on shift, not busy, not failed). */
    val idleStateProportion: TWResponseCIfc

    /** Time-weighted 0/1 indicator of the busy state (at least one unit working). */
    val busyStateProportion: TWResponseCIfc

    /** Time-weighted 0/1 indicator of the inactive state (off shift, capacity 0, not busy/failed). */
    val inactiveStateProportion: TWResponseCIfc
}

/**
 * A SResource represents a simple resource that can have units become busy. A
 * resource is considered busy when it has 1 or more units seized. A resource is
 * considered idle when all available units are idle. A resource has an initial
 * capacity, which represents the units that can be allocated.
 *
 * The capacity of the resource represents the maximum number of units available
 * for use. For example, if the resource has capacity 3, it may have 2 units busy
 * and 1 unit available. A resource cannot have more units busy than the capacity.
 *
 * @author rossetti
 */
class SResource(
    parent: ModelElement,
    capacity: Int = 1,
    name: String? = null
) : ModelElement(parent, name), StationResourceIfc {
    init {
        require(capacity >= 1) { "The initial capacity of the resource must be >= 1" }
    }

    private val myUnitsAvailableListeners = mutableListOf<UnitsAvailableListenerIfc>()

    override fun attachUnitsAvailableListener(listener: UnitsAvailableListenerIfc) {
        if (!myUnitsAvailableListeners.contains(listener)) {
            myUnitsAvailableListeners.add(listener)
        }
    }

    private fun notifyUnitsAvailable() {
        for (listener in myUnitsAvailableListeners) {
            listener.unitsAvailable(this)
        }
    }

    private val myResourceFailureListeners = mutableListOf<ResourceFailureListenerIfc>()

    /** Registers a listener notified when the resource fails (for preemption). */
    fun attachResourceFailureListener(listener: ResourceFailureListenerIfc) {
        if (!myResourceFailureListeners.contains(listener)) {
            myResourceFailureListeners.add(listener)
        }
    }

    private fun notifyResourceFailed() {
        for (listener in myResourceFailureListeners) {
            listener.resourceFailed(this)
        }
    }

    /**
     *  Frees [amount] busy units because their work was preempted (not completed).
     *  Unlike [release], this does not count as a completion (so it does not
     *  advance count-based failures) but it does update the operating clock.
     */
    internal fun preemptRelease(amount: Int = 1) {
        require(amount > 0) { "The preempt-release amount must be > 0" }
        require(amount <= myNumBusy.value.toInt()) { "Attempted to preempt-release more units than were busy" }
        myNumBusy.decrement(amount.toDouble())
        updateOperatingClock()
        updateStateIndicators()
    }

    /**
     *  The effect of a failure on in-service work. The default is preempt-resume.
     *  Must be set before the model runs.
     */
    var failureEffect: FailureEffect = FailureEffect.PREEMPT_RESUME
        set(value) {
            require(model.isNotRunning) { "The model must not be running when setting the failure effect." }
            field = value
        }

    private var myCapacitySchedule: CapacitySchedule? = null

    private val myCapacityChangeListener = object : CapacityChangeListenerIfc {
        override fun scheduleStarted(schedule: CapacitySchedule) {}
        override fun scheduleEnded(schedule: CapacitySchedule) {}
        override fun capacityChange(item: CapacitySchedule.CapacityItem) {
            changeCapacity(item.capacity)
        }
    }

    override fun useSchedule(schedule: CapacitySchedule) {
        require(model.isNotRunning) { "The model must not be running when assigning a capacity schedule." }
        myCapacitySchedule?.deleteCapacityChangeListener(myCapacityChangeListener)
        myCapacitySchedule = schedule
        schedule.addCapacityChangeListener(myCapacityChangeListener)
    }

    /**
     *  Sets the current capacity. An increase that creates newly available units
     *  notifies the availability listeners so a waiting queue can be served. A
     *  decrease takes effect as busy units are released (IGNORE-rule semantics):
     *  in-service units are not interrupted.
     */
    override fun changeCapacity(value: Int) {
        require(value >= 0) { "The capacity must be >= 0" }
        val increased = value > myCapacity
        myCapacity = value
        myCapacityTW.value = value.toDouble()
        if (increased) {
            notifyUnitsAvailable()
        }
        updateOperatingClock()
        updateStateIndicators()
    }

    // ---- failures (slice 2: calendar-time and count-based; finish-then-fail, full-down) ----

    private var myFailed = false
    private var myFailurePending = false
    private var myTimeToFailure: RVariableIfc? = null
    private var myTimeToRepair: RVariableIfc? = null
    private var myCountToFailure: GetValueIfc? = null
    private var myCompletionsSinceFailure = 0
    private var myNextFailureCount = Int.MAX_VALUE

    // operating-time (usage) clock state
    private var myOperatingTimeToFailure: RVariableIfc? = null
    private var myRemainingTTF = 0.0
    private var myAccruing = false
    private var myAccrualStart = 0.0
    private var myOperatingFailureEvent: KSLEvent<Nothing>? = null

    /**
     *  Configures time-based (calendar-clock) failures: the resource fails after
     *  [timeToFailure] elapses (wall-clock, whether busy or idle) and is down for
     *  [timeToRepair]. Failures are finish-then-fail (in-service units complete
     *  first) and take the whole resource down.
     */
    fun useTimeBasedFailures(timeToFailure: RVariableIfc, timeToRepair: RVariableIfc) {
        require(model.isNotRunning) { "The model must not be running when configuring failures." }
        myTimeToFailure = timeToFailure
        myTimeToRepair = timeToRepair
        myCountToFailure = null
        myOperatingTimeToFailure = null
    }

    /**
     *  Configures usage (count) based failures: the resource fails after
     *  [countToFailure] completed services and is down for [timeToRepair]. The
     *  count is re-sampled after each repair.
     */
    fun useCountBasedFailures(countToFailure: GetValueIfc, timeToRepair: RVariableIfc) {
        require(model.isNotRunning) { "The model must not be running when configuring failures." }
        myCountToFailure = countToFailure
        myTimeToRepair = timeToRepair
        myTimeToFailure = null
        myOperatingTimeToFailure = null
    }

    /**
     *  Configures operating-time (usage) based failures: the resource fails after
     *  [operatingTimeToFailure] units of *busy* time accumulate, and is down for
     *  [timeToRepair]. The operating clock advances only while the resource is busy
     *  and on shift; it pauses while idle, off-shift (capacity 0), or failed.
     */
    fun useOperatingTimeBasedFailures(operatingTimeToFailure: RVariableIfc, timeToRepair: RVariableIfc) {
        require(model.isNotRunning) { "The model must not be running when configuring failures." }
        myOperatingTimeToFailure = operatingTimeToFailure
        myTimeToRepair = timeToRepair
        myTimeToFailure = null
        myCountToFailure = null
    }

    /**
     *  Reschedules or pauses the operating-time failure event as the resource's
     *  "accruing" condition changes. Accruing means busy, on shift, and not failed
     *  or about to fail. Called after every state change that can flip that
     *  condition (seize, release, capacity change, fail, repair).
     */
    private fun updateOperatingClock() {
        if (myOperatingTimeToFailure == null) return
        val shouldAccrue = (myNumBusy.value > 0.0) && !myFailed && !myFailurePending && (myCapacity > 0)
        if (shouldAccrue && !myAccruing) {
            myAccruing = true
            myAccrualStart = time
            myOperatingFailureEvent = schedule(this::operatingFailureAction, myRemainingTTF)
        } else if (!shouldAccrue && myAccruing) {
            myAccruing = false
            val elapsed = time - myAccrualStart
            myRemainingTTF = (myRemainingTTF - elapsed).coerceAtLeast(0.0)
            myOperatingFailureEvent?.let { if (it.isScheduled) it.cancel = true }
            myOperatingFailureEvent = null
        }
    }

    private fun operatingFailureAction(event: KSLEvent<Nothing>) {
        // the accrued busy time has reached the sampled operating-time-to-failure
        myAccruing = false
        myRemainingTTF = 0.0
        myOperatingFailureEvent = null
        triggerFailure()
        updateOperatingClock()
    }

    private fun scheduleFailure() {
        val ttf = myTimeToFailure ?: return
        schedule(this::failureEventAction, ttf.value)
    }

    private fun failureEventAction(event: KSLEvent<Nothing>) {
        triggerFailure()
    }

    private fun triggerFailure() {
        if (myFailed || myFailurePending) return
        if (failureEffect == FailureEffect.FINISH_THEN_FAIL && myNumBusy.value > 0.0) {
            // finish-then-fail: stop starting new work; go down when the last unit frees
            myFailurePending = true
            updateOperatingClock()
            updateStateIndicators()
        } else {
            // preempt-resume (or idle): go down now
            enterFailed()
        }
    }

    private fun enterFailed() {
        myFailed = true
        myFailurePending = false
        myNumFailures.increment()
        myCompletionsSinceFailure = 0
        updateOperatingClock()
        updateStateIndicators()
        if (failureEffect == FailureEffect.PREEMPT_RESUME) {
            // station preempts its in-service work and frees the units
            notifyResourceFailed()
        }
        val ttr = myTimeToRepair!!.value
        schedule(this::repairEventAction, ttr)
    }

    private fun repairEventAction(event: KSLEvent<Nothing>) {
        myFailed = false
        updateStateIndicators()
        if (myCountToFailure != null) {
            myNextFailureCount = myCountToFailure!!.value.toInt().coerceAtLeast(1)
        }
        if (myOperatingTimeToFailure != null) {
            myRemainingTTF = myOperatingTimeToFailure!!.value
        }
        notifyUnitsAvailable()
        if (myTimeToFailure != null) {
            scheduleFailure()
        }
        updateOperatingClock()
    }

    private fun onCompletion() {
        if (myCountToFailure != null && !myFailed && !myFailurePending) {
            myCompletionsSinceFailure++
            if (myCompletionsSinceFailure >= myNextFailureCount) {
                triggerFailure()
            }
        }
        // a pending failure takes effect once the resource becomes idle
        if (myFailurePending && myNumBusy.value == 0.0) {
            myFailurePending = false
            enterFailed()
        }
    }

    /**
     * The initial capacity of the resource at time just prior to 0.0
     */
    @set:KSLControl(
        controlType = ControlType.INTEGER,
        lowerBound = 1.0
    )
    override var initialCapacity : Int = capacity
        set(value) {
            require(value >= 1) { "The initial capacity of the resource must be >= 1" }
            if (model.isRunning) {
                Model.logger.warn { "Changed the initial capacity of $name during replication ${model.currentReplicationNumber}." }
            }
            field = value
        }
    /**
     * The capacity of the resource at time any time t
     */
    private var myCapacity = initialCapacity

    override val capacity: Int
        get() = myCapacity

    override val numTimesSeized: Int
        get() = myNumTimesSeized

    override val numTimesReleased: Int
        get() = myNumTimesReleased

    /**
     * Counts how many times the resource has units become busy
     */
    private var myNumTimesSeized = 0

    /**
     * Counts how many times the resource has units become idle
     */
    private var myNumTimesReleased = 0

    private val mySeizeCounter = Counter(this, name = "${this.name}:SeizeCount")
    val seizeCounter: CounterCIfc
        get() = mySeizeCounter

    private val myNumBusy = TWResponse(this, "${this.name}:NumBusy")
    override val numBusyUnits: TWResponseCIfc
        get() = myNumBusy

    private val myCapacityTW = TWResponse(this, "${this.name}:Capacity", initialValue = initialCapacity.toDouble())
    override val capacityResponse: TWResponseCIfc
        get() = myCapacityTW

    private val myNumFailures = Counter(this, "${this.name}:NumFailures")
    override val numTimesFailed: CounterCIfc
        get() = myNumFailures

    private val myFailedState = TWResponse(this, "${this.name}:FailedState")
    override val failedStateProportion: TWResponseCIfc
        get() = myFailedState

    private val myIdleState = TWResponse(this, "${this.name}:IdleState", initialValue = 1.0)
    override val idleStateProportion: TWResponseCIfc
        get() = myIdleState

    private val myBusyState = TWResponse(this, "${this.name}:BusyState")
    override val busyStateProportion: TWResponseCIfc
        get() = myBusyState

    private val myInactiveState = TWResponse(this, "${this.name}:InactiveState")
    override val inactiveStateProportion: TWResponseCIfc
        get() = myInactiveState

    override val isFailed: Boolean
        get() = myFailed

    /**
     *  Recomputes the partition of the resource's state into exactly one of
     *  {failed, busy, inactive, idle}. Precedence: failed > busy > inactive > idle,
     *  so a unit finishing past shift end counts as busy, and inactive is only
     *  off-shift with nothing in service. Called after every change to the failed
     *  flag, the number busy, or the capacity.
     */
    private fun updateStateIndicators() {
        val failed = myFailed
        val busy = !failed && myNumBusy.value > 0.0
        val inactive = !failed && !busy && myCapacity == 0
        val idle = !failed && !busy && !inactive
        myFailedState.value = if (failed) 1.0 else 0.0
        myBusyState.value = if (busy) 1.0 else 0.0
        myInactiveState.value = if (inactive) 1.0 else 0.0
        myIdleState.value = if (idle) 1.0 else 0.0
    }

    private fun utilCapture(x: Double) : Double {
        // capacity can be 0 (off-shift); avoid division by zero
        return if (myCapacity <= 0) 0.0 else x / myCapacity
    }

    private val myUtil: TWResponseFunction = TWResponseFunction(this::utilCapture, myNumBusy, "${this.name}:Util")
    override val utilization: TWResponseCIfc
        get() = myUtil

    override val numAvailableUnits: Int
        get() = if (myFailed || myFailurePending) 0 else (capacity - myNumBusy.value.toInt()).coerceAtLeast(0)

    override val hasAvailableUnits: Boolean
        get() = numAvailableUnits > 0

    /** Checks if the resource is idle, has no units allocated
     */
    override val isIdle: Boolean
        get() = myNumBusy.value == 0.0

    /** Checks to see if the resource is busy, has some units allocated
     */
    override val isBusy: Boolean
        get() = myNumBusy.value > 0.0

    override fun initialize() {
        myCapacity = initialCapacity
        myCapacityTW.value = initialCapacity.toDouble()
        myNumTimesReleased = 0
        myNumTimesSeized = 0
        myFailed = false
        myFailurePending = false
        myCompletionsSinceFailure = 0
        myAccruing = false
        myAccrualStart = 0.0
        myOperatingFailureEvent = null
        updateStateIndicators()
        if (myCountToFailure != null) {
            myNextFailureCount = myCountToFailure!!.value.toInt().coerceAtLeast(1)
        }
        if (myOperatingTimeToFailure != null) {
            myRemainingTTF = myOperatingTimeToFailure!!.value
        }
        if (myTimeToFailure != null) {
            scheduleFailure()
        }
    }

    /**
     * Seizes amount units of the resource. If amt = 0, then an exception occurs. If
     * the resource has no units available, then an exception occurs. If the amt
     * is greater than the number available, then an exception occurs. Thus, users
     * must check for availability before calling this function.
     *
     * @param amount the amount to seize
     */
    override fun seize(amount: Int) {
        require(amount > 0) { "The seize amount must be > 0" }
        require(amount <= numAvailableUnits) { "Attempted to seize more than amount available ($numAvailableUnits) " }
        myNumBusy.increment(amount.toDouble())
        myNumTimesSeized++
        mySeizeCounter.increment()
        updateOperatingClock()
        updateStateIndicators()
    }

    /**
     *  Release the [amount] of the resource. The amount to release must be 1 or more and less than
     *  or equal to the current number of busy resource units.
     */
    override fun release(amount: Int) {
        require(amount > 0) { "The release amount must be > 0" }
        require(amount <= myNumBusy.value.toInt()) { "Attempted to release more units than were busy (${myNumBusy.value}" }
        myNumBusy.decrement(amount.toDouble())
        myNumTimesReleased++
        onCompletion()
        updateOperatingClock()
        updateStateIndicators()
        // Notify any waiting consumers (e.g., SeizeStations sharing this resource)
        // that units are newly available. SingleQStation's listener serves its own
        // queue here; it is also notified but is a safe no-op when its queue is empty.
        notifyUnitsAvailable()
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Resource: $name Capacity = $capacity \t Available = $numAvailableUnits \t Busy = ${myNumBusy.value}")
        return sb.toString()
    }
}