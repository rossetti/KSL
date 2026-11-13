/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.modeling.entity

import ksl.animation.AnimationEvent
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement

/**
 *  Describes something that can end a wait: a message arriving at a mailbox, a signal, items becoming
 *  available in a blocking queue. A trigger is obtained from the thing waited on (for example
 *  `signal.trigger()`), and is passed to `waitForFirst`, which waits until the first of its triggers
 *  fires or its time limit runs out.
 *
 *  A trigger is a description only. Creating one registers nothing, and one trigger may be used in
 *  any number of waits, one after another or by several entities at once, because everything a
 *  particular wait needs is kept by that wait. Two triggers are equal only when they are the same
 *  object, which is what a `when` on the result of `waitForFirst` compares.
 *
 *  @param T the type of the value the trigger produces when it ends a wait
 */
abstract class WaitTrigger<out T> internal constructor() {

    /**
     *  True if this trigger could end a wait by [entity] at once, without the entity suspending.
     *  Has no side effects.
     */
    internal abstract fun canFireNow(entity: ProcessModel.Entity): Boolean

    /**
     *  Ends a wait at once, taking the value. Called only after `canFireNow` returned true, within
     *  the same event.
     */
    internal abstract fun fireNow(process: ProcessModel.Entity.ProcessCoroutine): T

    /**
     *  Registers a wait that is about to suspend with the thing waited on, and returns the
     *  registration that links the two.
     */
    internal abstract fun register(group: WaitGroup): Registration

    /**
     *  If not null, the wait yields once at this priority before it registers, as a seize does
     *  before it joins its queue.
     */
    internal open val yieldPriority: Int? = null

    /**
     *  The state the entity takes, and the suspend type it reports, when this is the only trigger of
     *  a wait: the same as the untimed form of the wait, so code that reads the state sees no
     *  difference between the two.
     */
    internal open val aloneState: AloneState = AloneState.ACTIVE
}

/**
 *  The state of an entity in a wait with one trigger, as its untimed form reports it.
 *  @param suspendType the suspend type the wait reports
 */
internal enum class AloneState(val suspendType: SuspendType) {
    /** Active, as a plain suspension or a message receive. */
    ACTIVE(SuspendType.SUSPEND),

    /** Waiting for a signal. */
    WAITING_FOR_SIGNAL(SuspendType.WAIT_FOR_SIGNAL),

    /** Blocked receiving an amount of items from a blocking queue. */
    BLOCKED_RECEIVING_ITEMS(SuspendType.WAIT_FOR_ITEMS),

    /** Blocked receiving any matching items from a blocking queue. */
    BLOCKED_RECEIVING_ANY_ITEMS(SuspendType.WAIT_FOR_ANY_ITEMS),

    /** Waiting for a resource, as a seize. */
    WAITING_FOR_RESOURCE(SuspendType.SEIZE)
}

/**
 *  The result of a wait for one thing with a time limit: either the thing happened, and its value
 *  is available, or the time ran out first.
 *
 *  @param T the type of the value of the thing waited for
 */
sealed class WaitResult<out T> {

    /** The simulated time from the start of the wait to its end. */
    abstract val timeWaited: Double

    /** True if the time limit ran out before the thing waited for happened. */
    val timedOut: Boolean
        get() = this is TimedOut

    /**
     *  The value of the thing waited for.
     *  @throws IllegalStateException if the wait timed out; check `timedOut` first, or use `valueOrNull()`
     */
    val value: T
        get() = when (this) {
            is Satisfied -> satisfiedValue
            is TimedOut -> throw IllegalStateException(
                "The wait timed out after $timeWaited time units, so it has no value. Check timedOut before reading value, or use valueOrNull()."
            )
        }

    /** The value of the thing waited for, or null if the wait timed out. */
    fun valueOrNull(): T? = when (this) {
        is Satisfied -> satisfiedValue
        is TimedOut -> null
    }

    /**
     *  The thing waited for happened before the time limit.
     *  @param value the value it produced
     *  @param timeWaited the simulated time from the start of the wait to its end
     */
    class Satisfied<T>(value: T, override val timeWaited: Double) : WaitResult<T>() {
        internal val satisfiedValue: T = value

        override fun toString(): String = "Satisfied(value = $satisfiedValue, timeWaited = $timeWaited)"
    }

    /**
     *  The time limit ran out before the thing waited for happened.
     *  @param timeWaited the simulated time from the start of the wait to its end, which is the time limit
     */
    class TimedOut(override val timeWaited: Double) : WaitResult<Nothing>() {
        override fun toString(): String = "TimedOut(timeWaited = $timeWaited)"
    }
}

/**
 *  The result of `waitForFirst`: which of its triggers ended the wait, and that trigger's value, or
 *  that the time limit ran out first.
 */
class WaitForFirstResult internal constructor(
    private val myTriggers: List<WaitTrigger<*>>,
    /** The trigger that ended the wait, or null if the time limit ran out first. */
    val trigger: WaitTrigger<*>?,
    private val myValue: Any?,
    /** The simulated time from the start of the wait to its end. */
    val timeWaited: Double
) {

    /** True if the time limit ran out before any trigger fired. */
    val timedOut: Boolean
        get() = trigger == null

    /** True if the wait was ended by this trigger. */
    fun firedBy(trigger: WaitTrigger<*>): Boolean = this.trigger === trigger

    /**
     *  The value of the trigger that ended the wait.
     *  @throws IllegalArgumentException if the trigger was not passed to this wait
     *  @throws IllegalStateException if the trigger did not end the wait, or the wait timed out
     */
    fun <T> valueOf(trigger: WaitTrigger<T>): T {
        requireKnown(trigger)
        check(this.trigger === trigger) {
            if (timedOut) "The wait timed out after $timeWaited time units, so no trigger has a value. Check timedOut first, or use valueOrNull()."
            else "The trigger $trigger did not end this wait, so it has no value. Check firedBy first, or use valueOrNull()."
        }
        @Suppress("UNCHECKED_CAST")
        return myValue as T
    }

    /**
     *  The value of the trigger if it ended the wait; otherwise null.
     *  @throws IllegalArgumentException if the trigger was not passed to this wait
     */
    fun <T> valueOrNull(trigger: WaitTrigger<T>): T? {
        requireKnown(trigger)
        if (this.trigger !== trigger) return null
        @Suppress("UNCHECKED_CAST")
        return myValue as T
    }

    private fun requireKnown(trigger: WaitTrigger<*>) {
        require(myTriggers.any { it === trigger }) { "The trigger $trigger was not one of the triggers passed to this wait." }
    }

    override fun toString(): String =
        if (timedOut) "WaitForFirstResult(timed out, timeWaited = $timeWaited)"
        else "WaitForFirstResult(trigger = $trigger, value = $myValue, timeWaited = $timeWaited)"
}

/**
 *  What the winning registration found when its entity resumed: the value, or that the wait must be
 *  reported as timed out after all.
 */
internal sealed class AfterResume {
    class Value(val value: Any?) : AfterResume()
    object TimedOut : AfterResume()
}

/**
 *  Links one wait to one of its triggers while the wait is suspended: a waiter in a mailbox, an
 *  entity in a signal's hold queue, a request in a blocking queue. Both release functions do nothing
 *  when the registration has already been released.
 */
internal abstract class Registration(val trigger: WaitTrigger<*>, val group: WaitGroup) {

    /** True once the registration no longer holds anything in the thing waited on. */
    var isReleased: Boolean = false
        private set

    /** Releases a registration whose trigger lost, recording what its source records for a wait that ends. */
    fun releaseAsLoser() {
        if (isReleased) return
        isReleased = true
        onReleaseAsLoser()
    }

    /** Releases a registration because its process ended at the wait, recording nothing. */
    fun releaseOnTermination() {
        if (isReleased) return
        isReleased = true
        onReleaseOnTermination()
    }

    /** Marks the registration released by its own winning path. */
    protected fun markReleased() {
        isReleased = true
    }

    protected abstract fun onReleaseAsLoser()

    /**
     *  The source whose waiting requests must be looked at again after this registration was
     *  released as a loser, or null if there is none. A blocking queue defers that look until the
     *  claim has finished, so a nested claim never sees this wait half-released.
     */
    open val deferredNotificationSource: Any? get() = null

    /** Looks again at the source's waiting requests; called once per source after the claim. */
    open fun notifyDeferred() {}

    protected abstract fun onReleaseOnTermination()

    /**
     *  Runs in the resumed process when this registration's trigger won: leaves the thing waited on
     *  and produces the value. It extends the process because a step that suspends again (a seize's
     *  re-wait) may call the process's suspending functions only from within the process.
     */
    abstract suspend fun ProcessModel.Entity.ProcessCoroutine.afterWinningResume(): AfterResume
}

/**
 *  The state of one suspending `waitForFirst` or `suspendForOrTimeout`: the entity and its process,
 *  the triggers in argument order and their registrations, whether a claim has been made and by
 *  whom, and the timer. The first claim wins; nothing outside the wait keeps a reference to the
 *  group once its registrations are released.
 */
internal class WaitGroup(
    val entity: ProcessModel.Entity,
    val processId: Int,
    val triggers: List<WaitTrigger<*>>,
    val startTime: Double,
    val suspensionName: String?
) {
    /** One per trigger, in argument order. */
    val registrations: MutableList<Registration> = mutableListOf()

    /** True once a trigger, the timer, or a suspension's holder has claimed the wait. */
    var claimed: Boolean = false
        private set

    /** The trigger that claimed the wait; null when the timer or a suspension's holder did. */
    var winner: WaitTrigger<*>? = null
        private set

    /** The registration of the trigger that claimed the wait. */
    var winnerRegistration: Registration? = null
        private set

    /** The value given by the claim, if the claim carried one. */
    var value: Any? = null
        private set

    /** True when the timer claimed the wait. */
    var timedOut: Boolean = false
        private set

    /** When the wait's time limit runs out; infinite when it has none. */
    var deadline: Double = Double.POSITIVE_INFINITY

    /** The priority of the wait's timer, kept for a re-wait that needs a timer of its own. */
    var timeoutPriority: Int = ProcessModel.TIMEOUT_PRIORITY

    /** The timer's event, when the wait has a finite time limit. */
    var timerEvent: KSLEvent<WaitGroup>? = null

    /**
     *  Set by the wait just before it suspends. A claim made earlier, while the wait is still
     *  registering, schedules no resume, because the wait then goes on without suspending.
     */
    var isSuspending: Boolean = false

    /** Set by the wait when its process resumes normally, so its clean-up knows the exit was not abnormal. */
    var resumedNormally: Boolean = false

    /** The suspension of a `suspendForOrTimeout`; null for `waitForFirst`. */
    var suspension: ProcessModel.Entity.Suspension? = null

    /**
     *  Claims the wait. The first claim wins and every later one does nothing, so a trigger, the
     *  timer and a suspension's holder can all try at the same instant without coordinating.
     *
     *  On a successful claim, in one step: the winner and its value are recorded; every other
     *  registration is released, so a second occurrence at this instant goes elsewhere rather than
     *  into a wait that has already ended; the timer is cancelled if something else won, or the
     *  timeout is announced if the timer won; and, if the wait has suspended, the entity is scheduled
     *  to resume, so the stale-resume check applies to it like any other resume. A claim made while
     *  the wait is still registering schedules nothing: the wait sees the claim and goes on at once.
     *
     *  @param by the registration whose trigger fired; null for the timer and for a suspension's holder
     *  @param value the value the trigger produced, when it produces it at the claim
     *  @param source why the entity resumes
     *  @param resumePriority the priority of the resume event
     *  @return true if this call claimed the wait; false if it had already been claimed
     */
    fun claim(by: Registration?, value: Any?, source: ResumeSource, resumePriority: Int): Boolean {
        if (claimed) return false
        claimed = true
        winner = by?.trigger
        winnerRegistration = by
        this.value = value
        timedOut = source == ResumeSource.TIMEOUT
        for (registration in registrations) {
            if (registration !== by) registration.releaseAsLoser()
        }
        if (timedOut) {
            suspension?.endLinkOnTimeout()
            entity.emitAnimation { AnimationEvent.WaitTimedOut(entity.processModel.time, entity.id, suspensionName) }
        } else {
            cancelTimer()
        }
        val deferred = registrations.filter { it !== by && it.deferredNotificationSource != null }
            .distinctBy { it.deferredNotificationSource }
        if (isSuspending) {
            val what = when {
                by != null -> "trigger=${by.trigger}"
                suspension != null -> "suspension=${suspension?.name}"
                else -> "timer"
            }
            entity.scheduleResumeProcess(0.0, resumePriority, source, "wait claimed by $what")
        }
        // Last: the requests behind a removed one may now be fillable. Doing this after everything
        // else means a nested claim of another wait never sees this one half-released.
        for (registration in deferred) {
            registration.notifyDeferred()
        }
        return true
    }

    /** Cancels the timer if it is still on the calendar. */
    fun cancelTimer() {
        val event = timerEvent ?: return
        if (event.isScheduled) event.cancel = true
    }
}

/**
 *  The trigger of a signal, made by `Signal.trigger`. It never fires at once: a signal is an
 *  event, so the wait registers by joining the signal's hold queue, and a later signal to the
 *  entity claims the wait.
 */
internal class SignalTrigger(
    val signal: Signal,
    val waitPriority: Int?,
    val waitStats: Boolean
) : WaitTrigger<Unit>() {

    override fun canFireNow(entity: ProcessModel.Entity): Boolean = false

    override fun fireNow(process: ProcessModel.Entity.ProcessCoroutine) {
        error("A signal trigger cannot fire without a wait; canFireNow is always false.")
    }

    override fun register(group: WaitGroup): Registration {
        val entity = group.entity
        val registration = SignalRegistration(this, group)
        signal.holdTimed(entity, waitPriority ?: entity.priority, registration)
        entity.emitAnimation { AnimationEvent.WaitingForSignal(signal.time, entity.id, signal.name) }
        return registration
    }

    override val aloneState: AloneState
        get() = AloneState.WAITING_FOR_SIGNAL

    override fun toString(): String = "SignalTrigger(${signal.name})"
}

/**
 *  A signal trigger's place in one wait: the entity in the signal's hold queue. A loser leaves
 *  the queue at the claim, recording its wait if the trigger records statistics; the winner leaves
 *  it after its resume, as an untimed wait for the signal does; termination records nothing.
 */
internal class SignalRegistration(
    private val signalTrigger: SignalTrigger,
    group: WaitGroup
) : Registration(signalTrigger, group) {

    override fun onReleaseAsLoser() {
        signalTrigger.signal.releaseTimed(group.entity, signalTrigger.waitStats)
    }

    override fun onReleaseOnTermination() {
        signalTrigger.signal.releaseTimed(group.entity, false)
    }

    override suspend fun ProcessModel.Entity.ProcessCoroutine.afterWinningResume(): AfterResume {
        markReleased()
        val signal = signalTrigger.signal
        val entity = group.entity
        signal.releaseTimed(entity, signalTrigger.waitStats)
        entity.emitAnimation { AnimationEvent.SignalReceived(signal.time, entity.id, signal.name) }
        return AfterResume.Value(Unit)
    }
}

/**
 *  The trigger of items from a blocking queue, made by `BlockingQueue.itemsTrigger` (an amount of
 *  matching items) or `BlockingQueue.anyItemsTrigger` (every matching item, at least one). It can
 *  fire at once when no request is waiting ahead of it and the channel holds what it needs, which
 *  is when the untimed receive fills at once; otherwise it places a request in the queue's request
 *  queue, which the queue claims when it selects the request.
 *
 *  @param amount the number of items wanted, or null for every matching item
 */
internal class ItemsTrigger<T : ModelElement.QObject>(
    val blockingQ: BlockingQueue<T>,
    val amount: Int?,
    val predicate: (T) -> Boolean,
    val blockingPriority: Int?
) : WaitTrigger<List<T>>() {

    init {
        if (amount != null) {
            require(amount >= 1) { "The amount of items requested must be >= 1; it was $amount." }
        }
    }

    override fun canFireNow(entity: ProcessModel.Entity): Boolean =
        blockingQ.requestQ.isEmpty && blockingQ.countByChannel(predicate) >= (amount ?: 1)

    override fun fireNow(process: ProcessModel.Entity.ProcessCoroutine): List<T> {
        // As the untimed receive does when it can be filled at once: the request enters the queue and
        // is filled in the same instant, which records a wait of zero.
        return fill(blockingQ, newRequest(process.entity, null))
    }

    override fun register(group: WaitGroup): Registration {
        val registration = ItemsRegistration(this, group)
        val request = newRequest(group.entity, registration)
        registration.request = request
        if (blockingQ.selectNextRequest() === request) {
            // It can be filled now, ahead of requests already waiting: as the untimed receive, it
            // does not suspend. The wait is claimed while it registers, so no resume is scheduled.
            blockingQ.claimSelected(request, registration)
        }
        return registration
    }

    private fun newRequest(entity: ProcessModel.Entity, registration: Registration?): BlockingQueue<T>.ChannelRequest {
        val priority = blockingPriority ?: entity.priority
        val request = if (amount != null) blockingQ.enqueueAmountRequest(entity, predicate, amount, priority, registration)
        else blockingQ.enqueueChannelRequest(entity, predicate, priority, registration)
        return request
    }

    override val aloneState: AloneState
        get() = if (amount != null) AloneState.BLOCKED_RECEIVING_ITEMS else AloneState.BLOCKED_RECEIVING_ANY_ITEMS

    override fun toString(): String =
        if (amount != null) "ItemsTrigger(${blockingQ.name}, amount = $amount)" else "AnyItemsTrigger(${blockingQ.name})"
}

/**
 *  An items trigger's request in one wait. The winner takes its items after its resume, exactly as
 *  the untimed receive takes them; a loser leaves the request queue at the claim, its wait recorded
 *  as the queue records any request's wait, and the queue looks at the requests behind it once the
 *  claim is over; termination removes the request with nothing recorded.
 */
internal class ItemsRegistration<T : ModelElement.QObject>(
    private val itemsTrigger: ItemsTrigger<T>,
    group: WaitGroup
) : Registration(itemsTrigger, group) {

    lateinit var request: BlockingQueue<T>.ChannelRequest

    override fun onReleaseAsLoser() {
        itemsTrigger.blockingQ.removeRequestDeferringNotification(request)
    }

    override fun onReleaseOnTermination() {
        itemsTrigger.blockingQ.removeRequest(request)
    }

    override val deferredNotificationSource: Any
        get() = itemsTrigger.blockingQ

    override fun notifyDeferred() {
        itemsTrigger.blockingQ.notifyAfterDeferredRemoval()
    }

    override suspend fun ProcessModel.Entity.ProcessCoroutine.afterWinningResume(): AfterResume {
        markReleased()
        return AfterResume.Value(fill(itemsTrigger.blockingQ, request))
    }
}

/** Takes a request's items, as the untimed receive of that kind takes them. */
internal fun <T : ModelElement.QObject> fill(blockingQ: BlockingQueue<T>, request: BlockingQueue<T>.ChannelRequest): List<T> =
    if (request is BlockingQueue<T>.AmountRequest) blockingQ.fill(request) else blockingQ.fill(request)

/**
 *  The trigger of a seize: units of a resource, or of a resource pool, or a movable resource from a
 *  movable resource pool. It fires at once when the untimed `seize` would take the units without
 *  waiting. Otherwise its request waits in the queue exactly as a `seize` request does, and the claim
 *  is made when the request is woken. The units are allocated after the resume, as `seize` allocates
 *  them.
 *
 *  @param V the allocation the seize produces
 *  @param R the kind of request it places
 */
internal abstract class SeizeTrigger<V, R : ProcessModel.Entity.Request>(
    val queue: RequestQ,
    val seizePriority: Int,
    val recordRenegedWait: Boolean
) : WaitTrigger<V>() {

    /** Places the request in the queue, as the untimed seize does. */
    abstract fun enqueue(entity: ProcessModel.Entity): R

    /** True when the request just placed can take its units at once, as the untimed seize decides. */
    abstract fun canTakeNow(entity: ProcessModel.Entity, request: R): Boolean

    /** Takes the units after any wait; null when the re-wait timed out. */
    abstract suspend fun ProcessModel.Entity.ProcessCoroutine.take(
        request: R,
        suspensionName: String?,
        rewait: (suspend ProcessModel.Entity.ProcessCoroutine.() -> Boolean)?
    ): V?

    /** Takes the units of a request that can be served now, without waiting. */
    abstract fun ProcessModel.Entity.ProcessCoroutine.complete(request: R): V

    /** The name of what is seized, for the animation. */
    abstract val seizedName: String

    override val yieldPriority: Int
        get() = seizePriority

    override val aloneState: AloneState
        get() = AloneState.WAITING_FOR_RESOURCE

    override fun fireNow(process: ProcessModel.Entity.ProcessCoroutine): V {
        // As seize does when it can take the units at once: the request enters the queue and leaves it in the
        // same instant, which records a wait of zero.
        val request = enqueue(process.entity)
        return with(process) { complete(request) }
    }

    override fun register(group: WaitGroup): Registration {
        val entity = group.entity
        val registration = SeizeRegistration(this, group)
        val request = enqueue(entity)
        registration.attach(request)
        if (canTakeNow(entity, request)) {
            // It can take its units now. They are promised to it, as a wake promises them, so nothing else takes
            // them before the wait goes on to allocate; the claim is made while the wait registers, so it
            // schedules no resume.
            check(queue.promiseUnits(request, request.resource)) { "The units of $seizedName could not be promised to request ${request.id}." }
            queue.resumeWoken(request, ProcessModel.RESUME_PRIORITY, ResumeSource.UNKNOWN_INTERNAL, "seize trigger served at once")
        } else {
            entity.emitAnimation { AnimationEvent.SeizeWaiting(queue.time, entity.id, seizedName) }
        }
        return registration
    }
}

/** The trigger of units of a resource, made by `Resource.seizeTrigger`. */
internal class ResourceSeizeTrigger(
    val resource: Resource,
    val amountNeeded: Int,
    seizePriority: Int,
    queue: RequestQ,
    recordRenegedWait: Boolean
) : SeizeTrigger<Allocation, ProcessModel.Entity.ResourceRequest>(queue, seizePriority, recordRenegedWait) {

    init {
        require(amountNeeded >= 1) { "The amount to seize must be >= 1; it was $amountNeeded." }
        if (resource is ksl.modeling.spatial.MovableResource) {
            require(amountNeeded == 1) {
                "A movable resource is seized one unit at a time; the seize trigger of ${resource.name} asked for $amountNeeded."
            }
        }
    }

    override val seizedName: String get() = resource.name

    override fun canFireNow(entity: ProcessModel.Entity): Boolean =
        resource.canAllocate(amountNeeded) && queue.nextRequestForResource(resource) == null

    override fun enqueue(entity: ProcessModel.Entity) = entity.enqueueResourceRequest(resource, amountNeeded, queue)

    override fun canTakeNow(entity: ProcessModel.Entity, request: ProcessModel.Entity.ResourceRequest) =
        entity.canTakeResourceNow(request, queue)

    override suspend fun ProcessModel.Entity.ProcessCoroutine.take(
        request: ProcessModel.Entity.ResourceRequest,
        suspensionName: String?,
        rewait: (suspend ProcessModel.Entity.ProcessCoroutine.() -> Boolean)?
    ): Allocation? = takeResource(request, resource, queue, suspensionName, rewait)

    override fun ProcessModel.Entity.ProcessCoroutine.complete(request: ProcessModel.Entity.ResourceRequest): Allocation =
        completeResourceSeize(request, resource, queue, null)

    override fun toString(): String = "SeizeTrigger(${resource.name}, amount = $amountNeeded)"
}

/** The trigger of units of a resource pool, made by `ResourcePool.seizeTrigger`. */
internal class ResourcePoolSeizeTrigger(
    val resourcePool: ResourcePool,
    val amountNeeded: Int,
    seizePriority: Int,
    queue: RequestQ,
    val resourceSelectionRule: ResourceSelectionRuleIfc,
    val resourceAllocationRule: ResourceAllocationRuleIfc,
    recordRenegedWait: Boolean
) : SeizeTrigger<ResourcePoolAllocation, ProcessModel.Entity.ResourcePoolRequest>(queue, seizePriority, recordRenegedWait) {

    init {
        require(amountNeeded >= 1) { "The amount to seize must be >= 1; it was $amountNeeded." }
    }

    override val seizedName: String get() = resourcePool.name

    // as the untimed pool seize decides: the pool can allocate, whatever else waits
    override fun canFireNow(entity: ProcessModel.Entity): Boolean =
        resourcePool.canAllocate(resourceSelectionRule, amountNeeded)

    override fun enqueue(entity: ProcessModel.Entity) =
        entity.enqueuePoolRequest(resourcePool, amountNeeded, queue, resourceSelectionRule, resourceAllocationRule)

    override fun canTakeNow(entity: ProcessModel.Entity, request: ProcessModel.Entity.ResourcePoolRequest) =
        resourcePool.canAllocate(resourceSelectionRule, amountNeeded)

    override suspend fun ProcessModel.Entity.ProcessCoroutine.take(
        request: ProcessModel.Entity.ResourcePoolRequest,
        suspensionName: String?,
        rewait: (suspend ProcessModel.Entity.ProcessCoroutine.() -> Boolean)?
    ): ResourcePoolAllocation? = takePool(request, resourcePool, queue, suspensionName, rewait)

    override fun ProcessModel.Entity.ProcessCoroutine.complete(request: ProcessModel.Entity.ResourcePoolRequest): ResourcePoolAllocation =
        completePoolSeize(request, resourcePool, queue, null)

    override fun toString(): String = "SeizeTrigger(${resourcePool.name}, amount = $amountNeeded)"
}

/**
 *  The trigger of a movable resource from a movable resource pool, made by
 *  `MovableResourcePool.seizeTrigger`. A null request location means the entity's location when the
 *  wait starts.
 */
internal class MovableResourcePoolSeizeTrigger(
    val movableResourcePool: ksl.modeling.spatial.MovableResourcePool,
    queue: RequestQ,
    val requestLocation: ksl.modeling.spatial.LocationIfc?,
    seizePriority: Int,
    val resourceSelectionRule: ksl.modeling.spatial.MovableResourceSelectionRuleIfc,
    val resourceAllocationRule: ksl.modeling.spatial.MovableResourceAllocationRuleIfc,
    recordRenegedWait: Boolean
) : SeizeTrigger<Allocation, ProcessModel.Entity.MovableResourcePoolRequest>(queue, seizePriority, recordRenegedWait) {

    override val seizedName: String get() = movableResourcePool.name

    // as the untimed movable pool seize decides: the pool can allocate, whatever else waits
    override fun canFireNow(entity: ProcessModel.Entity): Boolean = movableResourcePool.canAllocate(resourceSelectionRule)

    override fun enqueue(entity: ProcessModel.Entity) = entity.enqueueMovablePoolRequest(
        movableResourcePool, queue, requestLocation ?: entity.currentLocation, resourceSelectionRule, resourceAllocationRule
    )

    override fun canTakeNow(entity: ProcessModel.Entity, request: ProcessModel.Entity.MovableResourcePoolRequest) =
        movableResourcePool.canAllocate(resourceSelectionRule)

    override suspend fun ProcessModel.Entity.ProcessCoroutine.take(
        request: ProcessModel.Entity.MovableResourcePoolRequest,
        suspensionName: String?,
        rewait: (suspend ProcessModel.Entity.ProcessCoroutine.() -> Boolean)?
    ): Allocation? = takeMovablePool(request, movableResourcePool, queue, suspensionName, rewait)

    override fun ProcessModel.Entity.ProcessCoroutine.complete(request: ProcessModel.Entity.MovableResourcePoolRequest): Allocation =
        completeMovablePoolSeize(request, movableResourcePool, queue, null)

    override fun toString(): String = "SeizeTrigger(${movableResourcePool.name})"
}

/**
 *  A seize trigger's request in one wait. The winner takes its units after the resume, as `seize`
 *  does; if what it was promised went away in the instant between its wake and its resume, it waits
 *  again in a wait of its own with the time left. A loser leaves the queue at the claim, its wait
 *  recorded unless the trigger says not to, and the free units of what it asked for are offered on
 *  once the claim is over; termination removes the request as termination removes any waiting request.
 */
internal class SeizeRegistration<V, R : ProcessModel.Entity.Request>(
    private val seizeTrigger: SeizeTrigger<V, R>,
    group: WaitGroup
) : Registration(seizeTrigger, group) {

    lateinit var request: R
        private set

    /** The queue the request left as a loser, through which the free units are offered on. */
    private var leftQueue: RequestQ? = null

    fun attach(request: R) {
        this.request = request
        request.registration = this
    }

    override fun onReleaseAsLoser() {
        val queue = request.queue as? RequestQ ?: return
        leftQueue = queue
        queue.removeLeaving(request, seizeTrigger.recordRenegedWait)
    }

    override fun onReleaseOnTermination() {
        (request.queue as? RequestQ)?.removeAbandoned(request)
    }

    override val deferredNotificationSource: Any?
        get() = if (leftQueue != null) request.resource else null

    override fun notifyDeferred() {
        leftQueue?.offerFreeUnitsOf(request.resource, "request_id=${request.id} left without service")
    }

    override suspend fun ProcessModel.Entity.ProcessCoroutine.afterWinningResume(): AfterResume {
        markReleased()
        val value = with(seizeTrigger) { take(request, group.suspensionName, rewait = { rewaitAlone() }) }
        return if (value == null) AfterResume.TimedOut else AfterResume.Value(value)
    }

    /** Waits again, alone, for the time left to the original deadline, with the same request. */
    private suspend fun ProcessModel.Entity.ProcessCoroutine.rewaitAlone(): Boolean {
        val again = WaitGroup(group.entity, group.processId, listOf(seizeTrigger), entity.processModel.time, group.suspensionName)
        again.deadline = group.deadline
        again.timeoutPriority = group.timeoutPriority
        val registration = SeizeRegistration(seizeTrigger, again)
        registration.attach(request)
        again.registrations.add(registration)
        return waitAlone(again, AloneState.WAITING_FOR_RESOURCE)
    }
}

/**
 *  Fires when units of [allocation] go missing, to a failure or a preemption, with the cause as its value.
 *  It fires at once when units are missing already. Made by `Allocation.interruptedTrigger`.
 */
internal class InterruptedTrigger(val allocation: Allocation) : WaitTrigger<InterruptCause>() {

    override fun canFireNow(entity: ProcessModel.Entity): Boolean = allocation.isAllocated && allocation.hasMissingUnits

    override fun fireNow(process: ProcessModel.Entity.ProcessCoroutine): InterruptCause = allocation.currentCause

    override fun register(group: WaitGroup): Registration = allocation.registerTrigger(this, group, onRestore = false)

    override fun toString(): String = "InterruptedTrigger(allocation ${allocation.id} of ${allocation.myResource.name})"
}

/**
 *  Fires when the missing units of [allocation] have all come back. It fires at once when none are missing.
 *  Made by `Allocation.restoredTrigger`.
 */
internal class RestoredTrigger(val allocation: Allocation) : WaitTrigger<Unit>() {

    override fun canFireNow(entity: ProcessModel.Entity): Boolean = allocation.isAllocated && !allocation.hasMissingUnits

    override fun fireNow(process: ProcessModel.Entity.ProcessCoroutine) {}

    override fun register(group: WaitGroup): Registration = allocation.registerTrigger(this, group, onRestore = true)

    override fun toString(): String = "RestoredTrigger(allocation ${allocation.id} of ${allocation.myResource.name})"
}

/**
 *  A wait on one of an allocation's triggers. It waits in the allocation's list, which claims it when its
 *  units go missing ([onRestore] false) or all come back ([onRestore] true); a loser or a terminated wait
 *  leaves the list. A released allocation never claims it.
 */
internal class AllocationRegistration(
    trigger: WaitTrigger<*>,
    group: WaitGroup,
    val allocation: Allocation,
    val onRestore: Boolean
) : Registration(trigger, group) {

    override fun onReleaseAsLoser() {
        allocation.myTriggerRegistrations.remove(this)
    }

    override fun onReleaseOnTermination() {
        allocation.myTriggerRegistrations.remove(this)
    }

    override suspend fun ProcessModel.Entity.ProcessCoroutine.afterWinningResume(): AfterResume {
        // the allocation removed this registration from its list when it claimed the wait
        markReleased()
        return AfterResume.Value(group.value)
    }
}
