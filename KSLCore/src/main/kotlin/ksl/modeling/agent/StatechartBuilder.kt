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
 *  Marker for DSL scope safety on the statechart builders. Prevents an
 *  inner `state { }` block from accidentally calling builder methods of an
 *  enclosing statechart builder.
 */
@DslMarker
annotation class StatechartDsl

/**
 *  Top-level builder for [AgentModel.Statechart]. Constructed via the
 *  `statechart { }` method on an [AgentLike] owner; users do not
 *  instantiate this class directly.
 *
 *  Within a `statechart { ... }` block, declare the initial state with
 *  [initial] and one or more states with [state]. The block must
 *  declare at least one state and the initial state must be one of
 *  them. States may be composite (nest substates via inner `state`
 *  blocks); a composite state must declare its initial substate via
 *  `initial(...)` inside the state block.
 */
@StatechartDsl
class StatechartBuilder internal constructor(internal val owner: AgentLike) {

    private var initialStateName: String? = null
    private val topLevelBuilders: MutableMap<String, StateBuilder> = mutableMapOf()
    private var completionHandler: ((finalStateName: String) -> Unit)? = null

    /**
     *  Designate [stateName] as the state the statechart enters when
     *  started. If that state is composite, its initial substate (and
     *  recursively any composite substates' initial substates) is
     *  resolved to the leaf state actually entered.
     */
    fun initial(stateName: String) {
        require(initialStateName == null) {
            "initial state already set to '$initialStateName'; cannot set it again to '$stateName'."
        }
        initialStateName = stateName
    }

    /**
     *  Declare a top-level state with the given [name] and configure
     *  it via [block]. The block may itself declare substates via
     *  nested `state` calls, producing a composite state.
     */
    fun state(name: String, block: StateBuilder.() -> Unit) {
        require(!topLevelBuilders.containsKey(name)) {
            "state '$name' is already declared in this statechart."
        }
        topLevelBuilders[name] = StateBuilder(name).apply(block)
    }

    /**
     *  Declare a *final* (terminal) state named [name]. Entering a
     *  final state runs the optional [block]'s `onEntry` actions, then
     *  auto-stops the statechart and fires [onCompletion]. Final
     *  states are leaves with no other triggers (no `onMessage`,
     *  `onTimeout`, `onCondition`, `onSignal`, or substates) — entering
     *  one ends the chart's life.
     *
     *  A statechart may declare multiple final states (e.g., `success`
     *  and `aborted`); the [onCompletion] handler receives the name of
     *  whichever was reached.
     */
    fun final(name: String, block: StateBuilder.() -> Unit = {}) {
        require(!topLevelBuilders.containsKey(name)) {
            "state '$name' is already declared in this statechart."
        }
        topLevelBuilders[name] = StateBuilder(name).apply(block).also { it.markFinal() }
    }

    /**
     *  Run [block] when the statechart reaches any final state (just
     *  after the chart has stopped). The block receives the name of
     *  the final state reached. A natural place to install and start a
     *  different statechart on the owner (`agent.useStatechart(next)`),
     *  send a completion message, or record a result — the chart is
     *  already stopped, so `isStarted` is false and a replacement may
     *  be selected.
     */
    fun onCompletion(block: (finalStateName: String) -> Unit) {
        require(completionHandler == null) {
            "statechart for ${owner.name} already declares an onCompletion handler."
        }
        completionHandler = block
    }

    internal fun build(agentModel: AgentModel, statechartName: String): AgentModel.Statechart {
        val initial = initialStateName
            ?: error("statechart for ${owner.name} did not declare an initial state via initial(\"...\").")
        require(topLevelBuilders.isNotEmpty()) {
            "statechart for ${owner.name} declared no states."
        }
        val allStates = mutableMapOf<String, StatechartState>()
        for ((_, tlBuilder) in topLevelBuilders) {
            val (_, descendants) = tlBuilder.buildWithDescendants(parent = null)
            for ((descName, descState) in descendants) {
                require(!allStates.containsKey(descName)) {
                    "state '$descName' is declared more than once in this statechart."
                }
                allStates[descName] = descState
            }
        }
        require(allStates.containsKey(initial)) {
            "initial state '$initial' was not declared in this statechart."
        }
        // Validate that every composite state's initialSubstate is one of its declared substates.
        for ((_, state) in allStates) {
            if (state.isComposite) {
                requireNotNull(state.initialSubstate) {
                    "composite state '${state.name}' must declare its initial substate via initial(\"...\")"
                }
                require(state.substateNames.contains(state.initialSubstate)) {
                    "composite state '${state.name}' initial substate '${state.initialSubstate}' is not a declared substate"
                }
            }
            // Final states must be leaves with no triggers.
            if (state.isFinal) {
                require(!state.isComposite) {
                    "final state '${state.name}' cannot be composite (have substates)"
                }
            }
        }
        // The initial state must not be a final state — a statechart
        // that starts already finished is almost certainly a mistake.
        require(!allStates.getValue(initial).isFinal) {
            "initial state '$initial' cannot be a final state."
        }
        return agentModel.newStatechart(owner, initial, allStates, statechartName, completionHandler)
    }
}

/**
 *  Builder for an individual [StatechartState]. Triggers and lifecycle
 *  hooks are configured by calling [onEntry], [onExit], [onMessage],
 *  [onTimeout], [onCondition], or [onSignal] inside the `state { }`
 *  block. Composite states declare substates by nesting more
 *  `state(...) { ... }` calls inside the block and naming one via
 *  [initial].
 *
 *  Each state may have at most one [onTimeout] and at most one
 *  [onCondition]; declaring more than one will fail at build time.
 *  Multiple [onMessage] / [onSignal] / [onEntry] / [onExit] are
 *  allowed and run in declaration order.
 */
@StatechartDsl
class StateBuilder internal constructor(private val name: String) {

    private val entryActions = mutableListOf<StateAction.() -> Unit>()
    private val exitActions = mutableListOf<StateAction.() -> Unit>()
    private val messageHandlers = mutableListOf<MessageHandler<*>>()
    private var timeoutHandler: TimeoutHandler? = null
    private var conditionHandler: ConditionHandler? = null
    private val signalHandlers = mutableListOf<SignalHandler>()
    private var initialSubstate: String? = null
    private val substateBuilders: MutableMap<String, StateBuilder> = mutableMapOf()
    private var isFinal: Boolean = false

    /** Mark this state as final (terminal). Called by `final(...)`. */
    internal fun markFinal() {
        isFinal = true
    }

    /**
     *  Run [block] when this state is entered, before any triggers
     *  are installed for the state. For composite states, entry runs
     *  the parent's actions first, then descends into the initial
     *  substate (whose entry actions then run).
     */
    fun onEntry(block: StateAction.() -> Unit) {
        entryActions.add(block)
    }

    /**
     *  Run [block] when this state is exited, after the state's
     *  triggers have been torn down and before the next state is
     *  entered. For composite states, substates exit first
     *  (bottom-up).
     */
    fun onExit(block: StateAction.() -> Unit) {
        exitActions.add(block)
    }

    /**
     *  Handle messages of type [T] arriving in the owner's mailbox
     *  while this state (or any substate) is active. When a message
     *  arrives, the runtime walks the active chain leaf-to-root and
     *  fires the first matching handler — most-specific wins.
     */
    inline fun <reified T : AgentMessage> onMessage(
        noinline predicate: (T) -> Boolean = { true },
        noinline block: StateAction.(T) -> Unit,
    ) {
        addMessageHandler(T::class.java, predicate, block)
    }

    /** Internal entry point used by the reified `onMessage` extension. */
    @PublishedApi
    internal fun <T : AgentMessage> addMessageHandler(
        type: Class<T>,
        predicate: (T) -> Boolean,
        block: StateAction.(T) -> Unit,
    ) {
        messageHandlers.add(MessageHandler(type, predicate, block))
    }

    /**
     *  After [duration] simulated time units elapse with this state
     *  active, run [block]. Each level (composite or leaf) has its
     *  own independent timeout; a composite-state timeout is scheduled
     *  when the composite is entered and cancelled when the composite
     *  is exited, regardless of substate transitions within.
     */
    fun onTimeout(duration: Double, block: StateAction.() -> Unit) {
        require(timeoutHandler == null) {
            "state '$name' already declares an onTimeout."
        }
        require(duration > 0.0) {
            "state '$name' onTimeout duration must be positive; was $duration."
        }
        timeoutHandler = TimeoutHandler(duration, block)
    }

    /**
     *  When [test] first returns `true` while this state is active,
     *  run [block]. Each level has its own independent condition.
     */
    fun onCondition(test: () -> Boolean, block: StateAction.() -> Unit) {
        require(conditionHandler == null) {
            "state '$name' already declares an onCondition."
        }
        conditionHandler = ConditionHandler(test, block)
    }

    /**
     *  When [signal] fires while this state (or any substate) is
     *  active, run [block]. If both this state and one of its
     *  substates listen to the same signal, the more-specific
     *  (deeper) handler wins — only it runs.
     */
    fun onSignal(signal: AgentSignal, block: StateAction.() -> Unit) {
        signalHandlers.add(SignalHandler(signal, block))
    }

    /**
     *  Designate [stateName] as the initial substate of this composite
     *  state. Required if this state has any substates; ignored
     *  otherwise.
     */
    fun initial(stateName: String) {
        require(initialSubstate == null) {
            "initial substate of '$name' already set to '$initialSubstate'; cannot set it again to '$stateName'."
        }
        initialSubstate = stateName
    }

    /**
     *  Declare a substate with the given [substateName]. Multiple
     *  substates can be declared; one must be designated as the
     *  initial via [initial].
     */
    fun state(substateName: String, block: StateBuilder.() -> Unit) {
        require(!substateBuilders.containsKey(substateName)) {
            "substate '$substateName' is already declared in '$name'."
        }
        substateBuilders[substateName] = StateBuilder(substateName).apply(block)
    }

    /**
     *  Declare a *final* (terminal) substate. Entering it auto-stops
     *  the whole statechart and fires its `onCompletion`. See
     *  [StatechartBuilder.final].
     */
    fun final(substateName: String, block: StateBuilder.() -> Unit = {}) {
        require(!substateBuilders.containsKey(substateName)) {
            "substate '$substateName' is already declared in '$name'."
        }
        substateBuilders[substateName] = StateBuilder(substateName).apply(block).also { it.markFinal() }
    }

    /**
     *  Build this state and all descendant substates recursively.
     *  Returns this state plus a flat map of all descendant states
     *  (including this one) with parent references set.
     */
    internal fun buildWithDescendants(
        parent: StatechartState?,
    ): Pair<StatechartState, Map<String, StatechartState>> {
        val self = StatechartState(
            name = name,
            entryActions = entryActions.toList(),
            exitActions = exitActions.toList(),
            messageHandlers = messageHandlers.toList(),
            timeoutHandler = timeoutHandler,
            conditionHandler = conditionHandler,
            signalHandlers = signalHandlers.toList(),
            initialSubstate = initialSubstate,
            substateNames = substateBuilders.keys.toSet(),
            isFinal = isFinal,
        )
        self.parent = parent

        val all = mutableMapOf<String, StatechartState>()
        all[name] = self
        for ((subName, subBuilder) in substateBuilders) {
            val (_, subDescendants) = subBuilder.buildWithDescendants(parent = self)
            for ((descName, descState) in subDescendants) {
                require(!all.containsKey(descName)) {
                    "duplicate state name '$descName' under '$name'"
                }
                all[descName] = descState
            }
            // Sanity: substate's name should match its registration name.
            require(subDescendants.containsKey(subName)) {
                "substate builder for '$subName' did not produce a state with that name"
            }
        }
        return self to all
    }
}
