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

package ksl.controls.experiments

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import ksl.simulation.InMemorySnapshotCollector
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.SimulationDispatcher
import ksl.utilities.Identity
import ksl.utilities.KSLArrays
import ksl.utilities.io.KSL
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.io.OutputDirectory
import ksl.utilities.io.addColumnsFor
import ksl.utilities.io.dbutil.KSLDatabase
import ksl.utilities.io.dbutil.SimulationSnapshot
import ksl.utilities.io.dbutil.SnapshotBatchWriter
import ksl.utilities.statistic.OLSRegression
import ksl.utilities.statistic.RegressionData
import ksl.utilities.statistic.RegressionResultsIfc
import ksl.utilities.toMapOfLists
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.*
import org.jetbrains.kotlinx.dataframe.io.writeCsv
import java.nio.file.Path

/**
 * Facilitates concurrent simulation of all design points in an experimental design.
 *
 * Unlike [DesignedExperiment], this class requires a [ModelBuilderIfc] so that each
 * design point runs on a fresh [Model] instance.  Execution uses structured concurrency:
 * design points are launched concurrently as coroutines on [SimulationDispatcher.default],
 * and results are committed to [kslDb] sequentially after all simulations complete.
 *
 * [simulateAll] and [simulate] are `suspend` functions and must be called from a coroutine
 * scope.  For command-line or test use, wrap with `runBlocking { experiment.simulateAll() }`.
 *
 * By default, design points use [DesignPointRandomStreamPolicy.INDEPENDENT_RANDOM_STREAMS].
 * Call [useCommonRandomNumbers] to make fresh model instances start from the same stream block.
 *
 * @param name the name of the parallel designed experiment
 * @param modelBuilder factory that returns a fresh model for each design-point run
 * @param factorSettings maps each factor to a numeric control or RV parameter key
 * @param design the design whose points will be simulated
 * @param modelConfiguration optional model configuration forwarded to the builder
 * @param pathToOutputDirectory output directory for design-point runs and database files
 * @param kslDb database that will hold all design-point results
 */
/**
 *  @param experimentName  Optional override for the base experiment
 *      name used to derive per-design-point experiment names
 *      ("<experimentName>_DP_<n>") and the matching output-directory
 *      names.  When `null` (the default), the base name comes from
 *      the template model's auto-generated `Experiment_<counter>`
 *      identity — which is JVM-counter-driven and changes between
 *      runs.  Callers that want deterministic, human-readable per-
 *      point names (e.g. the Experiment app: anchored to the
 *      analysis name) should pass an explicit value.  Persists to
 *      `EXPERIMENT.exp_name` in the KSL database, so the column
 *      values match the on-disk folder / file names.
 *  @param useDesignPointOutputDirs  When `true` (the default,
 *      preserving the original behaviour), every design point gets
 *      its own subdirectory under [pathToOutputDirectory] named
 *      `<experimentName>_DP_<n>_OutputDir`; each subdir contains
 *      that point's `kslOutput.txt` (and any per-point CSV / plot
 *      artifacts the model writes).  When `false`, every per-point
 *      model writes directly into [pathToOutputDirectory] and the
 *      diagnostic log uses a point-distinguished filename
 *      (`kslOutput_DP_<n>.txt`) so concurrent writers don't clash
 *      and re-runs overwrite cleanly.  False is the right default
 *      for callers that rely on the [kslDb] for per-point results.
 */
class ParallelDesignedExperiment @JvmOverloads constructor(
    name: String,
    private val modelBuilder: ModelBuilderIfc,
    private val factorSettings: Map<Factor, String>,
    override val design: ExperimentalDesignIfc,
    val modelConfiguration: Map<String, String>? = null,
    val pathToOutputDirectory: Path = KSL.createSubDirectory(name.replace(" ", "_") + "_OutputDir"),
    val kslDb: KSLDatabase = KSLDatabase("${name}.db".replace(" ", "_"), pathToOutputDirectory),
    private val experimentName: String? = null,
    private val useDesignPointOutputDirs: Boolean = true,
    /** Optional override for the template model's
     *  `lengthOfReplication`.  Applied to `baseRunParameters` after
     *  extraction so every design point's effective run parameters
     *  carry the override.  Null = inherit the model author's value. */
    private val lengthOfReplication: Double? = null,
    /** Optional override for the template model's
     *  `lengthOfReplicationWarmUp`.  Same flow as
     *  [lengthOfReplication]. */
    private val lengthOfReplicationWarmUp: Double? = null
) : Identity(name), DesignedExperimentIfc {

    private data class DesignPointRunPlan(
        val designPoint: DesignPoint,
        val experimentName: String,
        val inputs: Map<String, Double>,
        val runParameters: ExperimentRunParameters,
        val modelConfiguration: Map<String, String>?,
        val outputDirectoryName: String
    )

    private data class DesignPointRunOutcome(
        val plan: DesignPointRunPlan,
        val simulationRun: SimulationRun,
        val collector: InMemorySnapshotCollector?,
        /** True when this point was explicitly cancelled via
         *  [cancelDesignPoint] rather than completing or failing.
         *  Cancelled outcomes are NOT committed to the database. */
        val wasCancelled: Boolean = false
    )

    /**
     *  Per-design-point coroutine handles for the currently-running
     *  `simulate(...)` call.  Populated as each point launches and
     *  cleared after the commit phase for that point.  Keyed by
     *  `designPoint.number` (1-based) so [cancelDesignPoint] can
     *  target a specific point.  Concurrent because [cancelDesignPoint]
     *  may be invoked from any thread while the launch coroutine
     *  populates the map on another.
     */
    private val activeJobs: java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.Job> =
        java.util.concurrent.ConcurrentHashMap()

    /**
     *  IDs of design points whose cancellation was requested via
     *  [cancelDesignPoint].  Consulted by the per-point coroutine
     *  to distinguish "user cancelled THIS point" from "parent
     *  scope was cancelled" — the former returns a cancelled
     *  outcome to the commit phase; the latter re-throws so
     *  whole-run cancellation propagates as expected.
     */
    private val cancelledPointIds: MutableSet<Int> =
        java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    /**
     * Convenience constructor for two-level factor setting maps.
     */
    @Suppress("unused")
    constructor(
        name: String,
        modelBuilder: ModelBuilderIfc,
        twoLevelSettings: Map<TwoLevelFactor, String>,
        design: TwoLevelFactorialDesign,
        modelConfiguration: Map<String, String>? = null,
        pathToOutputDirectory: Path = KSL.createSubDirectory(name.replace(" ", "_") + "_OutputDir"),
        kslDb: KSLDatabase = KSLDatabase("${name}.db".replace(" ", "_"), pathToOutputDirectory)
    ) : this(
        name = name,
        modelBuilder = modelBuilder,
        factorSettings = DesignedExperiment.twoLevelFactorSetting(twoLevelSettings),
        design = design,
        modelConfiguration = modelConfiguration,
        pathToOutputDirectory = pathToOutputDirectory,
        kslDb = kslDb
    )

    /**
     * Convenience constructor for a function that creates a fresh model.
     */
    @Suppress("unused")
    constructor(
        name: String,
        modelCreator: () -> Model,
        factorSettings: Map<Factor, String>,
        design: ExperimentalDesignIfc,
        modelConfiguration: Map<String, String>? = null,
        pathToOutputDirectory: Path = KSL.createSubDirectory(name.replace(" ", "_") + "_OutputDir"),
        kslDb: KSLDatabase = KSLDatabase("${name}.db".replace(" ", "_"), pathToOutputDirectory)
    ) : this(
        name = name,
        modelBuilder = object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ksl.simulation.ExperimentRunParametersIfc?
            ): Model = modelCreator()
        },
        factorSettings = factorSettings,
        design = design,
        modelConfiguration = modelConfiguration,
        pathToOutputDirectory = pathToOutputDirectory,
        kslDb = kslDb
    )

    private val templateModel: Model = modelBuilder.build(modelConfiguration).also {
        // Anchor the base experiment name to the caller's choice
        // (e.g. an analysis name) so per-point experiment names are
        // deterministic across runs.  Without this, the template
        // model's auto-counter name leaks through and every re-run
        // gets a different prefix.
        if (!experimentName.isNullOrBlank()) {
            it.experimentName = experimentName
        }
    }
    private val baseRunParameters: ExperimentRunParameters = templateModel.extractRunParameters()
        .let { base ->
            // Apply caller-supplied overrides to the model's baked-in
            // run parameters.  Each is independent and copy()-ed only
            // when non-null so callers passing no overrides see the
            // original behaviour.
            var p = base
            if (lengthOfReplication != null) p = p.copy(lengthOfReplication = lengthOfReplication)
            if (lengthOfReplicationWarmUp != null) p = p.copy(lengthOfReplicationWarmUp = lengthOfReplicationWarmUp)
            p
        }
    private val mySimulationRuns = linkedMapOf<DesignPoint, SimulationRun>()

    private var myStartingStreamAdvance = 0
    private var myStreamAdvanceSpacing: Int? = null

    /**
     * A default value for the number of replications per design point.
     *
     * This value is used only when a simulation method does not supply a
     * numRepsPerDesignPoint argument and this property is greater than 0.
     */
    var defaultNumRepsPerDesignPoint: Int = -1

    /**
     * The active design-point random stream policy.
     */
    var streamPolicy: DesignPointRandomStreamPolicy =
        DesignPointRandomStreamPolicy.INDEPENDENT_RANDOM_STREAMS
        private set

    /**
     * Returns the executed runs, one run for each design point simulated.
     */
    override val simulationRuns: List<SimulationRun>
        get() = mySimulationRuns.values.toList()

    /**
     * The number of design points that have been executed.
     */
    override val numSimulationRuns: Int
        get() = mySimulationRuns.size

    /**
     * The names of the responses or counters in the template model.
     */
    override val responseNames: List<String>
        get() = templateModel.responseNames

    init {
        require(factorSettings.isNotEmpty()) { "factorSettings must not be empty" }
        for ((_, factor) in design.factors) {
            require(factorSettings.containsKey(factor)) {
                "The factor settings do not contain ${factor.name}, which is in the design."
            }
        }
        require(templateModel.validateInputKeys(factorSettings.values.toSet())) {
            "The factor settings, ${
                factorSettings.values.toSet().joinToString(prefix = "[", postfix = "]")
            } , contained invalid input names"
        }
    }

    /**
     * Assigns non-overlapping pre-run sub-stream advances to design points.
     *
     * With the default spacing of null, each design point's advance is based on
     * the cumulative replication counts of prior design points.
     */
    @JvmOverloads
    fun useIndependentRandomStreams(
        startingStreamAdvance: Int = 0,
        streamAdvanceSpacing: Int? = null
    ): ParallelDesignedExperiment {
        require(startingStreamAdvance >= 0) { "startingStreamAdvance must be >= 0" }
        streamAdvanceSpacing?.let {
            require(it >= 1) { "streamAdvanceSpacing must be >= 1" }
        }
        streamPolicy = DesignPointRandomStreamPolicy.INDEPENDENT_RANDOM_STREAMS
        myStartingStreamAdvance = startingStreamAdvance
        myStreamAdvanceSpacing = streamAdvanceSpacing
        return this
    }

    /**
     * Makes every design point start from the same random stream block.
     */
    fun useCommonRandomNumbers(): ParallelDesignedExperiment {
        streamPolicy = DesignPointRandomStreamPolicy.COMMON_RANDOM_NUMBERS
        return this
    }

    /**
     * Clears previously executed simulation runs.
     */
    fun clearSimulationRuns() {
        mySimulationRuns.clear()
    }

    /**
     * @param coded indicates if the points should be coded, the default is false
     */
    fun replicatedDesignPoints(coded: Boolean = false): List<DoubleArray> {
        val dpList = mutableListOf<DoubleArray>()
        for ((dp, _) in mySimulationRuns) {
            for (i in 1..dp.numReplications) {
                val points = if (coded) dp.codedValues() else dp.values()
                dpList.add(points)
            }
        }
        return dpList
    }

    /**
     * Returns replicated design point information in execution order as a data frame.
     */
    fun replicatedDesignPointInfo(): DataFrame<DesignPointInfo> {
        val list = mutableListOf<DesignPointInfo>()
        for ((dp, sr) in mySimulationRuns) {
            for (r in 1..dp.numReplications) {
                list.add(DesignPointInfo(dp.number, sr.experimentRunParameters.experimentName, r))
            }
        }
        return list.toDataFrame()
    }

    /**
     * Returns a data frame with columns (point, exp_name, rep_id, factor1, factor2, ..., factorN).
     */
    fun replicatedDesignPointsAsDataFrame(coded: Boolean = false): AnyFrame {
        if (mySimulationRuns.isEmpty()) {
            return DataFrame.empty()
        }
        val points = KSLArrays.to2DDoubleArray(replicatedDesignPoints(coded))
        val cols = points.toMapOfLists(design.factorNames)
        val dpi = replicatedDesignPointInfo()
        return if (dpi.rowsCount() == points.size) {
            dpi.add(cols.toDataFrame())
        } else {
            cols.toDataFrame()
        }
    }

    /**
     * Returns a data frame with columns (point, exp_name, rep_id, [responseName]).
     */
    fun responseAsDataFrame(responseName: String): AnyFrame {
        if (mySimulationRuns.isEmpty()) return DataFrame.empty()
        data class ResponseRow(
            val point: Int,
            val exp_name: String,
            val rep_id: Int,
            val value: Double
        )
        val myRows = mutableListOf<ResponseRow>()
        for ((dp, sr) in mySimulationRuns) {
            val myObs = sr.results[responseName] ?: continue
            val myExpName = sr.experimentRunParameters.experimentName
            for (r in 1..dp.numReplications) {
                myRows.add(ResponseRow(dp.number, myExpName, r, myObs.getOrElse(r - 1) { Double.NaN }))
            }
        }
        if (myRows.isEmpty()) return DataFrame.empty()
        return myRows.toDataFrame().rename("value").into(responseName)
    }

    /**
     * Returns a map of design-point label to per-replication observations for [responseName].
     */
    override fun observationsAsMap(responseName: String): Map<String, DoubleArray> {
        val myResult = linkedMapOf<String, DoubleArray>()
        simulationRuns.forEachIndexed { idx, myRun ->
            val myObs = myRun.replicationObservations(responseName)
            if (myObs != null && myObs.isNotEmpty()) {
                myResult["Point ${idx + 1}"] = myObs
            }
        }
        return myResult
    }

    /**
     * Returns a data frame with one response joined to replicated design points.
     */
    fun replicatedDesignPointsWithResponse(responseName: String, coded: Boolean = false): AnyFrame {
        return replicatedDesignPointsWithResponses(setOf(responseName), coded)
    }

    /**
     * Returns a data frame with selected responses joined to replicated design points.
     */
    fun replicatedDesignPointsWithResponses(
        names: Set<String> = responseNames.toSet(),
        coded: Boolean = false
    ): AnyFrame {
        require(names.isNotEmpty()) { "The supplied names cannot be empty" }
        if (mySimulationRuns.isEmpty()) {
            return DataFrame.empty()
        }
        val vn = responseNames
        var df = replicatedDesignPointsAsDataFrame(coded)
        val exp_name by column<String>()
        val rep_id by column<Int>()
        val point by column<Int>()
        for (name in names) {
            if (vn.contains(name)) {
                val df2 = responseAsDataFrame(name)
                df = df2.join(df, type = JoinType.Inner) { exp_name and rep_id and point }
            }
        }
        return df
    }

    /**
     * Returns regression data as a dataframe for the supplied response and linear model.
     */
    fun regressionDataAsDataFrame(
        responseName: String,
        linearModel: LinearModel,
        coded: Boolean = true
    ): AnyFrame {
        var df = replicatedDesignPointsWithResponse(responseName, coded)
        df = df.addColumnsFor(linearModel)
        return df
    }

    /**
     * Returns regression data for the supplied response and linear model.
     */
    fun regressionData(
        responseName: String,
        linearModel: LinearModel,
        coded: Boolean = true
    ): RegressionData {
        val df = regressionDataAsDataFrame(responseName, linearModel, coded)
        val rns = linearModel.termsAsMap.keys.toList()
        return RegressionData.create(df, responseName, rns, linearModel.intercept)
    }

    /**
     * Performs OLS regression for the supplied response and linear model.
     */
    fun regressionResults(
        responseName: String,
        linearModel: LinearModel
    ): RegressionResultsIfc = regressionResults(responseName, linearModel, coded = true)

    /**
     * Performs OLS regression for the supplied response and linear model.
     */
    override fun regressionResults(
        responseName: String,
        linearModel: LinearModel,
        coded: Boolean
    ): RegressionResultsIfc {
        val rd = regressionData(responseName, linearModel, coded)
        return OLSRegression(rd)
    }

    /**
     * Simulates all design points concurrently.
     *
     * This is a `suspend` function and must be called from a coroutine scope.
     * For command-line or test use, wrap with `runBlocking { experiment.simulateAll() }`.
     *
     * @param onDesignPointComplete optional callback invoked after each design point's
     *   results are committed to [kslDb] (in design-point order). Receives the [DesignPoint]
     *   and its [SimulationSnapshot.ExperimentCompleted], or `null` if the design point
     *   failed with a [RuntimeException].
     */
    suspend fun simulateAll(
        numRepsPerDesignPoint: Int? = null,
        clearRuns: Boolean = true,
        addRuns: Boolean = true,
        clearAllData: Boolean = true,
        onDesignPointComplete: ((designPoint: DesignPoint, snapshot: SimulationSnapshot.ExperimentCompleted?) -> Unit)? = null,
        onDesignPointStart: ((designPoint: DesignPoint) -> Unit)? = null,
        onDesignPointCancelled: ((designPoint: DesignPoint) -> Unit)? = null
    ) {
        simulate(
            design.iterator(), numRepsPerDesignPoint, clearRuns, addRuns, clearAllData,
            onDesignPointComplete, onDesignPointStart, onDesignPointCancelled
        )
    }

    /**
     * Simulates the design points presented by [iterator] concurrently.
     *
     * Design points are launched in parallel on [SimulationDispatcher.default].
     * After all simulations complete, results are committed to [kslDb] sequentially
     * in iterator order and [onDesignPointComplete] is fired for each point.
     * Cancellation is checked between each design-point commit, so cooperative
     * cancellation takes effect at design-point boundaries during the commit phase.
     *
     * This is a `suspend` function and must be called from a coroutine scope.
     * For command-line or test use, wrap with `runBlocking { experiment.simulate(iterator) }`.
     *
     * @param onDesignPointComplete optional callback invoked after each design point's
     *   results are committed to [kslDb]. Receives the [DesignPoint] and its
     *   [SimulationSnapshot.ExperimentCompleted], or `null` if the design point
     *   failed with a [RuntimeException].
     */
    suspend fun simulate(
        iterator: Iterator<DesignPoint>,
        numRepsPerDesignPoint: Int? = null,
        clearRuns: Boolean = true,
        addRuns: Boolean = true,
        clearAllData: Boolean = true,
        onDesignPointComplete: ((designPoint: DesignPoint, snapshot: SimulationSnapshot.ExperimentCompleted?) -> Unit)? = null,
        onDesignPointStart: ((designPoint: DesignPoint) -> Unit)? = null,
        onDesignPointCancelled: ((designPoint: DesignPoint) -> Unit)? = null
    ) {
        if (clearRuns) clearSimulationRuns()

        val designPoints = iterator.asSequence().toList()
        if (designPoints.isEmpty()) {
            val wm = "WARNING: The supplied iterator for parallel designed experiment, $name, had no design points."
            Model.logger.warn { wm }
            println()
            println(wm)
            println()
            System.out.flush()
            return
        }

        // Reset per-run cancellation state so a prior simulate() run's
        // bookkeeping doesn't leak into this one.
        activeJobs.clear()
        cancelledPointIds.clear()

        // supervisorScope (vs. coroutineScope): when ONE child coroutine
        // is cancelled via [cancelDesignPoint], the cancellation does
        // NOT propagate to sibling children.  Whole-run cancellation
        // (cancelling the parent scope) still propagates downward
        // because supervisorScope itself respects its own cancellation.
        kotlinx.coroutines.supervisorScope {
            if (clearAllData) kslDb.clearAllData()

            val effectiveNumReps = effectiveNumRepsPerDesignPoint(numRepsPerDesignPoint)
            if (effectiveNumReps != null) {
                designPoints.forEach { it.numReplications = effectiveNumReps }
            }

            // Build native design-point plans.  A design point is not a
            // Scenario, even though both eventually execute one model/run pair.
            val plans = applyStreamPolicy(designPoints.map { planFor(it) })

            // Phase 1: launch all design points concurrently on the
            // simulation dispatcher.  Each launch invokes
            // [onDesignPointStart] just before starting the coroutine
            // and registers its Deferred in [activeJobs] so a later
            // [cancelDesignPoint] call can target it.
            val planDeferreds: List<Pair<DesignPointRunPlan, kotlinx.coroutines.Deferred<DesignPointRunOutcome>>> =
                plans.map { plan ->
                    // async() returns immediately; the coroutine body
                    // doesn't run until the dispatcher picks it up.
                    // Registering the Deferred in [activeJobs] before
                    // invoking [onDesignPointStart] closes the cancel
                    // race window — callers reacting to the start
                    // callback can find the Job.
                    val deferred = async(SimulationDispatcher.default) {
                        try {
                            runDesignPoint(plan)
                        } catch (ex: CancellationException) {
                            // Per-point cancel: return a cancelled
                            // outcome to the commit phase.  The outer
                            // [await] catches this case too in case
                            // the coroutine was cancelled before its
                            // body began running (so this catch never
                            // got a chance to fire).
                            if (plan.designPoint.number in cancelledPointIds) {
                                cancelledOutcome(plan)
                            } else {
                                throw ex
                            }
                        }
                    }
                    activeJobs[plan.designPoint.number] = deferred
                    onDesignPointStart?.invoke(plan.designPoint)
                    plan to deferred
                }

            // Per-Deferred try/await — when cancellation happens before
            // the body runs (LAZY-like edge cases or fast cancellation),
            // [await] throws CancellationException directly without the
            // body's catch having a chance to fire.  Translate those
            // into cancelled outcomes here so the commit phase still
            // sees a uniform outcome list.  Parent-scope cancellation
            // is identifiable by [cancelledPointIds] not containing the
            // point; in that case we re-throw to propagate the cancel
            // as expected.
            val outcomes = planDeferreds.map { (plan, deferred) ->
                try {
                    deferred.await()
                } catch (ex: CancellationException) {
                    if (plan.designPoint.number in cancelledPointIds) {
                        cancelledOutcome(plan)
                    } else {
                        throw ex
                    }
                }
            }

            // Phase 2: sequential DB commit + callbacks, cancellation
            // checked between points.  Cancelled outcomes are NOT
            // committed (no EXPERIMENT row, no per-rep observations);
            // they fire [onDesignPointCancelled] first (so callers can
            // distinguish from failures), then [onDesignPointComplete]
            // with snapshot = null for count-driving consumers.
            val writer = SnapshotBatchWriter(kslDb)
            for (outcome in outcomes) {
                ensureActive()
                val designPoint = outcome.plan.designPoint
                val simulationRun = outcome.simulationRun
                val collector = outcome.collector
                var snapshot: SimulationSnapshot.ExperimentCompleted? = null
                if (outcome.wasCancelled) {
                    onDesignPointCancelled?.invoke(designPoint)
                    onDesignPointComplete?.invoke(designPoint, null)
                } else {
                    if (collector != null) {
                        collector.use { c ->
                            val snapshots = c.drain()
                            snapshot = snapshots
                                .filterIsInstance<SimulationSnapshot.ExperimentCompleted>()
                                .firstOrNull()
                            if (snapshots.isNotEmpty()) writer.write(snapshots)
                        }
                    }
                    onDesignPointComplete?.invoke(designPoint, snapshot)
                    if (addRuns) {
                        mySimulationRuns[designPoint] = simulationRun
                    }
                }
                activeJobs.remove(designPoint.number)
            }
        }
    }

    /**
     *  Request cancellation of the design point with the given
     *  1-based [pointId].  If that point's coroutine is currently
     *  running (or queued) it will be cancelled; the resulting
     *  outcome carries `wasCancelled = true` so the commit phase
     *  fires `onDesignPointCancelled` instead of treating it as a
     *  failure, and skips the database write entirely.
     *
     *  Safe to call from any thread.  Returns `true` if a matching
     *  active job was found and cancellation was requested; `false`
     *  if the point was unknown, already completed, or no
     *  `simulate(...)` is currently in flight.
     *
     *  No-op when the point has already completed — there's nothing
     *  to cancel, and the recorded outcome (success / failure) is
     *  preserved.
     */
    @Suppress("unused")
    fun cancelDesignPoint(pointId: Int): Boolean {
        val job = activeJobs[pointId] ?: return false
        cancelledPointIds.add(pointId)
        job.cancel()
        return true
    }

    /**
     *  Build a "cancelled" outcome for a design point.  Always
     *  carries `wasCancelled = true` and a `null` collector — no
     *  snapshot is committed.  The synthesized [SimulationRun] is
     *  there only so the commit-phase callback signature has
     *  something to receive; it is never written to the database.
     */
    private fun cancelledOutcome(plan: DesignPointRunPlan): DesignPointRunOutcome {
        val cancelledRun = SimulationRun(
            modelIdentifier = "",
            experimentRunParameters = plan.runParameters,
            inputs = plan.inputs,
            modelConfiguration = plan.modelConfiguration
        )
        cancelledRun.runErrorMsg = "Design point ${plan.designPoint.number} cancelled."
        return DesignPointRunOutcome(plan, cancelledRun, null, wasCancelled = true)
    }

    private fun effectiveNumRepsPerDesignPoint(numRepsPerDesignPoint: Int?): Int? {
        if (numRepsPerDesignPoint != null) {
            return numRepsPerDesignPoint.takeIf { it > 0 }
        }
        return defaultNumRepsPerDesignPoint.takeIf { it > 0 }
    }

    /**
     * Creates the execution plan for one design point.
     *
     * This mirrors [DesignedExperiment]'s direct design-point semantics: factor
     * settings become numeric inputs, the experiment name is derived from the
     * design-point number, and replication count comes from the design point.
     */
    private fun planFor(designPoint: DesignPoint): DesignPointRunPlan {
        require(designPoint.design == design) {
            "The design point was not associated with this experiment."
        }

        val experimentName = "${baseRunParameters.experimentName}_DP_${designPoint.number}"
        val inputs = mutableMapOf<String, Double>()
        for ((factor, value) in designPoint.settings) {
            inputs[factorSettings[factor]!!] = value
        }

        return DesignPointRunPlan(
            designPoint = designPoint,
            experimentName = experimentName,
            inputs = inputs,
            runParameters = baseRunParameters.copy(
                experimentName = experimentName,
                numberOfReplications = designPoint.numReplications
            ),
            modelConfiguration = modelConfiguration,
            outputDirectoryName = experimentName.replace(" ", "_") + "_OutputDir"
        )
    }

    /**
     * Runs one native design-point plan and returns its in-memory snapshot
     * collector for the ordered commit phase.
     */
    private suspend fun runDesignPoint(plan: DesignPointRunPlan): DesignPointRunOutcome {
        // Per-point dir layout — two modes (see class KDoc for the
        // `useDesignPointOutputDirs` parameter):
        //
        // - true  (default, original behaviour): each design point
        //   gets its own subdir under [pathToOutputDirectory] named
        //   "<expName>_DP_<n>_OutputDir" containing a `kslOutput.txt`
        //   plus any per-point model artifacts.
        // - false: all per-point models share [pathToOutputDirectory];
        //   the diagnostic log is renamed to "kslOutput_DP_<n>.txt"
        //   so concurrent writes don't clash and re-runs overwrite
        //   cleanly.
        val (modelDir, outFileName) = if (useDesignPointOutputDirs) {
            KSLFileUtil.createSubDirectory(pathToOutputDirectory, plan.outputDirectoryName) to
                "kslOutput.txt"
        } else {
            pathToOutputDirectory to "kslOutput_DP_${plan.designPoint.number}.txt"
        }
        var collector: InMemorySnapshotCollector? = null
        var simulationRun: SimulationRun? = null
        try {
            val model = modelBuilder.build(plan.modelConfiguration)
            if (model.modelConfigurationManager != null && plan.modelConfiguration != null) {
                model.configuration = plan.modelConfiguration
            }
            // autoCreateOutFile follows the per-point-subdir flag.
            // In per-point mode each design point has its own folder, so
            // a `kslOutput.txt` inside it is meaningful diagnostic
            // output.  In flat mode the per-point log file would just
            // be noise in the shared analysis directory (the model
            // usually doesn't write to KSL.out, and even when it does
            // the interleaving across concurrent points would be hard
            // to read).  Suppressing the file in flat mode keeps the
            // analysis directory clean — the routing path is still
            // honoured for any explicit writes the model code does
            // through the (lazy) subdirectory properties.
            model.outputDirectory = OutputDirectory(
                modelDir,
                outFileName = outFileName,
                autoCreateOutFile = useDesignPointOutputDirs
            )
            simulationRun = SimulationRun(
                modelIdentifier = model.modelIdentifier,
                experimentRunParameters = plan.runParameters,
                inputs = plan.inputs,
                modelConfiguration = plan.modelConfiguration
            )

            collector = InMemorySnapshotCollector(model.lifeCycleEmitters)
            ConcurrentSimulationRunner(model).simulate(simulationRun)
            return DesignPointRunOutcome(plan, simulationRun, collector)

        } catch (e: CancellationException) {
            collector?.close()
            throw e
        } catch (e: RuntimeException) {
            collector?.close()
            Model.logger.error {
                "ParallelDesignedExperiment: design point '${plan.experimentName}' failed — ${e.message}"
            }
            val failedRun = simulationRun ?: failedDesignPointRun(plan, e)
            if (failedRun.runErrorMsg.isEmpty()) {
                failedRun.runErrorMsg = SimulationRunner.stackTraceAsString(e)
                failedRun.results = emptyMap()
            }
            return DesignPointRunOutcome(plan, failedRun, null)
        }
    }

    private fun failedDesignPointRun(
        plan: DesignPointRunPlan,
        error: RuntimeException
    ): SimulationRun {
        return SimulationRun(
            modelIdentifier = "unavailable:${plan.experimentName}",
            experimentRunParameters = plan.runParameters,
            inputs = plan.inputs,
            modelConfiguration = plan.modelConfiguration
        ).also {
            it.runErrorMsg = SimulationRunner.stackTraceAsString(error)
            it.results = emptyMap()
        }
    }

    private fun applyStreamPolicy(plans: List<DesignPointRunPlan>): List<DesignPointRunPlan> {
        return when (streamPolicy) {
            DesignPointRandomStreamPolicy.INDEPENDENT_RANDOM_STREAMS -> applyIndependentStreamPolicy(plans)
            DesignPointRandomStreamPolicy.COMMON_RANDOM_NUMBERS -> plans.map { plan ->
                plan.copy(
                    runParameters = plan.runParameters.copy(numberOfStreamAdvancesPriorToRunning = 0)
                )
            }
        }
    }

    private fun applyIndependentStreamPolicy(plans: List<DesignPointRunPlan>): List<DesignPointRunPlan> {
        var nextAdvance = myStartingStreamAdvance
        return plans.map { plan ->
            val runParameters = plan.runParameters.copy(
                numberOfStreamAdvancesPriorToRunning = nextAdvance
            )
            nextAdvance += myStreamAdvanceSpacing ?: runParameters.numberOfReplications
            plan.copy(runParameters = runParameters)
        }
    }

    /**
     * Writes the joined design-point and response results to a CSV file.
     */
    @JvmOverloads
    fun resultsToCSV(
        fileName: String = name.replace(" ", "_") + ".csv",
        directory: Path = KSLFileUtil.createSubDirectory(pathToOutputDirectory, "csv"),
        coded: Boolean = false
    ) {
        val df = replicatedDesignPointsWithResponses(coded = coded)
        val out = KSLFileUtil.createPrintWriter(directory.resolve(fileName))
        df.writeCsv(writer = out)
    }
}
