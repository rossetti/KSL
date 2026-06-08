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

import ksl.modeling.elements.EventGenerator
import ksl.modeling.elements.GeneratorActionIfc
import ksl.modeling.entity.ProcessModel
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.simulation.ConditionalAction
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.RVariableIfc

/**
 *  Container for an agent-based modeling layer that runs alongside KSL's
 *  process view. Following the precedent of
 *  [ksl.modeling.entity.TaskProcessingSystem], an `AgentModel` ships two
 *  kinds of agent — transient and permanent — plus the supporting
 *  machinery (a shared message bus, inner-class [Statechart] and
 *  [AgentMailbox]) that lets both kinds work uniformly.
 *
 *  Transient and permanent split:
 *   - [Agent]: a transient `Entity`-based actor (an `inner class`).
 *     A `QObject` under the hood, not a `ModelElement`, so it can be
 *     constructed *during* a replication. No automatic per-agent
 *     statistics. Use for arrival-driven populations, swarms, dynamic
 *     coalitions.
 *   - [PermanentAgent]: a `ModelElement`-derived setup-time actor.
 *     Constructed before `simulate()` runs. Full KSL lifecycle hooks
 *     and can register per-agent `Response`/`TWResponse` statistics.
 *     Use for structural cast members, named role-holders, anything
 *     where you want individual stats.
 *
 *  Both implement [AgentLike] (mailbox + name + currentTime) so the
 *  same [Statechart] and [AgentMailbox] machinery serves both.
 *
 *  Messaging:
 *   - A single [BlockingQueue] *message bus* is pre-allocated at
 *     `AgentModel` construction. All sends route through it; mailboxes
 *     are POJOs that filter by recipient reference. This is what makes
 *     transient agents possible — agent construction no longer creates
 *     a `ModelElement` child.
 *   - Per-mailbox queue statistics are not collected automatically.
 *     Permanent agents can register their own `Response`/`TWResponse`
 *     observers if they want individual stats (see the
 *     [ksl.modeling.entity.TaskProcessingSystem.TaskProcessorPerformance]
 *     pattern).
 *
 *  Statechart:
 *   - [Statechart] is an `inner class` of `AgentModel`, not a
 *     `ModelElement`. It uses the outer's `protected schedule(...)` and
 *     `executive` to schedule timeout events and register conditional
 *     actions. Lifecycle (start/stop on replication boundaries) is
 *     driven by `AgentModel.initialize()` / `afterReplication()` for
 *     transient `Agent`s, and by each `PermanentAgent` /
 *     `AgentResource`'s own lifecycle hooks for the permanent case.
 *
 *  @param parent the parent model element
 *  @param name an optional name for the agent model
 */
open class AgentModel(
    parent: ModelElement,
    name: String? = null,
) : ProcessModel(parent, name) {

    // ── Registry of setup-time agents ────────────────────────────────────────

    private val _agents: MutableList<AgentLike> = mutableListOf()

    /**
     *  All setup-time agents (Agent, PermanentAgent, AgentResource)
     *  registered with this model. Transient `Agent`s created *during*
     *  a replication are not added to this list — they manage their
     *  own lifecycle via the immediate-start path in `statechart { }`.
     */
    val agents: List<AgentLike>
        get() = _agents

    val agentCount: Int
        get() = _agents.size

    fun removeAgent(agent: AgentLike) {
        _agents.remove(agent)
    }

    private val registryObservers: MutableList<AgentRegistryObserver> = mutableListOf()

    /**
     *  Register an [AgentRegistryObserver] to be notified when any agent
     *  (setup-time or transient) is constructed and registered with this
     *  model. Typically attached at setup time. See [AgentRegistryObserver].
     */
    fun attachRegistryObserver(observer: AgentRegistryObserver) {
        registryObservers.add(observer)
    }

    /** Remove a previously-attached [AgentRegistryObserver]. No-op if not attached. */
    fun detachRegistryObserver(observer: AgentRegistryObserver) {
        registryObservers.remove(observer)
    }

    /** Number of currently-attached [AgentRegistryObserver]s (for diagnostics). */
    val registryObserverCount: Int
        get() = registryObservers.size

    internal fun notifyAgentRegistered(agent: AgentLike) {
        if (registryObservers.isEmpty()) return
        for (o in registryObservers.toList()) o.onAgentRegistered(agent)
    }

    // ── Mailbox (POJO inner class) ───────────────────────────────────────────

    /**
     *  Observer hook for an [AgentMailbox]. Called for every successful
     *  delivery (whether the message was queued or handed off directly
     *  to a waiting receiver) and for every successful consumption
     *  (whether via `tryTake`, `consume`, or `Waiter` match). Used by
     *  [AgentPerformance] to gather mailbox traffic statistics without
     *  coupling the mailbox to the statistics machinery.
     *
     *  Default methods are no-ops so observers only need to override
     *  what they care about.
     */
    interface MailboxObserver<M : AgentMessage> {
        fun onMessageDelivered(message: M, currentSize: Int) {}
        fun onMessageConsumed(message: M, currentSize: Int) {}
    }

    /**
     *  Observer hook for a [Statechart]. Fires on every state entry,
     *  state exit, and completed transition. Used by [AgentPerformance]
     *  to gather statechart-state statistics (time-in-state,
     *  entry counts, transition counts).
     *
     *  Default methods are no-ops.
     */
    interface StatechartObserver {
        /** Fires after entry actions have run for the entered state. */
        fun onStateEntered(stateName: String, time: Double) {}

        /** Fires after exit actions have run for the exited state. */
        fun onStateExited(stateName: String, time: Double) {}

        /**
         *  Fires once per completed transition, after both exit
         *  actions of [fromState] and entry actions of [toState] have
         *  run. A self-transition (transitionTo current state) still
         *  counts.
         */
        fun onTransition(fromState: String, toState: String, time: Double) {}
    }

    /**
     *  Observer hook notified when an agent — [Agent] (setup-time *or*
     *  transient/runtime), [PermanentAgent], or [AgentResource] — is
     *  constructed and registered with this model. Its purpose is to
     *  hand external/integration/reporting code a reference to every
     *  agent that comes into existence, *including transient agents
     *  created during a replication that are not in the [agents]
     *  registry and could not otherwise be referenced*.
     *
     *  Timing: fires from the agent's base constructor. The agent's
     *  [AgentLike.mailbox] is available (so a [MailboxObserver] can be
     *  attached immediately), but a statechart configured in a
     *  *subclass* `init { statechart { ... } }` is not installed yet —
     *  attach a [StatechartObserver] when the chart is built, or read
     *  `agent.statechart` later. There is intentionally no
     *  disposal/deregistration counterpart: transient agents may simply
     *  be garbage-collected, so end-of-life is not reliably observable
     *  here — observe a process completion or a statechart final state
     *  instead.
     *
     *  Attach via [attachRegistryObserver]; non-suspending, so it is
     *  safe to call from coroutine-free integration code.
     */
    interface AgentRegistryObserver {
        fun onAgentRegistered(agent: AgentLike) {}
    }

    /**
     *  A typed mailbox that holds [AgentMessage] values for a single
     *  [AgentLike] owner.
     *
     *  This is a *POJO* inner class — not a `ModelElement`, not backed
     *  by a `BlockingQueue`. Storage is a plain [ArrayDeque]; suspending
     *  receives use [ksl.modeling.entity.ProcessModel.Entity.Suspension]
     *  directly, which is KSL's per-entity custom-suspension primitive.
     *  This means a mailbox can be constructed at any time (notably,
     *  when its owning [Agent] is created during a replication).
     *
     *  Per-mailbox queue statistics are *not* collected. If you need
     *  them, register a `Response`/`TWResponse` observer at setup time
     *  via [PermanentAgent] and update it from your own send/receive
     *  call sites.
     *
     *  The mailbox is unbounded: [deliver] never blocks. Capacity-based
     *  back-pressure can be added later if needed.
     */
    open inner class AgentMailbox<M : AgentMessage> internal constructor(
        val owner: AgentLike,
    ) {

        /**
         *  Records a process suspended on a [receiveMessage] call. The
         *  predicate selects which messages this waiter accepts; when
         *  a matching message arrives (or is found by a sync scan) the
         *  [result] slot is set and [suspension] is resumed.
         */
        internal inner class Waiter(
            val predicate: (M) -> Boolean,
            val suspension: ProcessModel.Entity.Suspension,
        ) {
            var result: M? = null
        }

        private val pending: ArrayDeque<M> = ArrayDeque()
        private val waiters: MutableList<Waiter> = mutableListOf()
        private val arrivalListeners: MutableList<(M) -> Unit> = mutableListOf()
        private val observers: MutableList<MailboxObserver<M>> = mutableListOf()
        private val reservations: MutableList<Reservation> = mutableListOf()

        /**
         *  A scoped capture of messages matching [predicate]. While
         *  active, a matching message delivered to this mailbox is routed
         *  into the reservation's private [buffer] with priority over
         *  waiters, the pending queue, and arrival listeners — so a
         *  reserved conversation (e.g. a Contract-Net round) is never
         *  seen or consumed by an unrelated receiver on the same mailbox
         *  (a statechart `onMessage` handler, another `receiveMessage`).
         *  Call [release] when done.
         */
        inner class Reservation internal constructor(
            internal val predicate: (M) -> Boolean,
        ) {
            internal val buffer: ArrayDeque<M> = ArrayDeque()

            /** Messages captured so far, in arrival order. */
            fun collected(): List<M> = buffer.toList()

            /** Stop capturing; subsequent matching messages flow normally. */
            fun release() {
                reservations.remove(this)
            }
        }

        /**
         *  Reserve all messages matching [predicate] into an isolated
         *  side-channel until the returned [Reservation] is released.
         */
        internal fun reserve(predicate: (M) -> Boolean): Reservation {
            val r = Reservation(predicate)
            reservations.add(r)
            return r
        }

        /** Number of messages currently in the mailbox awaiting consumption. */
        val size: Int
            get() = pending.size

        val isEmpty: Boolean
            get() = pending.isEmpty()

        val isNotEmpty: Boolean
            get() = pending.isNotEmpty()

        /**
         *  Non-suspending message insertion. If a process is currently
         *  suspended in [receiveMessage] with a matching predicate,
         *  hand the message directly to that waiter and resume it.
         *  Otherwise queue the message in [pending] and fire any
         *  registered arrival listeners.
         */
        fun deliver(message: M) {
            // Reserved conversations capture matching messages first, in
            // isolation from every other consumer on this mailbox.
            val reservation = reservations.firstOrNull { it.predicate(message) }
            if (reservation != null) {
                reservation.buffer.addLast(message)
                notifyDelivered(message)
                notifyConsumed(message)  // captured out of the public flow
                return
            }
            // First, try to satisfy a waiting receiver directly.
            val waiterIdx = waiters.indexOfFirst { it.predicate(message) }
            if (waiterIdx >= 0) {
                val waiter = waiters.removeAt(waiterIdx)
                waiter.result = message
                notifyDelivered(message)
                notifyConsumed(message)   // direct handoff is delivery + immediate consumption
                waiter.suspension.resume()
                return
            }
            // No matching waiter — queue the message.
            pending.addLast(message)
            notifyDelivered(message)
            // Notify arrival listeners (statechart hooks etc.).
            if (arrivalListeners.isNotEmpty()) {
                val snapshot = arrivalListeners.toList()
                for (l in snapshot) l(message)
            }
        }

        private fun notifyDelivered(message: M) {
            if (observers.isEmpty()) return
            val sz = pending.size
            for (o in observers.toList()) o.onMessageDelivered(message, sz)
        }

        private fun notifyConsumed(message: M) {
            if (observers.isEmpty()) return
            val sz = pending.size
            for (o in observers.toList()) o.onMessageConsumed(message, sz)
        }

        /**
         *  Register [listener] to be invoked whenever a new message
         *  is delivered to this mailbox (after queueing — i.e., not for
         *  messages that were immediately handed off to a waiter).
         *  Returns a deregister function. Used by the statechart
         *  runtime.
         */
        fun onArrival(listener: (M) -> Unit): () -> Unit {
            arrivalListeners.add(listener)
            return { arrivalListeners.remove(listener) }
        }

        /**
         *  Snapshot of the messages currently in this mailbox awaiting
         *  consumption, in arrival order. Does not remove anything.
         *  Used by the statechart runtime to re-scan when entering a
         *  new state.
         */
        fun snapshot(): List<M> = pending.toList()

        /**
         *  Remove [message] from the mailbox if present (identity
         *  comparison). Returns true if removed. Used by the
         *  statechart runtime after consuming a matching message.
         */
        internal fun consume(message: M): Boolean {
            val idx = pending.indexOfFirst { it === message }
            if (idx < 0) return false
            val removed = pending.removeAt(idx)
            notifyConsumed(removed)
            return true
        }

        /**
         *  Non-suspending take: scan [pending] for a message matching
         *  [predicate], remove and return it if found, else return
         *  null. Used by the suspending `receiveMessage` extension to
         *  short-circuit when a matching message is already queued.
         */
        internal fun tryTake(predicate: (M) -> Boolean): M? {
            val idx = pending.indexOfFirst(predicate)
            if (idx < 0) return null
            val taken = pending.removeAt(idx)
            notifyConsumed(taken)
            return taken
        }

        /**
         *  Register a [MailboxObserver] to receive notifications on
         *  every delivery and consumption.
         *
         *  Used by [AgentPerformance] and other stats collectors, and by
         *  cross-view integration code (e.g. a station adapter that owns
         *  a mailbox and completes a service when a reply arrives).
         *  Observers are normally attached at setup time; attaching at
         *  runtime is allowed but only sees traffic delivered after the
         *  attach. Mirrors the public [Statechart.addObserver].
         */
        fun addObserver(observer: MailboxObserver<M>) {
            observers.add(observer)
        }

        /** Remove a previously-registered [MailboxObserver]. No-op if not attached. */
        fun removeObserver(observer: MailboxObserver<M>) {
            observers.remove(observer)
        }

        /** Number of currently-registered [MailboxObserver]s (for diagnostics). */
        val observerCount: Int
            get() = observers.size

        /**
         *  Register [predicate] as a waiter. The caller is responsible
         *  for suspending its entity via the returned suspension and
         *  reading the result after resumption.
         */
        internal fun registerWaiter(
            predicate: (M) -> Boolean,
            suspension: ProcessModel.Entity.Suspension,
        ): Waiter {
            val waiter = Waiter(predicate, suspension)
            waiters.add(waiter)
            return waiter
        }

        /**
         *  Remove a waiter that no longer needs to wait (cancellation
         *  or completion path).
         */
        internal fun removeWaiter(waiter: Waiter) {
            waiters.remove(waiter)
        }

        /**
         *  Clear all per-replication state: undelivered messages,
         *  suspended waiters, and statechart arrival listeners. Called
         *  at the start of each replication for permanent agents so a
         *  mailbox does not carry replication *n*'s traffic (or a waiter
         *  whose entity was terminated at end of replication) into
         *  replication *n+1*. Setup-time [observers] (stats collectors)
         *  are preserved; arrival listeners are re-registered by the
         *  statechart's `start()` immediately after this runs.
         */
        internal fun reset() {
            pending.clear()
            waiters.clear()
            reservations.clear()
            arrivalListeners.clear()
        }
    }

    // ── Statechart (POJO inner class) ────────────────────────────────────────

    /**
     *  A flat statechart attached to an [AgentLike] owner. Behavior
     *  consists of a finite set of named states; the chart is always
     *  in exactly one state once started.
     *
     *  Implemented as an `inner class` of [AgentModel] rather than a
     *  separate `ModelElement`: the inner-class outer-instance
     *  reference (`this@AgentModel`) gives this class direct access to
     *  the outer's `protected schedule(...)` and `executive`. No
     *  `ModelElement` registration is needed, so a `Statechart` can be
     *  constructed at any time — including during a replication, as
     *  part of a transient [Agent]'s construction.
     *
     *  Lifecycle:
     *   - [start] enters the initial state and installs triggers.
     *   - [stop] tears down pending timeout/condition/transition
     *     events and the arrival listener.
     *   - For setup-time agents, [AgentModel.initialize] /
     *     [AgentModel.afterReplication] (for transient `Agent`) or the
     *     owner's own lifecycle (for `PermanentAgent` / `AgentResource`)
     *     drives start/stop on each replication.
     *   - For runtime-created transient `Agent`s, `statechart { }`
     *     auto-starts the chart immediately since the simulation is
     *     already running.
     *
     *  Hierarchical states, history, and `onSignal` are not in this
     *  version (planned as Phase 1b.1).
     */
    inner class Statechart internal constructor(
        val owner: AgentLike,
        private val initialStateName: String,
        private val states: Map<String, StatechartState>,
        val statechartName: String,
        private val completionHandler: ((finalStateName: String) -> Unit)? = null,
    ) {

        init {
            require(states.containsKey(initialStateName)) {
                "Initial state '$initialStateName' is not declared in this statechart."
            }
        }

        private var currentLeaf: StatechartState? = null
        private var cachedActiveChain: List<StatechartState> = emptyList()

        // Per-state pending triggers — hierarchical states have
        // multiple active levels concurrently, each with its own
        // timeout, condition, and signal subscriptions.
        private val pendingTimeoutByState: MutableMap<String, KSLEvent<Nothing>> = mutableMapOf()
        private val pendingConditionByState: MutableMap<String, ConditionalAction> = mutableMapOf()
        private val signalDeregistersByState: MutableMap<String, List<() -> Unit>> = mutableMapOf()

        private var pendingTransitionTarget: String? = null
        private var pendingTransitionEvent: KSLEvent<Nothing>? = null
        private var arrivalDeregister: (() -> Unit)? = null
        private var started: Boolean = false
        private val observers: MutableList<StatechartObserver> = mutableListOf()

        /**
         *  All state names declared in this statechart (including
         *  nested substates), in undefined order. Used by
         *  [AgentPerformance] to pre-allocate per-state statistics
         *  responses.
         */
        val stateNames: Set<String>
            get() = states.keys

        /**
         *  Register an observer to receive state-entry, state-exit,
         *  and transition events. Events fire for *every* level of
         *  the active chain (composite + leaf), in entry / exit order.
         */
        fun addObserver(observer: StatechartObserver) {
            observers.add(observer)
        }

        /**
         *  Name of the currently active leaf state, or the last leaf
         *  held before the statechart was stopped at end-of-replication.
         *  `null` only before the first [start].
         */
        val currentStateName: String?
            get() = currentLeaf?.name

        /**
         *  All currently-active state names, root composite first,
         *  leaf last. For a flat statechart this is always a
         *  single-element list. Useful for diagnostics and for
         *  checking whether a particular composite is active.
         */
        val activeStateNames: List<String>
            get() = cachedActiveChain.map { it.name }

        val isRunning: Boolean
            get() = started

        /**
         *  True while this statechart is started (between [start] and
         *  [stop]). The guard used by `useStatechart` to forbid
         *  replacing a chart that is currently governing an agent.
         */
        val isStarted: Boolean
            get() = started

        /**
         *  Enter the initial state and install triggers. Idempotent —
         *  a second call before [stop] is a no-op. If the declared
         *  initial state is composite, descends through `initial`
         *  substates to reach the leaf. Registers with the enclosing
         *  [AgentModel] so it is cleaned up deterministically at
         *  end-of-replication even if the owning agent is never
         *  explicitly stopped.
         */
        fun start() {
            if (started) return
            started = true
            pendingTimeoutByState.clear()
            pendingConditionByState.clear()
            signalDeregistersByState.clear()
            pendingTransitionTarget = null
            pendingTransitionEvent = null
            arrivalDeregister = owner.mailbox.onArrival { msg -> onMessageArrival(msg) }
            this@AgentModel.registerActiveStatechart(this)

            val initialLeaf = resolveLeaf(states.getValue(initialStateName))
            val newChain = chainTo(initialLeaf)
            enterChain(fromIndex = 0, newChain = newChain)
        }

        /**
         *  Tear down all pending triggers across every active level
         *  and deregister from the [AgentModel] active-statechart
         *  registry. Leaves [currentStateName] reporting the last leaf
         *  for post-simulation inspection. Idempotent. Safe to call
         *  while the model is running (graceful stop) or between runs.
         */
        fun stop() {
            if (!started) return
            cancelPendingTransition()
            for (state in cachedActiveChain.reversed()) {
                cancelStateTriggers(state)
            }
            arrivalDeregister?.invoke()
            arrivalDeregister = null
            started = false
            this@AgentModel.deregisterActiveStatechart(this)
        }

        // ── Hierarchy helpers ────────────────────────────────────────────────

        /** Descend through `initialSubstate` chain to find the leaf. */
        private fun resolveLeaf(state: StatechartState): StatechartState {
            var s = state
            while (s.isComposite) {
                s = states.getValue(s.initialSubstate!!)
            }
            return s
        }

        /** Build root-to-leaf chain by walking parent pointers. */
        private fun chainTo(leaf: StatechartState): List<StatechartState> {
            val out = ArrayDeque<StatechartState>()
            var s: StatechartState? = leaf
            while (s != null) {
                out.addFirst(s)
                s = s.parent
            }
            return out.toList()
        }

        /**
         *  Find the deepest state common to both chains. Null when
         *  the chains share no ancestor — happens when transitioning
         *  between top-level state trees.
         */
        private fun findLCA(
            oldChain: List<StatechartState>,
            newChain: List<StatechartState>,
        ): StatechartState? {
            var lca: StatechartState? = null
            val limit = minOf(oldChain.size, newChain.size)
            for (i in 0 until limit) {
                if (oldChain[i] === newChain[i]) lca = oldChain[i] else break
            }
            return lca
        }

        // ── Entry / exit of state chains ────────────────────────────────────

        /**
         *  Enter the states in [newChain] starting at [fromIndex].
         *  Runs entry actions and installs triggers for each state
         *  top-down. After the leaf is entered, the mailbox is
         *  re-scanned against the new chain's message handlers in
         *  case any buffered messages now match.
         */
        private fun enterChain(fromIndex: Int, newChain: List<StatechartState>) {
            for (i in fromIndex until newChain.size) {
                val state = newChain[i]
                currentLeaf = state
                cachedActiveChain = newChain.subList(0, i + 1)
                runEntryActions(state)
                notifyStateEntered(state.name)
                if (pendingTransitionTarget != null) return
                installStateTriggers(state)
            }
            cachedActiveChain = newChain
            rescanMailboxForChain()
        }

        private fun installStateTriggers(state: StatechartState) {
            installTimeout(state)
            installCondition(state)
            installSignalHandlers(state)
        }

        private fun cancelStateTriggers(state: StatechartState) {
            pendingTimeoutByState[state.name]?.let { if (it.isScheduled) it.cancel = true }
            pendingTimeoutByState.remove(state.name)
            pendingConditionByState[state.name]?.let { this@AgentModel.executive.unregister(it) }
            pendingConditionByState.remove(state.name)
            signalDeregistersByState[state.name]?.forEach { it() }
            signalDeregistersByState.remove(state.name)
        }

        // ── Action context and notifications ────────────────────────────────

        private fun runEntryActions(state: StatechartState) {
            val ctx = newContext(state)
            for (action in state.entryActions) action(ctx)
        }

        private fun runExitActions(state: StatechartState) {
            val ctx = newContext(state)
            for (action in state.exitActions) action(ctx)
            notifyStateExited(state.name)
        }

        private fun notifyStateEntered(stateName: String) {
            if (observers.isEmpty()) return
            val t = this@AgentModel.time
            for (o in observers.toList()) o.onStateEntered(stateName, t)
        }

        private fun notifyStateExited(stateName: String) {
            if (observers.isEmpty()) return
            val t = this@AgentModel.time
            for (o in observers.toList()) o.onStateExited(stateName, t)
        }

        private fun notifyTransition(fromState: String, toState: String) {
            if (observers.isEmpty()) return
            val t = this@AgentModel.time
            for (o in observers.toList()) o.onTransition(fromState, toState, t)
        }

        // ── Per-state trigger installation ──────────────────────────────────

        private fun installTimeout(state: StatechartState) {
            val h = state.timeoutHandler ?: return
            val action = ModelElement.EventActionIfc<Nothing> { _ ->
                pendingTimeoutByState.remove(state.name)
                // Guard: state must still be in the active chain.
                if (state in cachedActiveChain && pendingTransitionTarget == null) {
                    val ctx = newContext(state)
                    h.action(ctx)
                }
            }
            val ev = this@AgentModel.schedule(
                action, h.duration, null, KSLEvent.DEFAULT_PRIORITY,
                "$statechartName:timeout:${state.name}",
            )
            pendingTimeoutByState[state.name] = ev
        }

        private fun installCondition(state: StatechartState) {
            val h = state.conditionHandler ?: return
            val ca = object : ConditionalAction() {
                private var fired = false
                override fun testCondition(): Boolean = !fired && h.test()
                override fun action() {
                    fired = true
                    if (state in cachedActiveChain && pendingTransitionTarget == null) {
                        val ctx = newContext(state)
                        h.action(ctx)
                    }
                }
            }
            this@AgentModel.executive.register(ca)
            pendingConditionByState[state.name] = ca
            // Bootstrap: the executive only sweeps ConditionalActions at a
            // time-advance boundary *after* an event executes. Without this,
            // a condition that is the only active trigger (e.g. on the
            // initial state, in a model that otherwise schedules nothing)
            // would never be evaluated. A zero-delay event guarantees a
            // prompt first sweep; the action itself is a no-op.
            this@AgentModel.schedule(
                ModelElement.EventActionIfc<Nothing> { _ -> },
                0.0, null, KSLEvent.DEFAULT_PRIORITY,
                "$statechartName:cond-bootstrap:${state.name}",
            )
        }

        private fun installSignalHandlers(state: StatechartState) {
            if (state.signalHandlers.isEmpty()) return
            val deregs = mutableListOf<() -> Unit>()
            for (sh in state.signalHandlers) {
                val dereg = sh.signal.subscribe {
                    // Guard 1: chain integrity.
                    if (state !in cachedActiveChain) return@subscribe
                    if (pendingTransitionTarget != null) return@subscribe
                    // Guard 2: most-specific (deepest active state) wins.
                    // Walk leaf-to-root; if there's a deeper active state
                    // listening to this same signal, defer to that one.
                    val mostSpecific = cachedActiveChain.asReversed().firstOrNull { s ->
                        s.signalHandlers.any { it.signal === sh.signal }
                    }
                    if (mostSpecific !== state) return@subscribe
                    val ctx = newContext(state)
                    sh.action(ctx)
                }
                deregs.add(dereg)
            }
            signalDeregistersByState[state.name] = deregs
        }

        private fun cancelPendingTransition() {
            pendingTransitionEvent?.let { ev ->
                if (ev.isScheduled) ev.cancel = true
            }
            pendingTransitionEvent = null
            pendingTransitionTarget = null
        }

        // ── Message handling (chain walk, most-specific wins) ───────────────

        private fun onMessageArrival(msg: AgentMessage) {
            if (pendingTransitionTarget != null) return
            for (state in cachedActiveChain.asReversed()) {
                val handler = state.messageHandlers.firstOrNull { it.matches(msg) } ?: continue
                @Suppress("UNCHECKED_CAST")
                (owner.mailbox as AgentMailbox<AgentMessage>).consume(msg)
                val ctx = newContext(state)
                handler.fire(ctx, msg)
                return
            }
        }

        /** Re-scan buffered mailbox messages against the current chain. */
        private fun rescanMailboxForChain() {
            if (cachedActiveChain.none { it.messageHandlers.isNotEmpty() }) return
            for (msg in owner.mailbox.snapshot()) {
                var consumed = false
                for (state in cachedActiveChain.asReversed()) {
                    val handler = state.messageHandlers.firstOrNull { it.matches(msg) } ?: continue
                    @Suppress("UNCHECKED_CAST")
                    (owner.mailbox as AgentMailbox<AgentMessage>).consume(msg)
                    val ctx = newContext(state)
                    handler.fire(ctx, msg)
                    consumed = true
                    break
                }
                if (consumed && pendingTransitionTarget != null) return
            }
        }

        // ── Transitions ─────────────────────────────────────────────────────

        private fun newContext(state: StatechartState): StateAction {
            return object : StateAction() {
                override val agent: AgentLike = owner
                override val currentStateName: String = state.name
                override fun transitionTo(stateName: String) {
                    if (pendingTransitionTarget != null) return
                    require(states.containsKey(stateName)) {
                        "Unknown state '$stateName' in statechart for ${owner.name}."
                    }
                    pendingTransitionTarget = stateName
                    val action = ModelElement.EventActionIfc<Nothing> { _ -> performTransition() }
                    pendingTransitionEvent = this@AgentModel.schedule(
                        action, 0.0, null, KSLEvent.DEFAULT_PRIORITY,
                        "$statechartName:transition:${state.name}->$stateName",
                    )
                }
            }
        }

        private fun performTransition() {
            val target = pendingTransitionTarget ?: return
            pendingTransitionEvent = null
            pendingTransitionTarget = null

            val targetLeaf = resolveLeaf(states.getValue(target))
            val newChain = chainTo(targetLeaf)
            val oldChain = cachedActiveChain
            val lca = findLCA(oldChain, newChain)
            val fromLeafName = currentLeaf?.name

            // Exit from leaf up to (not including) LCA, bottom-up.
            for (state in oldChain.asReversed()) {
                if (state === lca) break
                cancelStateTriggers(state)
                runExitActions(state)
            }

            // Trim the cached chain down to the LCA.
            val lcaIdx = if (lca == null) -1 else oldChain.indexOf(lca)
            cachedActiveChain = if (lcaIdx >= 0) oldChain.subList(0, lcaIdx + 1) else emptyList()
            currentLeaf = if (lcaIdx >= 0) oldChain[lcaIdx] else null

            // Enter from below LCA down to new leaf, top-down.
            val startIdx = if (lca == null) 0 else newChain.indexOf(lca) + 1
            enterChain(fromIndex = startIdx, newChain = newChain)

            // Fire the transition event.
            if (fromLeafName != null) notifyTransition(fromLeafName, target)

            // If the new leaf is a final state, the statechart's life
            // is over: stop it (tears down triggers, deregisters), then
            // fire the completion handler. The handler runs with the
            // chart already stopped, so it may select and start a
            // replacement via the owner's useStatechart(...).
            //
            // Exception: if the final state's entry action itself requested
            // a transition (pendingTransitionTarget is set), honor that
            // instead of finalizing — otherwise the requested transition
            // would be silently discarded. (Transitioning out of a final
            // state is unusual, but we don't drop the user's explicit
            // request; the scheduled performTransition will run next.)
            if (targetLeaf.isFinal && pendingTransitionTarget == null) {
                val finalName = targetLeaf.name
                stop()
                completionHandler?.invoke(finalName)
            }
        }
    }

    /**
     *  Factory used by [StatechartBuilder.build]; surface only because
     *  the builder is a top-level class and needs to construct an inner
     *  `Statechart` of this `AgentModel`.
     */
    internal fun newStatechart(
        owner: AgentLike,
        initialStateName: String,
        states: Map<String, StatechartState>,
        statechartName: String,
        completionHandler: ((finalStateName: String) -> Unit)? = null,
    ): Statechart = Statechart(owner, initialStateName, states, statechartName, completionHandler)

    // ── Active-statechart registry (§14.8) ──────────────────────────────────

    /**
     *  Every statechart that is currently started registers here, so
     *  that any still-active chart can be stopped deterministically at
     *  end-of-replication — even one owned by a transient [Agent] that
     *  the modeler never explicitly stopped. Without this, a chart
     *  that subscribed to an [AgentSignal] would be held alive by the
     *  signal's subscriber list and keep firing into a finished agent.
     */
    private val activeStatecharts: MutableSet<Statechart> = mutableSetOf()

    internal fun registerActiveStatechart(sc: Statechart) {
        activeStatecharts.add(sc)
    }

    internal fun deregisterActiveStatechart(sc: Statechart) {
        activeStatecharts.remove(sc)
    }

    // Per-model conversation-id counter, reset each replication so
    // Contract-Net ids are reproducible run-to-run and isolated from
    // other models in the same JVM (the simulation is single-threaded).
    private var conversationCounter: Long = 0

    internal fun nextConversationId(prefix: String = "cnp"): String =
        "$prefix-${++conversationCounter}"

    // ── Lifecycle: drive statechart start/stop ──────────────────────────────

    override fun initialize() {
        super.initialize()
        conversationCounter = 0
        // Start the charts of setup-time agents that have one installed.
        // (Runtime agents start their charts via useStatechart while the
        // model is running.)
        for (a in _agents) {
            if (a is Agent) {
                a.mailbox.reset()
                a.statechart?.start()
            }
        }
    }

    override fun afterReplication() {
        super.afterReplication()
        // Stop every still-active statechart, regardless of whether its
        // owner is a registered agent or a transient one. Copy first —
        // stop() deregisters and would mutate the set during iteration.
        for (sc in activeStatecharts.toList()) {
            sc.stop()
        }
        activeStatecharts.clear()
    }

    /**
     *  When an entity is disposed (e.g., a transient [Agent] whose
     *  process completed and is being reclaimed), stop its statechart
     *  so its subscriptions are torn down promptly rather than waiting
     *  for the end-of-replication sweep.
     */
    override fun dispose(entity: Entity) {
        super.dispose(entity)
        if (entity is Agent) entity.statechart?.stop()
    }

    // ── Transient agent ──────────────────────────────────────────────────────

    /**
     *  An autonomous, reactive actor. An `Agent` is a
     *  [ProcessModel.Entity] (and therefore a `QObject`, not a
     *  `ModelElement`), so instances can be constructed at any time —
     *  including during a running replication. Each agent gets a
     *  POJO [mailbox] for receiving messages and may optionally have a
     *  [statechart].
     *
     *  Two construction patterns:
     *
     *  **Setup-time** (before `simulate()`): the agent is added to
     *  the [agents] registry. Its statechart, if any, is started by
     *  [AgentModel.initialize] at each replication start and stopped
     *  by [AgentModel.afterReplication]. Use for the structural cast.
     *
     *  **Runtime** (during a replication): the agent is *not* added
     *  to the registry — it's an ephemeral actor that owns its own
     *  lifetime. Its statechart, if any, is auto-started by
     *  `statechart { }` since the simulation is already running. The
     *  caller is responsible for any cleanup; in typical arrival-driven
     *  patterns the agent's process completes and the agent is GC'd.
     *
     *  Behavior is supplied via the inherited `process { }` builder
     *  and/or a [statechart]. Both compose; see [Statechart].
     *
     *  @param aName an optional name for the agent
     */
    open inner class Agent(aName: String? = null) : Entity(aName), AgentLike {

        /** The enclosing [AgentModel] (for helpers like Contract-Net). */
        internal val agentModel: AgentModel get() = this@AgentModel

        override val mailbox: AgentMailbox<AgentMessage> = AgentMailbox(this)

        override var statechart: Statechart? = null
            internal set

        /**
         *  Declare and install this agent's statechart in one step. May
         *  be called at most once per agent (use [buildStatechart] +
         *  [useStatechart] for the multi-variant case). If the
         *  simulation is already running (runtime creation), the chart
         *  starts immediately; otherwise [AgentModel.initialize] starts
         *  it at the beginning of each replication.
         */
        fun statechart(block: StatechartBuilder.() -> Unit): Statechart {
            check(statechart == null) {
                "Agent ${this.name} already has a statechart configured."
            }
            val sc = buildStatechart("statechart", block)
            useStatechart(sc)
            return sc
        }

        /**
         *  Build a statechart *without* installing it. Use to define
         *  alternative behaviors that can be selected via [useStatechart]
         *  — at construction time (e.g., per-instance design variants)
         *  or between runs (e.g., a Controls-package parameter that
         *  selects which design the next `simulate()` exercises).
         */
        fun buildStatechart(name: String, block: StatechartBuilder.() -> Unit): Statechart {
            val builder = StatechartBuilder(this).apply(block)
            return builder.build(this@AgentModel, "${this.name}:$name")
        }

        /**
         *  Select the agent's active statechart. Allowed only when the
         *  current chart (if any) is not started — between runs, before
         *  activation, or after the current chart has stopped / reached
         *  a final state. Throws otherwise. If the model is running when
         *  this is called, the new chart starts immediately; otherwise
         *  [AgentModel.initialize] starts it at the next replication.
         */
        fun useStatechart(chart: Statechart?) {
            val current = statechart
            check(current == null || !current.isStarted) {
                "cannot replace ${this.name}'s statechart while it is running; " +
                    "stop it (or let it reach a final state) first."
            }
            // Defensive, unconditional teardown of the outgoing chart. The
            // guard above already guarantees it is not running, so this is a
            // no-op today; it makes the swap robust if that ever changes.
            current?.stop()
            statechart = chart
            if (chart != null && this@AgentModel.model.isRunning) chart.start()
        }

        /**
         *  Gracefully stop the agent's active statechart, if any.
         *  Tears down its triggers and subscriptions and deregisters it.
         *  The agent persists; a different chart may be selected via
         *  [useStatechart] and started. Idempotent.
         */
        fun stopStatechart() {
            statechart?.stop()
        }

        init {
            // Only add to the registry if created at setup time. Runtime
            // agents own their own lifecycle and don't need the
            // per-replication restart that the registry drives. Registry
            // *observers*, however, are notified for both — the transient
            // case is the one external code could not otherwise reference.
            if (!this@AgentModel.model.isRunning) {
                this@AgentModel._agents.add(this)
            }
            this@AgentModel.notifyAgentRegistered(this)
        }
    }

    // ── AgentPerformance: stats observer for PermanentAgent mailboxes ───────

    /**
     *  Per-agent statistics observer for a [PermanentAgent]. Modeled on
     *  [ksl.modeling.entity.TaskProcessingSystem.TaskProcessorPerformance]:
     *  a [ModelElement] that subscribes to a specific mailbox via
     *  [MailboxObserver] (and optionally a statechart via
     *  [StatechartObserver]) and publishes standard KSL
     *  [Response]/[TWResponse] instances.
     *
     *  **Mailbox metrics** (always published):
     *   - `NumMessagesReceived` — total messages delivered to the
     *     mailbox during a replication.
     *   - `NumMessagesConsumed` — total messages consumed from the
     *     mailbox during a replication.
     *   - `NumInMailbox` — time-weighted size of the pending queue.
     *
     *  With `allPerformance = true`, additionally:
     *   - `NumPendingAtEndOfReplication` — final pending size at
     *     end-of-replication.
     *
     *  **Statechart metrics** (published when the agent had a
     *  statechart configured at the time `collectPerformance` was
     *  called):
     *   - `TimeInState_<name>` — time-weighted fraction of replication
     *     time spent in each declared state.
     *   - `NumTimesEntered_<name>` — number of entries into each state
     *     per replication.
     *   - `NumTransitions` — total state transitions per replication.
     *
     *  Per-state responses are pre-allocated at construction from
     *  `statechart.stateNames` so no new ModelElements are created
     *  during simulate(). Consequently, **the statechart must be
     *  configured on the agent before `collectPerformance` is called**
     *  for statechart stats to be tracked.
     *
     *  Must be constructed before `simulate()` runs — it's a
     *  [ModelElement] and registers in the model's element map.
     *  Typically created via [PermanentAgent.collectPerformance].
     */
    inner class AgentPerformance internal constructor(
        parent: ModelElement,
        agent: AgentLike,
        val allPerformance: Boolean = false,
        name: String? = null,
    ) : ModelElement(parent, name ?: "${agent.name}:Performance") {

        // --- Mailbox-traffic responses ----------------------------------------

        private val myNumReceived: Response = Response(this, "${this.name}:NumMessagesReceived")
        val numMessagesReceivedResponse: ResponseCIfc
            get() = myNumReceived

        private val myNumConsumed: Response = Response(this, "${this.name}:NumMessagesConsumed")
        val numMessagesConsumedResponse: ResponseCIfc
            get() = myNumConsumed

        private val myNumInMailbox: TWResponse = TWResponse(this, "${this.name}:NumInMailbox")
        val numInMailboxResponse: TWResponseCIfc
            get() = myNumInMailbox

        private val myFinalPending: Response by lazy {
            Response(this, "${this.name}:NumPendingAtEndOfReplication")
        }
        val finalPendingResponse: ResponseCIfc?
            get() = if (allPerformance) myFinalPending else null

        // --- Statechart responses (only if statechart was defined at ctor) ---

        /**
         *  True if statechart stats are being collected. False if the
         *  agent had no statechart at the time of construction.
         */
        val tracksStatechart: Boolean = agent.statechart != null

        private val timeInStateResponses: Map<String, TWResponse>
        private val numTimesEnteredResponses: Map<String, Response>
        private val myNumTransitions: Response?

        /** Per-state time-weighted occupancy (1.0 while in state, else 0.0). */
        val timeInStateResponse: Map<String, TWResponseCIfc>
            get() = timeInStateResponses

        /** Per-state count of entries per replication. */
        val numTimesEnteredResponse: Map<String, ResponseCIfc>
            get() = numTimesEnteredResponses

        /** Total transitions per replication (null if no statechart). */
        val numTransitionsResponse: ResponseCIfc?
            get() = myNumTransitions

        // --- Accumulators reset at warmUp and replicationEnded ---------------

        private var deliveredCount: Long = 0
        private var consumedCount: Long = 0
        private val perStateEntryCounts: MutableMap<String, Long> = mutableMapOf()
        private var transitionCount: Long = 0

        private val observedAgent: AgentLike = agent

        init {
            if (allPerformance) {
                myFinalPending.id  // force lazy
            }

            // Pre-allocate statechart responses if the agent has a statechart.
            val sc = agent.statechart
            if (sc != null) {
                timeInStateResponses = sc.stateNames.associateWith { stateName ->
                    TWResponse(this, "${this.name}:TimeInState_$stateName")
                }
                numTimesEnteredResponses = sc.stateNames.associateWith { stateName ->
                    Response(this, "${this.name}:NumTimesEntered_$stateName")
                }
                myNumTransitions = Response(this, "${this.name}:NumTransitions")
                sc.addObserver(object : StatechartObserver {
                    override fun onStateEntered(stateName: String, time: Double) {
                        perStateEntryCounts[stateName] =
                            (perStateEntryCounts[stateName] ?: 0L) + 1L
                        timeInStateResponses[stateName]?.value = 1.0
                    }
                    override fun onStateExited(stateName: String, time: Double) {
                        timeInStateResponses[stateName]?.value = 0.0
                    }
                    override fun onTransition(fromState: String, toState: String, time: Double) {
                        transitionCount += 1
                    }
                })
            } else {
                timeInStateResponses = emptyMap()
                numTimesEnteredResponses = emptyMap()
                myNumTransitions = null
            }

            // Subscribe to mailbox events.
            agent.mailbox.addObserver(object : MailboxObserver<AgentMessage> {
                override fun onMessageDelivered(message: AgentMessage, currentSize: Int) {
                    deliveredCount += 1
                    myNumInMailbox.value = currentSize.toDouble()
                }
                override fun onMessageConsumed(message: AgentMessage, currentSize: Int) {
                    consumedCount += 1
                    myNumInMailbox.value = currentSize.toDouble()
                }
            })
        }

        override fun warmUp() {
            super.warmUp()
            deliveredCount = 0
            consumedCount = 0
            perStateEntryCounts.clear()
            transitionCount = 0
        }

        override fun replicationEnded() {
            super.replicationEnded()
            myNumReceived.value = deliveredCount.toDouble()
            myNumConsumed.value = consumedCount.toDouble()
            if (allPerformance) {
                myFinalPending.value = observedAgent.mailbox.size.toDouble()
            }
            if (tracksStatechart) {
                for ((stateName, response) in numTimesEnteredResponses) {
                    response.value = (perStateEntryCounts[stateName] ?: 0L).toDouble()
                }
                myNumTransitions!!.value = transitionCount.toDouble()
            }
            deliveredCount = 0
            consumedCount = 0
            perStateEntryCounts.clear()
            transitionCount = 0
        }
    }

    // ── Permanent agent (ModelElement, setup-time, stats-friendly) ──────────

    /**
     *  A setup-time agent that *is* a `ModelElement`. Use when you need
     *  KSL lifecycle hooks (`initialize`, `warmUp`, `afterReplication`)
     *  on the agent itself, or want to own per-agent
     *  `Response`/`TWResponse` statistics. Modeled on
     *  [ksl.modeling.entity.TaskProcessingSystem.TaskProcessor]: a
     *  ModelElement wrapper that holds its own POJO mailbox and
     *  statechart and drives their lifecycle through its own hooks.
     *
     *  Must be constructed before `simulate()` since [ModelElement]s
     *  cannot be added to the model while it is running.
     */
    open inner class PermanentAgent(name: String? = null) :
        ModelElement(this@AgentModel, name), AgentLike {

        override val mailbox: AgentMailbox<AgentMessage> = AgentMailbox(this)

        override val currentTime: Double
            get() = time

        override var statechart: Statechart? = null
            internal set

        /**
         *  Optional performance observer attached to this agent's
         *  mailbox. Created by [collectPerformance]; null otherwise.
         */
        var performance: AgentPerformance? = null
            private set

        fun statechart(block: StatechartBuilder.() -> Unit): Statechart {
            check(statechart == null) {
                "PermanentAgent ${this.name} already has a statechart configured."
            }
            val sc = buildStatechart("statechart", block)
            useStatechart(sc)
            return sc
        }

        /**
         *  Build a statechart *without* installing it. See
         *  [Agent.buildStatechart]. For a `PermanentAgent`, alternatives
         *  are typically selected via a control parameter between runs
         *  to compare designs without reconstructing the model.
         */
        fun buildStatechart(name: String, block: StatechartBuilder.() -> Unit): Statechart {
            val builder = StatechartBuilder(this).apply(block)
            return builder.build(this@AgentModel, "${this.name}:$name")
        }

        /**
         *  Select the active statechart. Allowed only when the current
         *  chart (if any) is not started — for a `PermanentAgent` that
         *  means between replications / `simulate()` calls (the chart
         *  is stopped at `afterReplication`). The selected chart is
         *  started by [initialize] at the next replication. See
         *  [Agent.useStatechart].
         */
        fun useStatechart(chart: Statechart?) {
            val current = statechart
            check(current == null || !current.isStarted) {
                "cannot replace ${this.name}'s statechart while it is running."
            }
            // Defensive, unconditional teardown of the outgoing chart (the
            // guard already ensures it is not running, so this is a no-op
            // today; it future-proofs the swap).
            current?.stop()
            statechart = chart
            if (chart != null && this@AgentModel.model.isRunning) chart.start()
        }

        /** Gracefully stop this agent's statechart, if any. Idempotent. */
        fun stopStatechart() {
            statechart?.stop()
        }

        /**
         *  Opt this agent in to mailbox-traffic statistics. Must be
         *  called before `simulate()` runs since the returned
         *  [AgentPerformance] is a [ModelElement]. Idempotent — a
         *  second call returns the existing observer.
         *
         *  @param allPerformance if true, additionally publishes the
         *    `NumPendingAtEndOfReplication` response
         */
        fun collectPerformance(allPerformance: Boolean = false): AgentPerformance {
            performance?.let {
                require(it.allPerformance == allPerformance) {
                    "${this.name} already has performance collection configured with " +
                        "allPerformance=${it.allPerformance}; cannot re-request with " +
                        "allPerformance=$allPerformance."
                }
                return it
            }
            val perf = AgentPerformance(parent = this, agent = this, allPerformance = allPerformance)
            performance = perf
            return perf
        }

        init {
            this@AgentModel._agents.add(this)
            this@AgentModel.notifyAgentRegistered(this)
        }

        override fun initialize() {
            super.initialize()
            // Reset before start(): clears stale traffic/waiters from the
            // previous replication, then start() re-registers the chart's
            // arrival listener on the now-clean mailbox.
            mailbox.reset()
            statechart?.start()
        }

        override fun afterReplication() {
            super.afterReplication()
            statechart?.stop()
        }
    }

    // ── Context: typed agent collection with attached projections ───────────

    /**
     *  A typed collection of agents with optional [Projection]s
     *  attached. Modeled on Repast Simphony's context concept:
     *  membership lives here, *structure* (positions, edges, networks)
     *  lives in attached projections.
     *
     *  Why a separate abstraction from [AgentModel._agents]:
     *   - The model-level registry tracks every setup-time agent for
     *     lifecycle dispatch. Contexts are user-defined groupings —
     *     pedestrians vs. vehicles, friendlies vs. adversaries, members
     *     of organization X — and the same agent can belong to
     *     multiple contexts.
     *   - Projections need a stable, queryable membership set to
     *     attach to. Contexts give it to them; projections subscribe
     *     to [add] / [remove] via `Projection.onAgentJoined` /
     *     `onAgentLeft`.
     *
     *  Context is a [ModelElement] so it gets a stable name in the
     *  model, lifecycle hooks (in case future projections need them),
     *  and a place in KSL reporting output. Membership changes during
     *  a replication are fine; the context itself must be constructed
     *  before `simulate()`.
     *
     *  Generic in the agent type [A] — typically `AgentModel.Agent` or
     *  a user subclass. Bounded by [AgentLike] so contexts can also
     *  hold [PermanentAgent] or [AgentResource] instances.
     */
    open inner class Context<A : AgentLike>(name: String? = null) :
        ModelElement(this@AgentModel, name) {

        private val _members: MutableSet<A> = LinkedHashSet()
        private val _projections: MutableList<Projection<A>> = mutableListOf()

        /**
         *  Membership established before the first replication — i.e.
         *  agents added during model construction (the permanent
         *  scaffolding, such as intersections in a road network).
         *  Captured at the first [initialize] and restored at the start
         *  of every subsequent replication. See [initialize].
         */
        private val _setupMembers: LinkedHashSet<A> = LinkedHashSet()
        private var setupCaptured = false

        /** Number of agents currently in this context. */
        val size: Int
            get() = _members.size

        val isEmpty: Boolean
            get() = _members.isEmpty()

        val isNotEmpty: Boolean
            get() = _members.isNotEmpty()

        /** True if [agent] is currently a member. */
        operator fun contains(agent: A): Boolean = _members.contains(agent)

        /** Read-only view of the current membership. */
        val members: Collection<A>
            get() = _members

        /** Read-only view of attached projections. */
        val projections: List<Projection<A>>
            get() = _projections

        /**
         *  Add [agent] to this context. Notifies every attached
         *  projection via `onAgentJoined`. No-op if already a member.
         */
        fun add(agent: A) {
            if (_members.add(agent)) {
                // Snapshot: a projection callback may mutate the context.
                for (p in _projections.toList()) p.onAgentJoined(agent)
            }
        }

        /**
         *  Remove [agent] from this context. Notifies every attached
         *  projection via `onAgentLeft`. No-op if not a member.
         */
        fun remove(agent: A) {
            if (_members.remove(agent)) {
                // Snapshot: a projection callback may mutate the context.
                for (p in _projections.toList()) p.onAgentLeft(agent)
            }
        }

        /**
         *  Attach [projection] to this context. The projection's
         *  lifecycle hooks fire on subsequent [add] / [remove]
         *  operations. Existing members do NOT retroactively trigger
         *  `onAgentJoined` — projections that need to initialize state
         *  for all existing members should do so explicitly.
         */
        fun <P : Projection<A>> addProjection(projection: P): P {
            require(!_projections.contains(projection)) {
                "projection '${projection.name}' is already attached to context '${this.name}'"
            }
            _projections.add(projection)
            return projection
        }

        /** Iterate the current membership snapshot. */
        fun forEach(action: (A) -> Unit) {
            for (m in _members.toList()) action(m)
        }

        /** Members of subtype [T]. */
        inline fun <reified T : A> ofType(): List<T> = members.filterIsInstance<T>()

        /** Members matching [predicate]. */
        fun where(predicate: (A) -> Boolean): List<A> = members.filter(predicate)

        /**
         *  Reset context membership at the start of every replication.
         *
         *  KSL initializes a model element's children before the element
         *  itself, so this `Context` (a child of the [AgentModel]) is
         *  reset *before* the user's `AgentModel.initialize()` runs and
         *  repopulates per-replication agents — there is no ordering
         *  race.
         *
         *  Semantics:
         *   - **First replication:** whatever was added during model
         *     construction is the permanent scaffolding; it is captured
         *     as the setup membership and left in place.
         *   - **Subsequent replications:** every agent added *during* the
         *     previous replication is removed (via [remove], firing each
         *     projection's `onAgentLeft` so per-agent spatial state is
         *     cleaned), restoring the context to exactly its setup
         *     membership. Setup members — and the structural projection
         *     state tied to them, such as network edges laid at
         *     construction — are never touched.
         *
         *  This makes multi-replication runs independent without
         *  requiring users to manually `clear()` in `afterReplication`.
         */
        override fun initialize() {
            super.initialize()
            if (!setupCaptured) {
                _setupMembers.addAll(_members)
                setupCaptured = true
                return
            }
            val runtimeMembers = _members.filter { it !in _setupMembers }
            for (a in runtimeMembers) remove(a)
        }

        /**
         *  Clear all members and notify projections. Rarely needed now
         *  that [initialize] resets runtime membership automatically;
         *  still available for models that want to empty the context
         *  mid-replication.
         */
        fun clear() {
            val snapshot = _members.toList()
            _members.clear()
            for (p in _projections) {
                for (m in snapshot) p.onAgentLeft(m)
            }
        }
    }

    // ── AgentGenerator (unchanged in contract) ──────────────────────────────

    /**
     *  Schedules the creation of agents over time and activates each
     *  agent's default process. Mirror of
     *  [ksl.modeling.entity.ProcessModel.EntityGenerator] but produces
     *  [Agent] instances. The agent must have `defaultProcess`
     *  configured.
     */
    protected inner class AgentGenerator<A : Agent>(
        private val agentFactory: () -> A,
        timeUntilFirst: RVariableIfc,
        timeBetween: RVariableIfc,
        maxAgents: Long = Long.MAX_VALUE,
        timeOfLast: Double = Double.POSITIVE_INFINITY,
        var activationPriority: Int = KSLEvent.DEFAULT_PRIORITY + 1,
        name: String? = null,
    ) : EventGenerator(
        this@AgentModel, null, timeUntilFirst, timeBetween, maxAgents, timeOfLast, name
    ) {

        @Suppress("unused")
        private val myAction = GeneratorActionIfc { generate() }

        override fun generate() {
            val agent = agentFactory()
            require(agent.defaultProcess != null) {
                "Agent ${agent.name} has no default process. Define one via " +
                    "process(isDefaultProcess = true) { ... } before generation."
            }
            activate(agent.defaultProcess!!, priority = activationPriority)
        }
    }
}
