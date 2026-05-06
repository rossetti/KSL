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
class ParallelDesignedExperiment @JvmOverloads constructor(
    name: String,
    private val modelBuilder: ModelBuilderIfc,
    private val factorSettings: Map<Factor, String>,
    override val design: ExperimentalDesignIfc,
    val modelConfiguration: Map<String, String>? = null,
    val pathToOutputDirectory: Path = KSL.createSubDirectory(name.replace(" ", "_") + "_OutputDir"),
    val kslDb: KSLDatabase = KSLDatabase("${name}.db".replace(" ", "_"), pathToOutputDirectory)
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
        val collector: InMemorySnapshotCollector?
    )

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

    private val templateModel: Model = modelBuilder.build(modelConfiguration)
    private val baseRunParameters: ExperimentRunParameters = templateModel.extractRunParameters()
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
        onDesignPointComplete: ((designPoint: DesignPoint, snapshot: SimulationSnapshot.ExperimentCompleted?) -> Unit)? = null
    ) {
        simulate(design.iterator(), numRepsPerDesignPoint, clearRuns, addRuns, clearAllData, onDesignPointComplete)
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
        onDesignPointComplete: ((designPoint: DesignPoint, snapshot: SimulationSnapshot.ExperimentCompleted?) -> Unit)? = null
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

        coroutineScope {
            if (clearAllData) kslDb.clearAllData()

            val effectiveNumReps = effectiveNumRepsPerDesignPoint(numRepsPerDesignPoint)
            if (effectiveNumReps != null) {
                designPoints.forEach { it.numReplications = effectiveNumReps }
            }

            // Build native design-point plans.  A design point is not a
            // Scenario, even though both eventually execute one model/run pair.
            val plans = applyStreamPolicy(designPoints.map { planFor(it) })

            // Phase 1: launch all design points concurrently on the simulation dispatcher
            val jobs = plans.map { plan -> async(SimulationDispatcher.default) { runDesignPoint(plan) } }
            val outcomes = jobs.awaitAll()

            // Phase 2: sequential DB commit + callback, cancellation checked between points
            val writer = SnapshotBatchWriter(kslDb)
            for (outcome in outcomes) {
                ensureActive()
                val designPoint = outcome.plan.designPoint
                val simulationRun = outcome.simulationRun
                val collector = outcome.collector
                var snapshot: SimulationSnapshot.ExperimentCompleted? = null
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
        }
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
        val modelDir = KSLFileUtil.createSubDirectory(pathToOutputDirectory, plan.outputDirectoryName)
        var collector: InMemorySnapshotCollector? = null
        var simulationRun: SimulationRun? = null
        try {
            val model = modelBuilder.build(plan.modelConfiguration)
            if (model.modelConfigurationManager != null && plan.modelConfiguration != null) {
                model.configuration = plan.modelConfiguration
            }
            model.outputDirectory = OutputDirectory(modelDir, outFileName = "kslOutput.txt")
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
