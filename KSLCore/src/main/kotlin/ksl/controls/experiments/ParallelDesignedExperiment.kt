package ksl.controls.experiments

import kotlinx.coroutines.runBlocking
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.utilities.Identity
import ksl.utilities.KSLArrays
import ksl.utilities.io.KSL
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.io.addColumnsFor
import ksl.utilities.io.dbutil.KSLDatabase
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
 * Unlike [DesignedExperiment], this class requires a [ModelBuilderIfc] or model-creator
 * function so that each design point can run on a fresh [Model] instance. Execution is
 * delegated to [ConcurrentScenarioRunner], preserving isolated model state, in-memory
 * snapshot collection, and sequential database commit behavior.
 *
 * By default, design points use [DesignPointRandomStreamPolicy.INDEPENDENT_RANDOM_STREAMS].
 * This matches legacy [DesignedExperiment] behavior, where one reused model naturally
 * advances to the next stream block as design points are simulated sequentially. Call
 * [useCommonRandomNumbers] to make fresh model instances start from the same stream block.
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
    val design: ExperimentalDesignIfc,
    val modelConfiguration: Map<String, String>? = null,
    val pathToOutputDirectory: Path = KSL.createSubDirectory(name.replace(" ", "_") + "_OutputDir"),
    val kslDb: KSLDatabase = KSLDatabase("${name}.db".replace(" ", "_"), pathToOutputDirectory)
) : Identity(name) {

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
    val simulationRuns: List<SimulationRun>
        get() = mySimulationRuns.values.toList()

    /**
     * The number of design points executed in the base design.
     */
    val numSimulationRuns: Int
        get() = mySimulationRuns.size

    /**
     * The names of the responses or counters in the template model.
     */
    val responseNames: List<String>
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
    fun observationsAsMap(responseName: String): Map<String, DoubleArray> {
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
        linearModel: LinearModel,
        coded: Boolean = true
    ): RegressionResultsIfc {
        val rd = regressionData(responseName, linearModel, coded)
        return OLSRegression(rd)
    }

    /**
     * Simulates all design points concurrently.
     */
    @JvmOverloads
    fun simulateAll(
        numRepsPerDesignPoint: Int? = null,
        clearRuns: Boolean = true,
        addRuns: Boolean = true,
        clearAllData: Boolean = true
    ) {
        simulate(design.iterator(), numRepsPerDesignPoint, clearRuns, addRuns, clearAllData)
    }

    /**
     * Simulates the design points presented by [iterator] concurrently.
     */
    @JvmOverloads
    fun simulate(
        iterator: Iterator<DesignPoint>,
        numRepsPerDesignPoint: Int? = null,
        clearRuns: Boolean = true,
        addRuns: Boolean = true,
        clearAllData: Boolean = true
    ) {
        if (clearRuns) {
            clearSimulationRuns()
        }
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

        val effectiveNumReps = effectiveNumRepsPerDesignPoint(numRepsPerDesignPoint)
        val scenarios = designPoints.map { designPoint ->
            require(designPoint.design == design) {
                "The design point was not associated with this experiment."
            }
            if (effectiveNumReps != null) {
                designPoint.numReplications = effectiveNumReps
            }
            scenarioFor(designPoint)
        }

        applyStreamPolicy(scenarios)

        val runner = ConcurrentScenarioRunner(name, scenarios, pathToOutputDirectory, kslDb)
        runBlocking { runner.simulate(clearAllData = clearAllData) }

        if (addRuns) {
            for ((index, designPoint) in designPoints.withIndex()) {
                scenarios[index].simulationRun?.let { mySimulationRuns[designPoint] = it }
            }
        }
    }

    private fun effectiveNumRepsPerDesignPoint(numRepsPerDesignPoint: Int?): Int? {
        if (numRepsPerDesignPoint != null) {
            return numRepsPerDesignPoint.takeIf { it > 0 }
        }
        return defaultNumRepsPerDesignPoint.takeIf { it > 0 }
    }

    private fun scenarioFor(designPoint: DesignPoint): Scenario {
        val expName = "${baseRunParameters.experimentName}_DP_${designPoint.number}"
        val inputs = mutableMapOf<String, Double>()
        for ((factor, value) in designPoint.settings) {
            inputs[factorSettings[factor]!!] = value
        }

        return Scenario(
            modelBuilder = modelBuilder,
            name = expName,
            inputs = inputs,
            runParameters = baseRunParameters.copy(
                experimentName = expName,
                numberOfReplications = designPoint.numReplications
            ),
            modelConfiguration = modelConfiguration
        )
    }

    private fun applyStreamPolicy(scenarios: List<Scenario>) {
        when (streamPolicy) {
            DesignPointRandomStreamPolicy.INDEPENDENT_RANDOM_STREAMS -> {
                scenarios.assignIndependentStreamAdvances(
                    startingStreamAdvance = myStartingStreamAdvance,
                    streamAdvanceSpacing = myStreamAdvanceSpacing
                )
            }
            DesignPointRandomStreamPolicy.COMMON_RANDOM_NUMBERS -> {
                scenarios.forEach { it.useCommonRandomNumbers() }
            }
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
