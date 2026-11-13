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

/** Why units of an allocation went missing. */
enum class InterruptCause {
    /** The resource failed. */
    FAILURE,

    /** A request of higher priority took the units. */
    PREEMPTION
}

/**
 *  What `delayOrInterrupt` returns: the delay either completed, or ended early because units it depends on
 *  went missing.
 */
sealed class DelayResult {

    /** The duration of the delay as sampled when it began. */
    abstract val duration: Double

    /** The simulated time from the start of the delay to its end, including any time it was held by a process interrupt. */
    abstract val timeElapsed: Double

    /** The delay ran to its end. */
    class Completed internal constructor(
        override val duration: Double,
        override val timeElapsed: Double
    ) : DelayResult() {
        override fun toString(): String = "Completed(duration=$duration, timeElapsed=$timeElapsed)"
    }

    /**
     *  The delay ended early, at the moment units of [by] went missing. The units are still missing: the
     *  process decides what to do, for example wait for them with `by.restoredTrigger()`, release the
     *  allocation, or carry on with what it holds.
     *
     *  @property timeRemaining the time that was left on the delay when it ended. After a process interrupt
     *  with its own post time, or one that restarted the delay, it can exceed the duration less the time run.
     *  @property by the allocation whose units went missing first
     *  @property cause why they went missing
     *  @property rule the rule of the failure or preemption that took them, `RESUME` or `RESTART`; it tells a
     *  process that wants to imitate it whether to go on with [timeRemaining] or start again with the duration
     */
    class Interrupted internal constructor(
        override val duration: Double,
        override val timeElapsed: Double,
        val timeRemaining: Double,
        val by: Allocation,
        val cause: InterruptCause,
        val rule: InterruptRule
    ) : DelayResult() {

        /** The work done before the delay ended: the duration less the time remaining, never negative. */
        val timeDone: Double
            get() = maxOf(0.0, duration - timeRemaining)

        override fun toString(): String =
            "Interrupted(duration=$duration, timeElapsed=$timeElapsed, timeRemaining=$timeRemaining, " +
                    "by=${by.id} of ${by.myResource.name}, cause=$cause, rule=$rule)"
    }
}
