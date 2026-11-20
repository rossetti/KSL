/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.modeling.agent

import ksl.animation.AnimationEvent
import ksl.modeling.entity.AfterResume
import ksl.modeling.entity.AloneState
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.Registration
import ksl.modeling.entity.ResumeSource
import ksl.modeling.entity.WaitGroup
import ksl.modeling.entity.WaitTrigger

/**
 *  A broadcast trigger that statecharts can listen for. Unlike
 *  [AgentMessage] (point-to-point) and `onCondition` (a predicate
 *  tested in the C-phase), a signal is an explicit notification fired
 *  by user code — typically at the end of a transition, a phase
 *  change, or an externally-driven schedule event.
 *
 *  A signal is a POJO: not a `ModelElement`, no automatic lifecycle.
 *  Create one wherever it's convenient (typically as a property of
 *  the `AgentModel`), share it across as many states and statecharts
 *  as needed, and call [fire] when the event occurs.
 *
 *  Subscriptions are managed automatically by the statechart runtime:
 *  states with `onSignal(s) { ... }` subscribe when entered and
 *  unsubscribe on exit (including via `stop()` at end-of-replication).
 *  Users do not interact with the subscriber list directly.
 *
 *  Differences from KSL's [ksl.modeling.entity.Signal]:
 *   - That one is a `ModelElement` that holds suspended entities and
 *     resumes them. It's a process-view primitive.
 *   - This one is a statechart broadcast — observers, not coroutines.
 *     A process waits for it through its [trigger] in `waitForFirst`,
 *     alone or with other triggers and a time limit; `waitFor` does not
 *     take it.
 *
 *  To fire one later, schedule it from the `AgentModel`:
 *  `schedule({ signal.fire() }, t)`.
 *
 *  @param name optional display name for diagnostics
 */
class AgentSignal(val name: String = "AgentSignal") {

    private val subscribers: MutableList<() -> Unit> = mutableListOf()

    /**
     *  Fire the signal. All currently-subscribed handlers run
     *  synchronously in the order they subscribed. If a handler
     *  triggers a transition that unsubscribes (or subscribes a
     *  different handler), only handlers active at the start of
     *  [fire] are invoked for this call.
     */
    fun fire() {
        if (subscribers.isEmpty()) return
        for (sub in subscribers.toList()) sub()
    }

    /**
     *  Add [handler] to the subscriber list. Returns a function that,
     *  when invoked, removes the handler. Intended for use by the
     *  statechart runtime, not direct user code.
     */
    internal fun subscribe(handler: () -> Unit): () -> Unit {
        subscribers.add(handler)
        return { subscribers.remove(handler) }
    }

    /** Number of currently-subscribed handlers (for diagnostics). */
    val numSubscribers: Int
        get() = subscribers.size

    /**
     *  A trigger for `waitForFirst` that fires when this signal is next fired. It never fires at once: a signal is
     *  an event, so the wait subscribes, and the next [fire] claims it, resuming the process. One [fire] ends the
     *  wait of every process waiting for it, as it reaches every subscribed statechart. The subscription ends with
     *  the wait, whichever trigger wins, and when the waiting process is terminated. Nothing is recorded: a signal
     *  keeps no statistics.
     */
    fun trigger(): WaitTrigger<Unit> = AgentSignalTrigger(this)
}

/** The trigger of an agent signal, made by `AgentSignal.trigger`. */
internal class AgentSignalTrigger(val signal: AgentSignal) : WaitTrigger<Unit>() {

    override fun canFireNow(entity: ProcessModel.Entity): Boolean = false

    override fun fireNow(process: ProcessModel.Entity.ProcessCoroutine) {
        error("An agent signal trigger cannot fire without a wait; canFireNow is always false.")
    }

    override fun register(group: WaitGroup): Registration {
        val entity = group.entity
        val registration = AgentSignalRegistration(this, group)
        entity.emitAnimation { AnimationEvent.WaitingForSignal(entity.processModel.time, entity.id, signal.name) }
        return registration
    }

    override val aloneState: AloneState
        get() = AloneState.WAITING_FOR_SIGNAL

    override fun toString(): String = "AgentSignalTrigger(${signal.name})"
}

/**
 *  An agent signal trigger's place in one wait: a subscription to the signal, which claims the wait when the signal
 *  is fired. The subscription ends at the claim, whoever wins, and at a termination.
 */
internal class AgentSignalRegistration(
    private val signalTrigger: AgentSignalTrigger,
    group: WaitGroup
) : Registration(signalTrigger, group) {

    private val unsubscribe: () -> Unit = signalTrigger.signal.subscribe {
        if (claimWait(Unit, ResumeSource.SIGNAL, ProcessModel.RESUME_PRIORITY)) end()
    }

    private fun end() = unsubscribe()

    override fun onReleaseAsLoser() = end()

    override fun onReleaseOnTermination() = end()

    override suspend fun ProcessModel.Entity.ProcessCoroutine.afterWinningResume(): AfterResume {
        markReleased()
        end()
        val entity = group.entity
        entity.emitAnimation { AnimationEvent.SignalReceived(entity.processModel.time, entity.id, signalTrigger.signal.name) }
        return AfterResume.Value(Unit)
    }
}
