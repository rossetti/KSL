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

package ksl.app.session

import kotlinx.datetime.Instant
import ksl.utilities.io.dbutil.SimulationSnapshot

/**
 * Sealed lifecycle event hierarchy emitted on [RunHandle.events] during a
 * simulation run.
 *
 * ## Event sequence guarantees
 *
 * For every submitted run exactly one *terminal* event is emitted as the
 * final event on the flow: [RunCompleted], [RunCancelled], or [RunFailed].
 * No further events are emitted after a terminal event.
 *
 * Each execution path emits its own concrete "started" variant — see
 * [Started] — followed by orchestrator-specific progress events:
 *
 * - per-replication runs (`Runner` / `SingleRunOrchestrator`) emit
 *   [ReplicationRunStarted] then [ReplicationStarted] / [ReplicationEnded]
 *   pairs;
 * - scenario sweeps (`ScenarioOrchestrator`) emit [ScenarioRunStarted]
 *   then one [ScenarioCompleted] per scenario;
 * - designed experiments (`ExperimentOrchestrator`) emit
 *   [ExperimentRunStarted] then one [DesignPointCompleted] per point;
 * - simulation optimization (`OptimizationOrchestrator`) emits
 *   [OptimizationRunStarted] then one [IterationCompleted] per iteration.
 *
 * A typical successful per-replication run produces:
 * ```
 * [RunWarning]?                  (zero or more, emitted before the run starts)
 * ReplicationRunStarted
 * ReplicationStarted(1)
 * ReplicationEnded(1)
 * ReplicationStarted(2)
 * ReplicationEnded(2)
 * ...
 * RunCompleted(summary)          ← terminal
 * ```
 *
 * ## What these events do NOT carry
 *
 * These events are **lifecycle-only** and carry no statistical observations.
 * Per-replication and across-replication statistics live in the output sinks
 * the user configured on the model (database, CSV, in-memory responses) and
 * are accessed through normal KSL APIs after [RunCompleted] is received.
 */
sealed class RunEvent {

    /**
     * Sealed parent of every "the run has started" event.
     *
     * Concrete variants — [ReplicationRunStarted], [ScenarioRunStarted],
     * [ExperimentRunStarted], [OptimizationRunStarted] — carry a count
     * field appropriate to their execution mode, but all share
     * [runId], [modelIdentifier], and [startTime].  GUIs that only need
     * a uniform "the run is now running" signal can match
     * `is RunEvent.Started` without distinguishing the variant.
     *
     * @property runId the unique run identifier assigned at submission time
     * @property modelIdentifier the model identifier at submission time
     * @property startTime wall-clock instant the run began executing
     */
    sealed class Started : RunEvent() {
        abstract val runId: String
        abstract val modelIdentifier: String
        abstract val startTime: Instant
    }

    /**
     * Emitted once by [Runner] immediately after
     * `model.initializeReplications()` on the per-replication execution
     * path used by `SingleRunOrchestrator`.
     *
     * @property runId the unique run identifier assigned by [Runner]
     * @property modelIdentifier `model.modelIdentifier` at submission time
     * @property totalReplications `model.numberOfReplications` at submission time
     * @property startTime wall-clock instant the experiment was initialized
     */
    data class ReplicationRunStarted(
        override val runId: String,
        override val modelIdentifier: String,
        val totalReplications: Int,
        override val startTime: Instant
    ) : Started()

    /**
     * Emitted once by `ScenarioOrchestrator` immediately before the scenario
     * sweep begins, after any pre-run warnings.
     *
     * @property runId the unique run identifier assigned by the orchestrator
     * @property modelIdentifier the run's model identifier
     * @property totalScenarios total number of scenarios in the run; matches
     *           the [ScenarioCompleted.totalScenarios] field on subsequent
     *           per-scenario events
     * @property startTime wall-clock instant the orchestrator began the sweep
     */
    data class ScenarioRunStarted(
        override val runId: String,
        override val modelIdentifier: String,
        val totalScenarios: Int,
        override val startTime: Instant
    ) : Started()

    /**
     * Emitted once by `ExperimentOrchestrator` immediately before the
     * designed experiment begins, after any pre-run warnings.
     *
     * @property runId the unique run identifier assigned by the orchestrator
     * @property modelIdentifier the run's model identifier
     * @property totalDesignPoints total number of design points; matches
     *           [DesignPointCompleted.totalDesignPoints] on subsequent events
     * @property startTime wall-clock instant the orchestrator began the experiment
     */
    data class ExperimentRunStarted(
        override val runId: String,
        override val modelIdentifier: String,
        val totalDesignPoints: Int,
        override val startTime: Instant
    ) : Started()

    /**
     * Emitted once by `OptimizationOrchestrator` immediately before
     * solver iteration begins, after any pre-run warnings.
     *
     * @property runId the unique run identifier assigned by the orchestrator
     * @property modelIdentifier the run's model identifier (typically
     *           `solver.problemDefinition.modelIdentifier`)
     * @property maxIterations the solver's `maximumNumberIterations` cap; an
     *           upper bound, not a guaranteed total — solvers may stop early
     *           on convergence
     * @property startTime wall-clock instant the orchestrator began
     *           solver iteration
     */
    data class OptimizationRunStarted(
        override val runId: String,
        override val modelIdentifier: String,
        val maxIterations: Int,
        override val startTime: Instant
    ) : Started()

    /**
     * Emitted when a potentially problematic configuration is detected
     * **before** the run starts.  Does not prevent the run from proceeding;
     * the receiving GUI or test driver should surface it to the user.
     *
     * Multiple warnings may be emitted for a single run.  All [RunWarning]
     * events are emitted before the run's [Started] variant.
     */
    data class RunWarning(val warning: RunWarningType) : RunEvent()

    /**
     * Emitted by [Runner] immediately before `model.runNextReplication()` is
     * called for replication [repNumber].
     *
     * @property repNumber 1-based index of the replication about to execute
     * @property totalReplications total replications requested for this run
     */
    data class ReplicationStarted(
        val repNumber: Int,
        val totalReplications: Int
    ) : RunEvent()

    /**
     * Emitted by [Runner] immediately after `model.runNextReplication()` returns
     * for replication [repNumber].
     *
     * @property repNumber 1-based index of the replication that just completed
     * @property totalReplications total replications requested for this run
     */
    data class ReplicationEnded(
        val repNumber: Int,
        val totalReplications: Int
    ) : RunEvent()

    /**
     * Carries the current simulation clock value and event execution count.
     *
     * Defined here so the sealed hierarchy is stable, but **not emitted in
     * Phase 1**.  Emission requires a mechanism to bridge the Executive's
     * synchronous event loop to the coroutine flow during a replication;
     * that mechanism is deferred to a later phase.
     *
     * @property simTime current value of the simulation clock
     * @property eventsExecuted cumulative events executed since experiment start
     */
    data class SimTimeAdvanced(
        val simTime: Double,
        val eventsExecuted: Long
    ) : RunEvent()

    /**
     * One line of output captured from `System.out` or `System.err` while
     * a GUI host (e.g. `kslSingleApp(...)`) had its *Capture stdout* toggle
     * enabled.  The framework itself does not emit these — they are
     * injected by the host's capture machinery into the host's console
     * pipeline so user `println` output appears alongside framework
     * events.  Listeners that filter on framework lifecycle events should
     * ignore this variant.
     *
     * @property text the captured line, without the trailing newline.
     * @property fromErr `true` if the line was written to `System.err`;
     *   `false` if to `System.out`.  Hosts use this to drive severity
     *   classification (stderr → ERROR, stdout → INFO).
     */
    data class StdOutLine(
        val text: String,
        val fromErr: Boolean
    ) : RunEvent()

    /**
     * Terminal event — emitted when an unexpected exception is thrown during
     * replication execution.  Always the last event on the flow.
     *
     * @property error typed description of the failure
     */
    data class RunFailed(val error: KSLRuntimeError) : RunEvent()

    /**
     * Terminal event — emitted when the run is stopped by an explicit call to
     * [RunHandle.cancel].  Always the last event on the flow.
     *
     * @property reason the message passed to [RunHandle.cancel]
     */
    data class RunCancelled(val reason: String) : RunEvent()

    /**
     * Terminal event — emitted when the run ends normally (all replications
     * completed, execution-time limit reached, or the model stopped itself via
     * `endSimulation()`).  Always the last event on the flow.
     *
     * @property summary lightweight post-run summary; see [RunSummary]
     */
    data class RunCompleted(val summary: RunSummary) : RunEvent()

    // ── Orchestrator events (Phase 5) ─────────────────────────────────────────

    /**
     * Emitted by `ScenarioOrchestrator` when an individual scenario's
     * simulation begins.  Under [ksl.app.config.ExecutionMode.SEQUENTIAL]
     * scenarios start one at a time; under [ksl.app.config.ExecutionMode.CONCURRENT]
     * every scenario emits this event before any of them begin replications.
     *
     * @property scenarioName the scenario name as specified in `ScenarioSpec.name`
     * @property index        1-based position of this scenario among all scenarios
     * @property totalScenarios total number of scenarios in the run
     */
    data class ScenarioStarted(
        val scenarioName: String,
        val index: Int,
        val totalScenarios: Int
    ) : RunEvent()

    /**
     * Emitted by `ScenarioOrchestrator` immediately before scenario
     * [scenarioName] begins replication [repNumber].  Carries the same
     * payload as [ReplicationStarted] but tagged with the scenario name,
     * so multi-scenario consumers (the Scenario app's GUI in particular)
     * can attribute per-replication progress to the right row.
     *
     * @property scenarioName scenario whose replication is starting
     * @property repNumber 1-based index of the replication about to execute
     * @property totalReplications total replications for this scenario
     */
    data class ScenarioReplicationStarted(
        val scenarioName: String,
        val repNumber: Int,
        val totalReplications: Int
    ) : RunEvent()

    /**
     * Emitted by `ScenarioOrchestrator` immediately after scenario
     * [scenarioName] finishes replication [repNumber].  Counterpart to
     * [ScenarioReplicationStarted].
     */
    data class ScenarioReplicationEnded(
        val scenarioName: String,
        val repNumber: Int,
        val totalReplications: Int
    ) : RunEvent()

    /**
     * Emitted by `ScenarioOrchestrator` after each scenario completes (or fails).
     *
     * Events are emitted in scenario-index order during the sequential commit phase
     * of `ConcurrentScenarioRunner`, after all scenarios have finished executing.
     *
     * @property scenarioName the scenario name as specified in `ScenarioSpec.name`
     * @property index        1-based position of this scenario among all scenarios
     * @property totalScenarios total number of scenarios in the run
     * @property snapshot     experiment-completed snapshot; `null` if the scenario
     *                        failed with a [RuntimeException]
     */
    data class ScenarioCompleted(
        val scenarioName: String,
        val index: Int,
        val totalScenarios: Int,
        val snapshot: SimulationSnapshot.ExperimentCompleted?
    ) : RunEvent()

    /**
     * Emitted by `ExperimentOrchestrator` after each design point completes.
     *
     * @property pointId      1-based design-point identifier within the experiment
     * @property index        1-based position of this design point in the run order
     * @property totalDesignPoints total number of design points in the experiment
     * @property snapshot     experiment-completed snapshot; `null` if the design
     *                        point failed with a [RuntimeException]
     */
    data class DesignPointCompleted(
        val pointId: Int,
        val index: Int,
        val totalDesignPoints: Int,
        val snapshot: SimulationSnapshot.ExperimentCompleted?
    ) : RunEvent()

    /**
     * Emitted by `OptimizationOrchestrator` after each solver iteration completes.
     *
     * @property iteration              1-based iteration counter
     * @property bestInputs             best input values found so far (variable name → value)
     * @property estimatedObjectiveValue estimated objective function value for [bestInputs]
     * @property solverSpecificState    optional solver-specific key-value state for diagnostics
     */
    data class IterationCompleted(
        val iteration: Int,
        val bestInputs: Map<String, Double>,
        val estimatedObjectiveValue: Double,
        val solverSpecificState: Map<String, Double>? = null
    ) : RunEvent()
}
