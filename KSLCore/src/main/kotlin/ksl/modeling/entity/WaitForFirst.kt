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
 *  This file holds the wait engine itself. Each kind of trigger, with its registration, is kept beside
 *  its source: `SignalTrigger.kt`, `ItemsTrigger.kt` (blocking queues), `SeizeTriggers.kt` (resources
 *  and pools), `AllocationTriggers.kt`, and the message trigger in the agent package's `AgentMessaging.kt`.
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
     *  Checks that a wait by [entity] may use this trigger, throwing `IllegalArgumentException` if not. A wait
     *  checks every trigger before any can fire or register, so a wrong call fails where it is made, with nothing
     *  left behind. By default every trigger may be used.
     */
    internal open fun validate(entity: ProcessModel.Entity) {}

    /**
     *  True when whether this trigger can fire at once is known only once its request is queued, as for a seize
     *  whose queue has a request selection rule. The fast path of a wait stops at such a trigger, so that the
     *  triggers are still decided in argument order: it and those after it register, and it claims the wait as it
     *  registers when it can.
     */
    internal open val decidesWhenQueued: Boolean
        get() = false

    /**
     *  Ends a wait at once, taking the value. Called only after `canFireNow` returned true, within
     *  the same event. [suspensionName] is the wait's, which names an allocation the trigger makes.
     */
    internal abstract fun fireNow(process: ProcessModel.Entity.ProcessCoroutine, suspensionName: String?): T

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
}

/**
 *  Links one wait to one of its triggers while the wait is suspended: a waiter in a mailbox, an
 *  entity in a signal's hold queue, a request in a blocking queue. Both release functions do nothing
 *  when the registration has already been released.
 *
 *  **What a source must do to take part in waits.** A source keeps each registration with its own
 *  record of the waiter (the entity in its hold queue, its request, its mailbox waiter), and:
 *  - links the registration and its waiter both ways before the waiter is placed where model code can see it (a
 *    queue whose listeners run as it enters): that code can end the wait, and the claim then releases this
 *    registration with its waiter; after placing it, does nothing more for a registration already released
 *    (the seize, items and signal triggers do this; a mailbox runs no code as a waiter joins);
 *  - when that waiter is satisfied, ends the wait with [claimWait], never by resuming the entity itself;
 *  - takes a false answer from [claimWait] to mean the wait had already ended, and moves on to its next waiter;
 *  - claims nothing once the replication's events have ended, from `replicationEnded()` on: the claim is
 *    refused then (`WaitGroup.claim`), and a source that would hand something to its waiter checks first and
 *    keeps it (the blocking queue, the resource offers, an allocation's triggers, the agent mailbox and the
 *    process-view signal check this before they look at their waiters);
 *  - if removing a waiter makes it look at its other waiters, defers that look to the end of the claim
 *    ([deferredNotificationSource], [notifyDeferred]), as the blocking queue does;
 *  - in [onReleaseOnTermination], removes the waiter recording nothing and hands on anything it held.
 */
internal abstract class Registration(val trigger: WaitTrigger<*>, val group: WaitGroup) {

    init {
        // In the group from the start, before its trigger places anything: a request it places can let another
        // registration of the same wait claim it (a selection rule that serves only once a second request waits),
        // and the claim releases only the registrations the group holds. Left out, this one would stay behind, to be
        // woken later for a wait that has ended.
        group.registrations.add(this)
    }

    /**
     *  Ends this registration's wait because its trigger fired: the one way a source ends a wait. The
     *  wait's other registrations are released, its timer cancelled and its resume scheduled, as
     *  `WaitGroup.claim` describes.
     *
     *  @param value the trigger's value when the source produces it now (a mailbox's message); null when
     *  [afterWinningResume] produces it after the resume
     *  @param source why the entity resumes
     *  @param resumePriority the priority of the resume event
     *  @return true if this ended the wait; false if the wait had already been ended
     */
    fun claimWait(value: Any?, source: ResumeSource, resumePriority: Int): Boolean =
        group.claim(this, value, source, resumePriority)

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

    /**
     *  True while this registration is held: its wait has been claimed by another of its triggers, by the timer or by
     *  its termination, and it is not yet released, as while the claim releases the registrations one by one, or while
     *  a trigger ready at once fires after the wait was closed for it (`WaitGroup.claimAround`). It keeps its place
     *  with its source and records nothing, and its source passes over it, making the choice it would make if the
     *  registration had left, so that nothing a release sets off can serve it. A claim through it is refused.
     */
    val isHeld: Boolean
        get() = (group.isFiring || group.claimed) && !isReleased && this !== group.winnerRegistration

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
     *  and produces the value. It extends the process, so a step may call the process's suspending functions.
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
    /** One per registered trigger, in the order they registered; each adds itself as it is made. */
    val registrations: MutableList<Registration> = mutableListOf()

    /** For the invariant checks: the triggers whose wait has been recorded, with the queue it was recorded in. */
    private val recordedIn: MutableSet<Pair<WaitTrigger<*>, String>> = mutableSetOf()

    /**
     *  For the invariant checks, when on: notes that the wait for [trigger] was recorded in [queueName], and
     *  throws if it already was. Each trigger's wait is recorded once, however the wait goes.
     */
    fun noteRecorded(trigger: WaitTrigger<*>, queueName: String) {
        if (!ResourceInvariants.enabled || recordedIn.add(Pair(trigger, queueName))) return
        throw IllegalStateException(
            "Invariant broken at time ${entity.processModel.time}: the wait of entity ${entity.id} for $trigger, " +
                    "begun at $startTime, has its time recorded in $queueName a second time"
        )
    }

    /**
     *  The entity's priority when the wait began. A trigger that places a request for the entity ranks it by
     *  this, so that a trigger registered earlier in the same wait, which may change the entity's priority as
     *  a signal trigger with a wait priority does, has no effect on it.
     */
    var entityPriority: Int = entity.priority

    /** True once a trigger, the timer, or a suspension's holder has claimed the wait. */
    var claimed: Boolean = false
        private set

    /**
     *  True while a trigger ready at once fires after the wait was closed for it (`claimAround`): the wait is
     *  claimed, and its other registrations are held where they are until the firing is over.
     */
    var isFiring: Boolean = false
        private set

    /** Closes the wait without a winner, because its process ended: nothing can claim it afterwards. */
    fun closeOnTermination() {
        claimed = true
    }

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

    /** The priority of the wait's timer. */
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
     *  timer and a suspension's holder can all try at the same instant without coordinating. A source
     *  claims through its registration's `claimWait`; the timer and a suspension's holder call this.
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
     *  Once the replication's events have ended, nothing can be resumed, so nothing claims the wait: the claim
     *  is refused and the wait stays as it is, to be closed when its entity is terminated. A source that hands
     *  something to its waiter keeps it instead (a mailbox keeps its message).
     *
     *  @return true if this call claimed the wait; false if it had already been claimed, or the replication's
     *  events have ended
     */
    fun claim(by: Registration?, value: Any?, source: ResumeSource, resumePriority: Int): Boolean {
        if (claimed || entity.processModel.isRunEnded) return false
        claimed = true
        completeClaim(by, value, source, resumePriority)
        return true
    }

    /**
     *  Claims the wait for a trigger that can fire at once, met after a queued trigger has registered, and fires
     *  it: [fire] takes what the trigger won. The wait is closed first, with the other registrations held, so that
     *  nothing the firing sets off (a mailbox observer, an allocation listener) can claim it for a queued trigger;
     *  only after the firing are they released and the requests behind them looked at again, so that those looks
     *  cannot take what the trigger found free.
     *
     *  @return what [fire] returned, which is also the wait's value
     */
    fun <T> claimAround(source: ResumeSource, resumePriority: Int, fire: () -> T): T {
        check(!claimed && !entity.processModel.isRunEnded) { "A wait is claimed around a firing only while it is open." }
        claimed = true
        // held while the trigger fires: every source passes over a held registration
        isFiring = true
        val value = try {
            fire()
        } finally {
            isFiring = false
        }
        completeClaim(null, value, source, resumePriority)
        return value
    }

    /** The rest of a claim, once the wait is closed: see [claim]. */
    private fun completeClaim(by: Registration?, value: Any?, source: ResumeSource, resumePriority: Int) {
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
    }

    /** Cancels the timer if it is still on the calendar. */
    fun cancelTimer() {
        val event = timerEvent ?: return
        if (event.isScheduled) event.cancel = true
    }
}
