/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.controls.experiments

import ksl.simulation.Model
import ksl.simulation.InMemorySnapshotCollector
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.SimulationDispatcher
import ksl.utilities.Identity
import ksl.utilities.io.KSL
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.io.OutputDirectory
import ksl.utilities.io.dbutil.KSLDatabase
import ksl.utilities.io.dbutil.SnapshotBatchWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.ConcurrentHashMap
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path

/**
 *  Executes a list of [Scenario] instances concurrently and writes all results to a shared
 *  [KSLDatabase].
 *
 *  ### Execution model
 *
 *  [simulate] operates in two phases:
 *
 *  **Phase 1 — concurrent simulation.**  Each scenario is dispatched as an independent
 *  coroutine on [SimulationDispatcher.default], a CPU-bounded dispatcher limited to
 *  `Runtime.availableProcessors()` threads.  Every coroutine builds a fresh [Model] via
 *  `scenario.modelBuilder`, attaches an [InMemorySnapshotCollector] to the model's lifecycle
 *  emitters, and runs the simulation.  All database-bound data is captured in memory during
 *  this phase; no database writes occur.
 *
 *  **Phase 2 — sequential database commit.**  After all coroutines complete ([awaitAll]),
 *  the collected snapshots are written to [kslDb] one scenario at a time via
 *  [SnapshotBatchWriter].  Serialising the writes avoids concurrent SQLite access while
 *  keeping simulation time fully parallel.
 *
 *  ### Model isolation requirement
 *
 *  Every scenario submitted to this runner **must** use a [ModelBuilderIfc] that constructs
 *  a fully independent [Model] instance on each [ModelBuilderIfc.build] call.  Scenarios
 *  built with the backward-compatible `model: Model` constructors of [Scenario] wrap a single
 *  shared instance and **must not** be used here — concurrent coroutines operating on the
 *  same model produce incorrect results and data races.
 *
 *  ### Error handling
 *
 *  If a simulation throws a [RuntimeException], the failing scenario's coroutine catches the
 *  exception, logs it, and continues without affecting other concurrent scenarios.  The
 *  failing scenario's [Scenario.simulationRun] will contain the error message via
 *  [SimulationRun.runErrorMsg]; its partial snapshots are not committed to the database.
 *
 *  @param name               Runner name; also used as the default database file stem and
 *                            output directory name.
 *  @param scenarioList       Initial list of scenarios to register.
 *  @param pathToOutputDirectory  Root directory under which per-scenario output sub-directories
 *                            are created.
 *  @param kslDb              Shared database that receives all scenario results.
 */
class ConcurrentScenarioRunner @JvmOverloads constructor(
    name: String,
    scenarioList: List<Scenario> = emptyList(),
    val pathToOutputDirectory: Path = KSL.createSubDirectory(name.replace(" ", "_") + "_OutputDir"),
    val kslDb: KSLDatabase = KSLDatabase("${name}.db".replace(" ", "_"), pathToOutputDirectory)
) : Identity(name) {

    private data class ScenarioRunOutcome(
        val scenario: Scenario,
        val simulationRun: SimulationRun,
        val collector: InMemorySnapshotCollector?
    )

    private val myScenarios = mutableListOf<Scenario>()

    /** Read-only ordered list of registered scenarios. */
    val scenarioList: List<Scenario>
        get() = myScenarios

    private val myScenariosByName = mutableMapOf<String, Scenario>()

    /** Read-only map of scenario name to [Scenario]. */
    val scenariosByName: Map<String, Scenario>
        get() = myScenariosByName

    init {
        for (scenario in scenarioList) {
            addScenario(scenario)
        }
    }

    /** Per-scenario job handles, populated during [simulate] so callers can
     *  cancel one running scenario without stopping the rest of the sweep. */
    private val jobsByName: ConcurrentHashMap<String, Job> = ConcurrentHashMap()

    /**
     *  Cancel a single scenario by name without stopping the rest of the
     *  sweep.  Looks up the scenario's coroutine job and issues a
     *  cooperative cancellation against it.  Returns `true` when a running
     *  scenario was found; `false` when the name is unknown, has already
     *  finished, or hasn't started yet.
     *
     *  Cancelled scenarios produce a `null` snapshot in the commit phase,
     *  so [RunEvent.ScenarioCompleted] is emitted for them with
     *  `snapshot == null`.  Sibling scenarios continue to run because
     *  [simulate] wraps each scenario's `async` in a `supervisorScope`,
     *  preventing one child's cancellation from propagating to the others.
     */
    fun cancelScenario(scenarioName: String): Boolean {
        val job = jobsByName[scenarioName] ?: return false
        if (!job.isActive) return false
        job.cancel(ScenarioCancellation(scenarioName))
        return true
    }

    /**
     *  Sentinel [CancellationException] used to mark per-scenario
     *  cancellations issued via [cancelScenario].  Letting only this
     *  subtype be swallowed in [awaitOrCancelled] preserves the
     *  framework's existing contract that a scenario throwing a
     *  plain [CancellationException] (e.g. from inside its
     *  `modelBuilder.build`) propagates up as a true coroutine
     *  cancellation instead of being treated as a recoverable
     *  failed-scenario outcome.
     */
    private class ScenarioCancellation(val scenarioName: String) :
        CancellationException("Scenario '$scenarioName' cancelled by user")

    /**
     *  Returns the scenario with the given [name], or `null` if none is registered.
     */
    fun scenarioByName(name: String): Scenario? = myScenariosByName[name]

    /**
     *  Registers [scenario] with this runner.  The scenario name must be unique.
     */
    fun addScenario(scenario: Scenario) {
        require(scenario.supportsConcurrentExecution) {
            "Scenario '${scenario.name}' was constructed with a pre-built Model and reuses that instance. " +
                "ConcurrentScenarioRunner requires scenarios constructed with a ModelBuilderIfc that returns " +
                "a fresh Model for each run."
        }
        require(!myScenariosByName.containsKey(scenario.name)) {
            "Scenario ${scenario.name} already exists in this runner"
        }
        myScenarios.add(scenario)
        myScenariosByName[scenario.name] = scenario
    }

    /**
     *  Creates a [Scenario] from a [ModelBuilderIfc] and a full [ExperimentRunParameters]
     *  snapshot, registers it with this runner, and returns it.
     *
     *  The scenario name must be unique within this runner.
     */
    @JvmOverloads
    @Suppress("unused")
    fun addScenario(
        modelBuilder: ModelBuilderIfc,
        name: String,
        inputs: Map<String, Double> = emptyMap(),
        runParameters: ExperimentRunParameters,
        stringInputs: Map<String, String> = emptyMap(),
        jsonInputs: Map<String, String> = emptyMap(),
    ): Scenario {
        val s = Scenario(modelBuilder, name, inputs, stringInputs, jsonInputs, runParameters)
        addScenario(s)
        return s
    }

    /**
     *  Sets the number of replications to [numReps] for every registered scenario.
     */
    fun numReplicationsPerScenario(numReps: Int) {
        require(numReps >= 1) { "The number of replications for each scenario must be >= 1" }
        for (scenario in myScenarios) {
            scenario.scenarioRunParameters.numberOfReplications = numReps
        }
    }

    /**
     *  Assigns non-overlapping pre-run sub-stream advances to selected scenarios.
     *
     *  Common random numbers remain the default when this method is not called.
     *  With the default [streamAdvanceSpacing] of null, each selected scenario is
     *  assigned the next cumulative offset based on the previous selected
     *  scenario's number of replications. Supplying [streamAdvanceSpacing] uses a
     *  fixed gap between scenarios.
     *
     *  @param scenarios indices of scenarios to assign; defaults to all scenarios
     *  @param startingStreamAdvance first assigned advance value; must be >= 0
     *  @param streamAdvanceSpacing fixed spacing between selected scenarios; null uses cumulative replication counts
     */
    @JvmOverloads
    fun useIndependentRandomStreams(
        scenarios: IntProgression = myScenarios.indices,
        startingStreamAdvance: Int = 0,
        streamAdvanceSpacing: Int? = null
    ) {
        myScenarios.assignIndependentStreamAdvances(
            scenarios = scenarios,
            startingStreamAdvance = startingStreamAdvance,
            streamAdvanceSpacing = streamAdvanceSpacing
        )
    }

    /**
     *  Runs the selected scenarios concurrently and writes results to [kslDb].
     *
     *  This is a `suspend` function and must be called from a coroutine scope.  For
     *  command-line or test use, wrap with `runBlocking { runner.simulate() }`.
     *
     *  @param scenarios    Indices (into [scenarioList]) of the scenarios to execute.
     *                      Defaults to all registered scenarios.
     *  @param clearAllData When `true` (the default) the database is cleared before any
     *                      simulation starts.  Set to `false` only when the caller has
     *                      guaranteed that no existing experiment names will collide with
     *                      the names of scenarios being run; a collision will cause
     *                      [SnapshotBatchWriter] to throw during the commit phase.
     */
    suspend fun simulate(
        scenarios: IntProgression = myScenarios.indices,
        clearAllData: Boolean = true,
        onScenarioComplete: ((scenarioName: String, snapshot: ksl.utilities.io.dbutil.SimulationSnapshot.ExperimentCompleted?) -> Unit)? = null,
        executionMode: ksl.app.config.ExecutionMode = ksl.app.config.ExecutionMode.CONCURRENT,
        onScenarioStart: ((scenarioName: String, scenarioIndex: Int, totalScenarios: Int) -> Unit)? = null,
        onReplicationStart: (suspend (scenarioName: String, repNumber: Int, totalReps: Int) -> Unit)? = null,
        onReplicationEnd: (suspend (scenarioName: String, repNumber: Int, totalReps: Int) -> Unit)? = null,
        /**
         *  Per-scenario replication snapshots filtered out of the drained
         *  snapshot stream during the commit phase and forwarded in
         *  replication order.  Used by the Scenario app's reporting
         *  layer to reconstruct per-replication observations for box
         *  plots, multiple-comparison analyses, and replication traces.
         *  Safe to omit when the caller only needs across-replication
         *  summaries.
         */
        onScenarioReplications: ((scenarioName: String, snapshots: List<ksl.utilities.io.dbutil.SimulationSnapshot.ReplicationCompleted>) -> Unit)? = null
    ) = coroutineScope {
        if (clearAllData) kslDb.clearAllData()
        jobsByName.clear()

        val scenarioList = scenarios.filter { it in myScenarios.indices }.map { myScenarios[it] }
        val total = scenarioList.size

        // ── Phase 1: simulate ─────────────────────────────────────────────────
        // Both modes wrap per-scenario `async` in a supervisorScope so that
        // cancelling one scenario (via [cancelScenario]) doesn't cascade
        // into siblings.  CONCURRENT — fan out all asyncs and await each;
        // SEQUENTIAL — fan out one at a time, awaiting each before
        // starting the next.  Per-scenario start and per-replication
        // events are forwarded to the caller's lifecycle so multi-scenario
        // consumers can attribute progress to the correct row.
        val outcomes: List<ScenarioRunOutcome> = supervisorScope {
            when (executionMode) {
                ksl.app.config.ExecutionMode.CONCURRENT -> {
                    val pairs = scenarioList.mapIndexed { i, scenario ->
                        scenario to async(SimulationDispatcher.default) {
                            jobsByName[scenario.name] = kotlin.coroutines.coroutineContext[Job]!!
                            try {
                                onScenarioStart?.invoke(scenario.name, i + 1, total)
                                runScenario(scenario, onReplicationStart, onReplicationEnd)
                            } finally {
                                jobsByName.remove(scenario.name)
                            }
                        }
                    }
                    pairs.map { (scenario, d) -> awaitOrCancelled(d) { scenario } }
                }
                ksl.app.config.ExecutionMode.SEQUENTIAL ->
                    scenarioList.mapIndexed { i, scenario ->
                        val d = async(SimulationDispatcher.default) {
                            jobsByName[scenario.name] = kotlin.coroutines.coroutineContext[Job]!!
                            try {
                                onScenarioStart?.invoke(scenario.name, i + 1, total)
                                runScenario(scenario, onReplicationStart, onReplicationEnd)
                            } finally {
                                jobsByName.remove(scenario.name)
                            }
                        }
                        awaitOrCancelled(d) { scenario }
                    }
            }
        }

        // ── Phase 2: sequential DB commit ─────────────────────────────────────
        // All simulation data is held in memory until this ordered commit phase.
        val writer = SnapshotBatchWriter(kslDb)
        for (outcome in outcomes) {
            val scenario = outcome.scenario
            val collector = outcome.collector
            if (collector != null) {
                collector.use { c ->
                    val snapshots = c.drain()
                    val expCompleted = snapshots
                        .filterIsInstance<ksl.utilities.io.dbutil.SimulationSnapshot.ExperimentCompleted>()
                        .firstOrNull()
                    if (onScenarioReplications != null) {
                        val reps = snapshots
                            .filterIsInstance<ksl.utilities.io.dbutil.SimulationSnapshot.ReplicationCompleted>()
                        onScenarioReplications.invoke(scenario.name, reps)
                    }
                    onScenarioComplete?.invoke(scenario.name, expCompleted)
                    if (snapshots.isNotEmpty()) {
                        writer.write(snapshots)
                    }
                }
            } else {
                // collector is null when the scenario failed with a non-cancellation exception
                onScenarioComplete?.invoke(scenario.name, null)
            }
        }
    }

    /**
     *  Await [deferred], converting a per-scenario [CancellationException]
     *  into a null-collector outcome so the surrounding `supervisorScope`
     *  doesn't propagate the cancellation to siblings.  Sibling-cancel
     *  isolation is the entire point of routing per-scenario cancels
     *  through this helper rather than letting them bubble.
     */
    private suspend fun awaitOrCancelled(
        deferred: Deferred<ScenarioRunOutcome>,
        scenarioRef: () -> Scenario
    ): ScenarioRunOutcome = try {
        deferred.await()
    } catch (e: CancellationException) {
        // Plain CancellationException — not our doing — propagates so the
        // orchestrator's outer catch handles it (this matches the
        // long-standing "cancellation is not a failed scenario" contract,
        // exercised by ConcurrentScenarioRunnerTest).
        if (e !is ScenarioCancellation) throw e
        // Even for an explicit per-scenario cancel, propagate if the
        // surrounding orchestrator coroutine is itself cancelled
        // (e.g. global Cancel button).
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val s = scenarioRef()
        // Synthesize a SimulationRun for the cancelled scenario so the
        // commit phase has something to label "(cancelled)" in events.
        val run = s.simulationRun ?: SimulationRun(
            modelIdentifier = "",
            experimentRunParameters = s.scenarioRunParameters,
            inputs = s.inputs,
            stringInputs = s.stringInputs,
            jsonInputs = s.jsonInputs,
            modelConfiguration = s.modelConfiguration
        )
        ScenarioRunOutcome(s, run, null)
    }

    private suspend fun runScenario(
        scenario: Scenario,
        onReplicationStart: (suspend (scenarioName: String, repNumber: Int, totalReps: Int) -> Unit)? = null,
        onReplicationEnd: (suspend (scenarioName: String, repNumber: Int, totalReps: Int) -> Unit)? = null
    ): ScenarioRunOutcome {
        var modelIdentifier: String? = null
        var collector: InMemorySnapshotCollector? = null
        try {
            val modelDirName = scenario.name.replace(" ", "_") + "_OutputDir"
            val modelDir = KSLFileUtil.createSubDirectory(pathToOutputDirectory, modelDirName)

            val model = scenario.modelBuilder.build(scenario.modelConfiguration)
            modelIdentifier = model.modelIdentifier
            val simulationRun = SimulationRun(
                modelIdentifier = model.modelIdentifier,
                experimentRunParameters = scenario.scenarioRunParameters,
                inputs = scenario.inputs,
                stringInputs = scenario.stringInputs,
                jsonInputs = scenario.jsonInputs,
                modelConfiguration = scenario.modelConfiguration
            )
            scenario.simulationRun = simulationRun

            if (model.modelConfigurationManager != null && scenario.modelConfiguration != null) {
                model.configuration = scenario.modelConfiguration!!
            }
            model.outputDirectory = OutputDirectory(modelDir, outFileName = "kslOutput.txt")

            // Attach collector before the simulation lifecycle starts so the
            // completed-experiment snapshot is available for the ordered DB commit.
            collector = InMemorySnapshotCollector(model.lifeCycleEmitters)
            // Forward per-replication progress to the caller's callbacks (if any)
            // tagged with this scenario's name so multi-scenario consumers can
            // attribute progress to the right row.
            val startCb: (suspend (Int, Int) -> Unit)? = onReplicationStart?.let { cb ->
                { rep, total -> cb(scenario.name, rep, total) }
            }
            val endCb: (suspend (Int, Int) -> Unit)? = onReplicationEnd?.let { cb ->
                { rep, total -> cb(scenario.name, rep, total) }
            }
            ConcurrentSimulationRunner(model).simulate(
                simulationRun = simulationRun,
                onReplicationStarted = startCb,
                onReplicationEnded = endCb
            )
            return ScenarioRunOutcome(scenario, simulationRun, collector)

        } catch (e: CancellationException) {
            collector?.close()
            throw e
        } catch (e: RuntimeException) {
            // Isolate ordinary scenario failures, but do not treat coroutine
            // cancellation as a failed scenario.
            collector?.close()
            Model.logger.error {
                "ConcurrentScenarioRunner: scenario '${scenario.name}' failed — ${e.message}"
            }
            recordFailedSimulationRun(scenario, e, modelIdentifier)
            return ScenarioRunOutcome(scenario, scenario.simulationRun!!, null)
        }
    }

    private fun recordFailedSimulationRun(
        scenario: Scenario,
        e: RuntimeException,
        modelIdentifier: String?
    ) {
        val simulationRun = scenario.simulationRun ?: SimulationRun(
            modelIdentifier = modelIdentifier ?: "model-unavailable:${scenario.name}",
            experimentRunParameters = scenario.scenarioRunParameters,
            inputs = scenario.inputs,
            stringInputs = scenario.stringInputs,
            jsonInputs = scenario.jsonInputs,
            modelConfiguration = scenario.modelConfiguration
        )

        if (simulationRun.runErrorMsg.isEmpty()) {
            simulationRun.runErrorMsg = stackTraceAsString(e)
        }
        simulationRun.results = emptyMap()
        scenario.simulationRun = simulationRun
    }

    private fun stackTraceAsString(e: RuntimeException): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        e.printStackTrace(pw)
        return sw.toString()
    }

    /**
     *  Prints basic half-width summary reports for each scenario to the console.
     */
    fun print() {
        write(PrintWriter(System.out))
    }

    /**
     *  Writes basic half-width summary reports for each scenario to [out].
     */
    @JvmOverloads
    fun write(out: PrintWriter = KSL.out) {
        for (s in scenarioList) {
            val sr = s.simulationRun?.statisticalReporter()
            val r = sr?.halfWidthSummaryReport(title = s.name)
            out.println(r)
            out.println()
        }
    }

    /**
     *  Returns a map of scenario name → per-replication observations for [responseName]
     *  across all executed scenarios.  Scenarios not yet executed or producing no
     *  observations for the response are silently omitted.
     *
     *  @param responseName the response to extract
     */
    fun observationsAsMap(responseName: String): Map<String, DoubleArray> {
        val result = linkedMapOf<String, DoubleArray>()
        for (scenario in scenarioList) {
            val obs = scenario.simulationRun?.replicationObservations(responseName)
            if (obs != null && obs.isNotEmpty()) {
                result[scenario.name] = obs
            }
        }
        return result
    }
}
