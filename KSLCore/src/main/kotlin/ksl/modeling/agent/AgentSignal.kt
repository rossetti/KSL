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
}
