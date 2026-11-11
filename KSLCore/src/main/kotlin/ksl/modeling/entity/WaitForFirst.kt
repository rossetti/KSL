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

    protected abstract fun onReleaseOnTermination()

    /**
     *  Runs in the resumed process when this registration's trigger won: leaves the thing waited on
     *  and produces the value.
     */
    abstract suspend fun afterWinningResume(process: ProcessModel.Entity.ProcessCoroutine): AfterResume
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

    /** The timer's event, when the wait has a finite time limit. */
    var timerEvent: ksl.simulation.KSLEvent<WaitGroup>? = null

    /** Set by the wait when its process resumes normally, so its clean-up knows the exit was not abnormal. */
    var resumedNormally: Boolean = false

    /** The suspension of a `suspendForOrTimeout`; null for `waitForFirst`. */
    var suspension: ProcessModel.Entity.Suspension? = null
}
