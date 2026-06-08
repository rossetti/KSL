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
 *  Runtime context passed to every statechart action block. Exposes the
 *  owning [agent] so handlers can read or mutate agent state, and provides
 *  [transitionTo] to schedule a transition to another state.
 *
 *  Transitions are *scheduled*, not executed in place: calling
 *  [transitionTo] inside an action records the target and lets the current
 *  handler finish, then the transition runs as a zero-delay event. This
 *  avoids re-entrance issues when an action triggers another transition
 *  via cascading message arrivals or condition tests.
 *
 *  If an action calls [transitionTo] more than once, the last call wins.
 *  Subsequent calls are silently ignored if a transition is already
 *  pending.
 *
 *  The [agent] reference is typed as [AgentLike] so the same handler
 *  signature works for both [AgentModel.Agent] and [AgentResource]. Cast
 *  to the concrete type at the call site when an action needs
 *  owner-specific state.
 */
abstract class StateAction internal constructor() {
    abstract val agent: AgentLike
    abstract val currentStateName: String
    abstract fun transitionTo(stateName: String)
}

internal class TimeoutHandler(
    val duration: Double,
    val action: StateAction.() -> Unit,
)

internal class ConditionHandler(
    val test: () -> Boolean,
    val action: StateAction.() -> Unit,
)

internal class SignalHandler(
    val signal: AgentSignal,
    val action: StateAction.() -> Unit,
)

internal class MessageHandler<T : AgentMessage>(
    val messageType: Class<T>,
    val predicate: (T) -> Boolean,
    val action: StateAction.(T) -> Unit,
) {
    @Suppress("UNCHECKED_CAST")
    fun matches(msg: AgentMessage): Boolean =
        messageType.isInstance(msg) && predicate(msg as T)

    @Suppress("UNCHECKED_CAST")
    fun fire(ctx: StateAction, msg: AgentMessage) {
        ctx.action(msg as T)
    }
}

/**
 *  A configured state within a statechart. Created by the
 *  [StatechartBuilder] DSL; users do not instantiate this class
 *  directly. The actual statechart runtime that consumes these is
 *  [AgentModel.Statechart].
 *
 *  Hierarchical states: a state may be *composite* (contain
 *  substates) or *leaf* (no substates). [substateNames] lists the
 *  names of immediate children; [initialSubstate] designates which
 *  substate is entered first when this composite is entered.
 *  [parent] is null for top-level states and references the
 *  containing composite for substates; it is set by the
 *  [StatechartBuilder] at build time.
 */
class StatechartState internal constructor(
    val name: String,
    internal val entryActions: List<StateAction.() -> Unit>,
    internal val exitActions: List<StateAction.() -> Unit>,
    internal val messageHandlers: List<MessageHandler<*>>,
    internal val timeoutHandler: TimeoutHandler?,
    internal val conditionHandler: ConditionHandler?,
    internal val signalHandlers: List<SignalHandler>,
    val initialSubstate: String? = null,
    val substateNames: Set<String> = emptySet(),
    val isFinal: Boolean = false,
) {
    /** Set by [StatechartBuilder] at build time; null for top-level states. */
    internal var parent: StatechartState? = null

    /** True when this state has at least one substate. */
    val isComposite: Boolean
        get() = substateNames.isNotEmpty()
}
