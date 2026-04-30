package ksl.simulation

import ksl.controls.experiments.ConcurrentScenarioRunner
import ksl.controls.experiments.Scenario
import ksl.controls.experiments.ScenarioRunner
import ksl.examples.book.appendixD.GIGcQueue
import ksl.examples.book.chapter4.DriveThroughPharmacyWithQ
import ksl.examples.book.chapter7.StemFairMixerEnhancedSched
import ksl.utilities.io.dbutil.AcrossRepStatTableData
import ksl.utilities.io.dbutil.ControlTableData
import ksl.utilities.io.dbutil.ExperimentTableData
import ksl.utilities.io.dbutil.FrequencyTableData
import ksl.utilities.io.dbutil.HistogramTableData
import ksl.utilities.io.dbutil.KSLDatabase
import ksl.utilities.io.dbutil.ModelElementTableData
import ksl.utilities.io.dbutil.RvParameterTableData
import ksl.utilities.io.dbutil.SimulationRunTableData
import ksl.utilities.io.dbutil.TimeSeriesResponseTableData
import ksl.utilities.io.dbutil.WithinRepCounterStatTableData
import ksl.utilities.io.dbutil.WithinRepStatTableData
import ksl.utilities.random.rvariable.ExponentialRV
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConcurrentScenarioRunnerDatabaseParityTest {

    companion object {
        private const val REPS = 5
        private const val LENGTH = 1000.0
        private const val WARMUP = 200.0
        private const val TIME_SERIES_REPS = 3
        private const val TIME_SERIES_PERIODS = 6
        private const val TIME_SERIES_RESPONSES = 2
        private const val PHARMACY_REPS = 3
        private const val PHARMACY_LENGTH = 2000.0
        private const val PHARMACY_WARMUP = 200.0

        private val SCENARIO_NAMES = listOf("OneServer", "TwoServers", "ThreeServers")
        private val INPUT_SCENARIO_NAMES = listOf("InputOne", "InputTwo", "InputThree")
        private val TIME_SERIES_SCENARIO_NAMES = listOf("StemFairA", "StemFairB")
        private val PHARMACY_SCENARIO_NAMES = listOf("PharmacyA", "PharmacyB")
    }

    private data class ParityRunners(
        val sequential: ScenarioRunner,
        val concurrent: ConcurrentScenarioRunner
    )

    private data class NormalizedExperiment(
        val simName: String,
        val modelName: String,
        val expName: String,
        val numChunks: Int,
        val lengthOfRep: Double?,
        val lengthOfWarmUp: Double?,
        val repAllowedExecTime: Long?,
        val repInitOption: Boolean,
        val resetStartStreamOption: Boolean,
        val antitheticOption: Boolean,
        val advanceNextSubStreamOption: Boolean,
        val numStreamAdvances: Int,
        val gcAfterRepOption: Boolean
    )

    private data class NormalizedSimulationRun(
        val runName: String,
        val numReps: Int,
        val startRepId: Int,
        val lastRepId: Int?,
        val runErrorMsg: String?
    )

    private data class NormalizedModelElement(
        val elementName: String,
        val className: String,
        val parentName: String?,
        val leftCount: Int,
        val rightCount: Int
    )

    private data class NormalizedWithinRepStat(
        val elementName: String,
        val repId: Int,
        val statName: String,
        val statCount: Double?,
        val average: Double?,
        val minimum: Double?,
        val maximum: Double?,
        val weightedSum: Double?,
        val sumOfWeights: Double?,
        val weightedSsq: Double?,
        val lastValue: Double?,
        val lastWeight: Double?
    )

    private data class NormalizedWithinRepCounterStat(
        val elementName: String,
        val repId: Int,
        val statName: String,
        val lastValue: Double?
    )

    private data class NormalizedAcrossRepStat(
        val elementName: String,
        val statName: String,
        val statCount: Double?,
        val average: Double?,
        val stdDev: Double?,
        val stdErr: Double?,
        val halfWidth: Double?,
        val confLevel: Double?,
        val minimum: Double?,
        val maximum: Double?,
        val sumOfObs: Double?,
        val devSsq: Double?,
        val lastValue: Double?,
        val kurtosis: Double?,
        val skewness: Double?,
        val lag1Cov: Double?,
        val lag1Corr: Double?,
        val vonNeumannLag1Stat: Double?,
        val numMissingObs: Double?
    )

    private data class InputMetadataExpectation(
        val scenarioName: String,
        val modelName: String,
        val numServers: Int,
        val arrivalMean: Double,
        val serviceMean: Double
    )

    private data class NormalizedControl(
        val elementName: String,
        val keyName: String,
        val controlValue: Double?,
        val lowerBound: Double?,
        val upperBound: Double?,
        val propertyName: String,
        val controlType: String,
        val comment: String?
    )

    private data class NormalizedRvParameter(
        val elementName: String,
        val className: String,
        val dataType: String,
        val rvName: String,
        val paramName: String,
        val paramValue: Double
    )

    private data class TimeSeriesScenarioSpec(
        val scenarioName: String,
        val modelName: String,
        val mixerName: String,
        val warningTime: Double
    )

    private data class NormalizedTimeSeriesResponse(
        val elementName: String,
        val repId: Int,
        val statName: String,
        val period: Int,
        val startTime: Double?,
        val endTime: Double?,
        val length: Double?,
        val value: Double?
    )

    private data class PharmacyDistributionSpec(
        val scenarioName: String,
        val modelName: String,
        val pharmacyName: String,
        val numPharmacists: Int,
        val arrivalMean: Double,
        val serviceMean: Double,
        val arrivalStream: Int,
        val serviceStream: Int
    )

    private data class NormalizedHistogram(
        val histogramElementName: String,
        val responseElementName: String,
        val responseName: String,
        val binLabel: String,
        val binNum: Int,
        val binLowerLimit: Double?,
        val binUpperLimit: Double?,
        val binCount: Double?,
        val binCumCount: Double?,
        val binProportion: Double?,
        val binCumProportion: Double?
    )

    private data class NormalizedFrequency(
        val elementName: String,
        val name: String,
        val cellLabel: String,
        val value: Int,
        val count: Double?,
        val cumCount: Double?,
        val proportion: Double?,
        val cumProportion: Double?
    )

    private fun buildQueueScenario(
        scenarioName: String,
        modelName: String,
        numServers: Int,
        arrivalStream: Int,
        serviceStream: Int,
        inputs: Map<String, Double> = emptyMap()
    ): Scenario {
        val builder = object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ExperimentRunParametersIfc?
            ): Model {
                val model = Model(modelName, autoCSVReports = false)
                GIGcQueue(
                    model,
                    numServers = numServers,
                    ad = ExponentialRV(1.0, arrivalStream),
                    sd = ExponentialRV(0.5, serviceStream),
                    name = "MM1Q"
                )
                return model
            }
        }

        return Scenario(
            modelBuilder = builder,
            name = scenarioName,
            inputs = inputs,
            numberReplications = REPS,
            lengthOfReplication = LENGTH,
            lengthOfReplicationWarmUp = WARMUP
        )
    }

    private fun buildThreeQueueScenarios(): List<Scenario> {
        return listOf(
            buildQueueScenario("OneServer", "DBParity_1S", 1, 41, 42),
            buildQueueScenario("TwoServers", "DBParity_2S", 2, 43, 44),
            buildQueueScenario("ThreeServers", "DBParity_3S", 3, 45, 46)
        )
    }

    private fun runParityRunners(): ParityRunners {
        val sequential = ScenarioRunner(
            "SequentialDatabaseParity_${System.nanoTime()}",
            buildThreeQueueScenarios()
        )
        sequential.simulate()

        val concurrent = ConcurrentScenarioRunner(
            "ConcurrentDatabaseParity_${System.nanoTime()}",
            buildThreeQueueScenarios()
        )
        runBlocking { concurrent.simulate() }

        return ParityRunners(sequential, concurrent)
    }

    private fun inputMetadataExpectations(): List<InputMetadataExpectation> {
        return listOf(
            InputMetadataExpectation("InputOne", "DBParityInputs_1S", 1, 1.10, 0.45),
            InputMetadataExpectation("InputTwo", "DBParityInputs_2S", 2, 1.20, 0.50),
            InputMetadataExpectation("InputThree", "DBParityInputs_3S", 3, 1.30, 0.55)
        )
    }

    private fun buildInputMetadataQueueScenarios(): List<Scenario> {
        return inputMetadataExpectations().mapIndexed { index, spec ->
            buildQueueScenario(
                scenarioName = spec.scenarioName,
                modelName = spec.modelName,
                numServers = 1,
                arrivalStream = 51 + 2 * index,
                serviceStream = 52 + 2 * index,
                inputs = mapOf(
                    "MM1Q.numServers" to spec.numServers.toDouble(),
                    "${spec.modelName}:TBA.mean" to spec.arrivalMean,
                    "${spec.modelName}:ServiceTime.mean" to spec.serviceMean
                )
            )
        }
    }

    private fun runInputMetadataParityRunners(): ParityRunners {
        val sequential = ScenarioRunner(
            "SequentialInputMetadataParity_${System.nanoTime()}",
            buildInputMetadataQueueScenarios()
        )
        sequential.simulate()

        val concurrent = ConcurrentScenarioRunner(
            "ConcurrentInputMetadataParity_${System.nanoTime()}",
            buildInputMetadataQueueScenarios()
        )
        runBlocking { concurrent.simulate() }

        return ParityRunners(sequential, concurrent)
    }

    private fun timeSeriesScenarioSpecs(): List<TimeSeriesScenarioSpec> {
        return listOf(
            TimeSeriesScenarioSpec("StemFairA", "DBParityStemFair_A", "SFS_A", 30.0),
            TimeSeriesScenarioSpec("StemFairB", "DBParityStemFair_B", "SFS_B", 45.0)
        )
    }

    private fun buildStemFairTimeSeriesScenarios(): List<Scenario> {
        return timeSeriesScenarioSpecs().map { spec ->
            val builder = object : ModelBuilderIfc {
                override fun build(
                    modelConfiguration: Map<String, String>?,
                    experimentRunParameters: ExperimentRunParametersIfc?
                ): Model {
                    val model = Model(spec.modelName, autoCSVReports = false)
                    val mixer = StemFairMixerEnhancedSched(model, spec.mixerName)
                    mixer.warningTime = spec.warningTime
                    mixer.timeSeriesResponse.acrossRepStatisticsOption = true
                    return model
                }
            }

            Scenario(
                modelBuilder = builder,
                name = spec.scenarioName,
                numberReplications = TIME_SERIES_REPS,
                lengthOfReplication = Double.POSITIVE_INFINITY,
                lengthOfReplicationWarmUp = 0.0
            )
        }
    }

    private fun runTimeSeriesParityRunners(): ParityRunners {
        val sequential = ScenarioRunner(
            "SequentialTimeSeriesParity_${System.nanoTime()}",
            buildStemFairTimeSeriesScenarios()
        )
        sequential.simulate()

        val concurrent = ConcurrentScenarioRunner(
            "ConcurrentTimeSeriesParity_${System.nanoTime()}",
            buildStemFairTimeSeriesScenarios()
        )
        runBlocking { concurrent.simulate() }

        return ParityRunners(sequential, concurrent)
    }

    private fun pharmacyDistributionSpecs(): List<PharmacyDistributionSpec> {
        return listOf(
            PharmacyDistributionSpec("PharmacyA", "DBParityPharmacy_A", "Pharmacy_A", 1, 6.0, 3.0, 61, 62),
            PharmacyDistributionSpec("PharmacyB", "DBParityPharmacy_B", "Pharmacy_B", 2, 5.0, 2.5, 63, 64)
        )
    }

    private fun buildPharmacyDistributionScenarios(): List<Scenario> {
        return pharmacyDistributionSpecs().map { spec ->
            val builder = object : ModelBuilderIfc {
                override fun build(
                    modelConfiguration: Map<String, String>?,
                    experimentRunParameters: ExperimentRunParametersIfc?
                ): Model {
                    val model = Model(spec.modelName, autoCSVReports = false)
                    val pharmacy = DriveThroughPharmacyWithQ(
                        parent = model,
                        numServers = spec.numPharmacists,
                        name = spec.pharmacyName
                    )
                    pharmacy.arrivalGenerator.setInitialEventTimeProcesses(
                        ExponentialRV(spec.arrivalMean, spec.arrivalStream)
                    )
                    pharmacy.serviceRV.initialRandomSource =
                        ExponentialRV(spec.serviceMean, spec.serviceStream)
                    return model
                }
            }

            Scenario(
                modelBuilder = builder,
                name = spec.scenarioName,
                numberReplications = PHARMACY_REPS,
                lengthOfReplication = PHARMACY_LENGTH,
                lengthOfReplicationWarmUp = PHARMACY_WARMUP
            )
        }
    }

    private fun runPharmacyDistributionParityRunners(): ParityRunners {
        val sequential = ScenarioRunner(
            "SequentialDistributionParity_${System.nanoTime()}",
            buildPharmacyDistributionScenarios()
        )
        sequential.simulate()

        val concurrent = ConcurrentScenarioRunner(
            "ConcurrentDistributionParity_${System.nanoTime()}",
            buildPharmacyDistributionScenarios()
        )
        runBlocking { concurrent.simulate() }

        return ParityRunners(sequential, concurrent)
    }

    @Test
    fun metadataTablesMatchSequentialDatabaseObserver() {
        val runners = runParityRunners()

        for (scenarioName in SCENARIO_NAMES) {
            assertEquals(
                normalizedExperiment(runners.sequential.kslDb, scenarioName),
                normalizedExperiment(runners.concurrent.kslDb, scenarioName),
                "experiment table must match for '$scenarioName'"
            )

            assertEquals(
                normalizedSimulationRuns(runners.sequential.kslDb, scenarioName),
                normalizedSimulationRuns(runners.concurrent.kslDb, scenarioName),
                "simulation_run table must match for '$scenarioName'"
            )

            assertEquals(
                normalizedModelElements(runners.sequential.kslDb, scenarioName),
                normalizedModelElements(runners.concurrent.kslDb, scenarioName),
                "model_element table must match for '$scenarioName'"
            )
        }
    }

    @Test
    fun statisticTablesMatchSequentialDatabaseObserver() {
        val runners = runParityRunners()

        for (scenarioName in SCENARIO_NAMES) {
            assertEquals(
                normalizedWithinRepStats(runners.sequential.kslDb, scenarioName),
                normalizedWithinRepStats(runners.concurrent.kslDb, scenarioName),
                "within_rep_stat table must match for '$scenarioName'"
            )

            assertEquals(
                normalizedWithinRepCounterStats(runners.sequential.kslDb, scenarioName),
                normalizedWithinRepCounterStats(runners.concurrent.kslDb, scenarioName),
                "within_rep_counter_stat table must match for '$scenarioName'"
            )

            assertEquals(
                normalizedAcrossRepStats(runners.sequential.kslDb, scenarioName),
                normalizedAcrossRepStats(runners.concurrent.kslDb, scenarioName),
                "across_rep_stat table must match for '$scenarioName'"
            )
        }
    }

    @Test
    fun inputMetadataTablesMatchSequentialDatabaseObserver() {
        val runners = runInputMetadataParityRunners()

        assertEquals(
            INPUT_SCENARIO_NAMES.toSet(),
            runners.concurrent.kslDb.experimentNames.toSet(),
            "Concurrent DB must contain all input-metadata scenarios"
        )

        for (spec in inputMetadataExpectations()) {
            val sequentialControls = normalizedControls(runners.sequential.kslDb, spec.scenarioName)
            val concurrentControls = normalizedControls(runners.concurrent.kslDb, spec.scenarioName)

            val controlValues = sequentialControls.associate { it.keyName to it.controlValue }
            assertEquals(
                true,
                "MM1Q.numServers" in controlValues,
                "Sequential control metadata must include the applied numServers control"
            )
            assertEquals(
                spec.numServers.toDouble(),
                controlValues.getValue("MM1Q.numServers") ?: Double.NaN,
                1.0e-10,
                "Sequential control metadata must reflect the applied numServers input"
            )
            assertEquals(
                sequentialControls,
                concurrentControls,
                "control table must match for '${spec.scenarioName}'"
            )

            val sequentialRvParameters = normalizedRvParameters(runners.sequential.kslDb, spec.scenarioName)
            val concurrentRvParameters = normalizedRvParameters(runners.concurrent.kslDb, spec.scenarioName)

            val rvParameterValues = sequentialRvParameters.associate { (it.rvName to it.paramName) to it.paramValue }
            assertEquals(
                spec.arrivalMean,
                rvParameterValues.getValue("${spec.modelName}:TBA" to "mean"),
                1.0e-10,
                "Sequential RV parameter metadata must reflect the applied arrival mean input"
            )
            assertEquals(
                spec.serviceMean,
                rvParameterValues.getValue("${spec.modelName}:ServiceTime" to "mean"),
                1.0e-10,
                "Sequential RV parameter metadata must reflect the applied service mean input"
            )
            assertEquals(
                sequentialRvParameters,
                concurrentRvParameters,
                "rv_parameter table must match for '${spec.scenarioName}'"
            )
        }
    }

    @Test
    fun timeSeriesResponseTableMatchesSequentialDatabaseObserver() {
        val runners = runTimeSeriesParityRunners()

        assertEquals(
            TIME_SERIES_SCENARIO_NAMES.toSet(),
            runners.concurrent.kslDb.experimentNames.toSet(),
            "Concurrent DB must contain all time-series scenarios"
        )

        val expectedRows = TIME_SERIES_REPS * TIME_SERIES_PERIODS * TIME_SERIES_RESPONSES

        for (scenarioName in TIME_SERIES_SCENARIO_NAMES) {
            val sequentialRows = normalizedTimeSeriesResponses(runners.sequential.kslDb, scenarioName)
            val concurrentRows = normalizedTimeSeriesResponses(runners.concurrent.kslDb, scenarioName)

            assertEquals(
                expectedRows,
                sequentialRows.size,
                "Expected $expectedRows time-series rows for '$scenarioName'"
            )
            assertEquals(
                sequentialRows,
                concurrentRows,
                "time_series_response table must match for '$scenarioName'"
            )
        }
    }

    @Test
    fun histogramAndFrequencyTablesMatchSequentialDatabaseObserver() {
        val runners = runPharmacyDistributionParityRunners()

        assertEquals(
            PHARMACY_SCENARIO_NAMES.toSet(),
            runners.concurrent.kslDb.experimentNames.toSet(),
            "Concurrent DB must contain all pharmacy distribution scenarios"
        )

        for (scenarioName in PHARMACY_SCENARIO_NAMES) {
            val sequentialHistograms = normalizedHistograms(runners.sequential.kslDb, scenarioName)
            val concurrentHistograms = normalizedHistograms(runners.concurrent.kslDb, scenarioName)

            assertFalse(
                sequentialHistograms.isEmpty(),
                "Expected histogram rows for '$scenarioName'"
            )
            assertTrue(
                sequentialHistograms.sumOf { it.binCount ?: 0.0 } > 0.0,
                "Histogram rows for '$scenarioName' must contain observed counts"
            )
            assertEquals(
                sequentialHistograms,
                concurrentHistograms,
                "histogram table must match for '$scenarioName'"
            )

            val sequentialFrequencies = normalizedFrequencies(runners.sequential.kslDb, scenarioName)
            val concurrentFrequencies = normalizedFrequencies(runners.concurrent.kslDb, scenarioName)

            assertFalse(
                sequentialFrequencies.isEmpty(),
                "Expected frequency rows for '$scenarioName'"
            )
            assertTrue(
                sequentialFrequencies.sumOf { it.count ?: 0.0 } > 0.0,
                "Frequency rows for '$scenarioName' must contain observed counts"
            )
            assertEquals(
                sequentialFrequencies,
                concurrentFrequencies,
                "frequency table must match for '$scenarioName'"
            )
        }
    }

    private fun normalizedExperiment(
        db: KSLDatabase,
        scenarioName: String
    ): NormalizedExperiment {
        val row = requireNotNull(db.fetchExperimentData(scenarioName)) {
            "Expected experiment '$scenarioName'"
        }
        return row.normalized()
    }

    private fun normalizedSimulationRuns(
        db: KSLDatabase,
        scenarioName: String
    ): List<NormalizedSimulationRun> {
        return db.simulationRunDataFor(scenarioName)
            .map { it.normalized() }
            .sortedWith(compareBy({ it.runName }, { it.startRepId }))
    }

    private fun normalizedModelElements(
        db: KSLDatabase,
        scenarioName: String
    ): List<NormalizedModelElement> {
        return db.modelElementDataFor(scenarioName)
            .map { it.normalized() }
            .sortedWith(compareBy({ it.elementName }, { it.className }, { it.parentName ?: "" }))
    }

    private fun normalizedWithinRepStats(
        db: KSLDatabase,
        scenarioName: String
    ): List<NormalizedWithinRepStat> {
        val elementNames = elementNamesById(db, scenarioName)
        return db.withinRepStatDataFor(scenarioName)
            .map { it.normalized(elementNames) }
            .sortedWith(compareBy({ it.statName }, { it.repId }, { it.elementName }))
    }

    private fun normalizedWithinRepCounterStats(
        db: KSLDatabase,
        scenarioName: String
    ): List<NormalizedWithinRepCounterStat> {
        val elementNames = elementNamesById(db, scenarioName)
        return db.withinRepCounterStatDataFor(scenarioName)
            .map { it.normalized(elementNames) }
            .sortedWith(compareBy({ it.statName }, { it.repId }, { it.elementName }))
    }

    private fun normalizedAcrossRepStats(
        db: KSLDatabase,
        scenarioName: String
    ): List<NormalizedAcrossRepStat> {
        val elementNames = elementNamesById(db, scenarioName)
        return db.acrossRepStatDataFor(scenarioName)
            .map { it.normalized(elementNames) }
            .sortedWith(compareBy({ it.statName }, { it.elementName }))
    }

    private fun normalizedControls(
        db: KSLDatabase,
        scenarioName: String
    ): List<NormalizedControl> {
        val elementNames = elementNamesById(db, scenarioName)
        return db.controlDataFor(scenarioName)
            .map { it.normalized(elementNames) }
            .sortedWith(compareBy({ it.keyName }, { it.elementName }, { it.propertyName }))
    }

    private fun normalizedRvParameters(
        db: KSLDatabase,
        scenarioName: String
    ): List<NormalizedRvParameter> {
        val elementNames = elementNamesById(db, scenarioName)
        return db.rvParameterDataFor(scenarioName)
            .map { it.normalized(elementNames) }
            .sortedWith(compareBy({ it.rvName }, { it.paramName }, { it.elementName }))
    }

    private fun normalizedTimeSeriesResponses(
        db: KSLDatabase,
        scenarioName: String
    ): List<NormalizedTimeSeriesResponse> {
        val elementNames = elementNamesById(db, scenarioName)
        return db.timeSeriesResponseDataFor(scenarioName)
            .map { it.normalized(elementNames) }
            .sortedWith(compareBy({ it.statName }, { it.repId }, { it.period }, { it.elementName }))
    }

    private fun normalizedHistograms(
        db: KSLDatabase,
        scenarioName: String
    ): List<NormalizedHistogram> {
        val elementNames = elementNamesById(db, scenarioName)
        return db.histogramDataFor(scenarioName)
            .map { it.normalized(elementNames) }
            .sortedWith(compareBy({ it.responseName }, { it.binNum }, { it.histogramElementName }))
    }

    private fun normalizedFrequencies(
        db: KSLDatabase,
        scenarioName: String
    ): List<NormalizedFrequency> {
        val elementNames = elementNamesById(db, scenarioName)
        return db.frequencyDataFor(scenarioName)
            .map { it.normalized(elementNames) }
            .sortedWith(compareBy({ it.name }, { it.value }, { it.cellLabel }, { it.elementName }))
    }

    private fun elementNamesById(
        db: KSLDatabase,
        scenarioName: String
    ): Map<Int, String> {
        return db.modelElementDataFor(scenarioName)
            .associate { it.element_id to it.element_name }
    }

    private fun ExperimentTableData.normalized() = NormalizedExperiment(
        simName = sim_name,
        modelName = model_name,
        expName = exp_name,
        numChunks = num_chunks,
        lengthOfRep = length_of_rep,
        lengthOfWarmUp = length_of_warm_up,
        repAllowedExecTime = rep_allowed_exec_time,
        repInitOption = rep_init_option,
        resetStartStreamOption = reset_start_stream_option,
        antitheticOption = antithetic_option,
        advanceNextSubStreamOption = adv_next_sub_stream_option,
        numStreamAdvances = num_stream_advances,
        gcAfterRepOption = gc_after_rep_option
    )

    private fun SimulationRunTableData.normalized() = NormalizedSimulationRun(
        runName = run_name,
        numReps = num_reps,
        startRepId = start_rep_id,
        lastRepId = last_rep_id,
        runErrorMsg = run_error_msg
    )

    private fun ModelElementTableData.normalized() = NormalizedModelElement(
        elementName = element_name,
        className = class_name,
        parentName = parent_name,
        leftCount = left_count,
        rightCount = right_count
    )

    private fun WithinRepStatTableData.normalized(
        elementNames: Map<Int, String>
    ) = NormalizedWithinRepStat(
        elementName = elementNames.getValue(element_id_fk),
        repId = rep_id,
        statName = stat_name,
        statCount = stat_count,
        average = average,
        minimum = minimum,
        maximum = maximum,
        weightedSum = weighted_sum,
        sumOfWeights = sum_of_weights,
        weightedSsq = weighted_ssq,
        lastValue = last_value,
        lastWeight = last_weight
    )

    private fun WithinRepCounterStatTableData.normalized(
        elementNames: Map<Int, String>
    ) = NormalizedWithinRepCounterStat(
        elementName = elementNames.getValue(element_id_fk),
        repId = rep_id,
        statName = stat_name,
        lastValue = last_value
    )

    private fun AcrossRepStatTableData.normalized(
        elementNames: Map<Int, String>
    ) = NormalizedAcrossRepStat(
        elementName = elementNames.getValue(element_id_fk),
        statName = stat_name,
        statCount = stat_count,
        average = average,
        stdDev = std_dev,
        stdErr = std_err,
        halfWidth = half_width,
        confLevel = conf_level,
        minimum = minimum,
        maximum = maximum,
        sumOfObs = sum_of_obs,
        devSsq = dev_ssq,
        lastValue = last_value,
        kurtosis = kurtosis,
        skewness = skewness,
        lag1Cov = lag1_cov,
        lag1Corr = lag1_corr,
        vonNeumannLag1Stat = von_neumann_lag1_stat,
        numMissingObs = num_missing_obs
    )

    private fun ControlTableData.normalized(
        elementNames: Map<Int, String>
    ) = NormalizedControl(
        elementName = elementNames.getValue(element_id_fk),
        keyName = key_name,
        controlValue = control_value,
        lowerBound = lower_bound,
        upperBound = upper_bound,
        propertyName = property_name,
        controlType = control_type,
        comment = comment
    )

    private fun RvParameterTableData.normalized(
        elementNames: Map<Int, String>
    ) = NormalizedRvParameter(
        elementName = elementNames.getValue(element_id_fk),
        className = class_name,
        dataType = data_type,
        rvName = rv_name,
        paramName = param_name,
        paramValue = param_value
    )

    private fun TimeSeriesResponseTableData.normalized(
        elementNames: Map<Int, String>
    ) = NormalizedTimeSeriesResponse(
        elementName = elementNames.getValue(element_id_fk),
        repId = rep_id,
        statName = stat_name,
        period = period,
        startTime = start_time,
        endTime = end_time,
        length = length,
        value = value
    )

    private fun HistogramTableData.normalized(
        elementNames: Map<Int, String>
    ) = NormalizedHistogram(
        histogramElementName = elementNames.getValue(element_id_fk),
        responseElementName = elementNames.getValue(response_id_fk),
        responseName = response_name,
        binLabel = bin_label,
        binNum = bin_num,
        binLowerLimit = bin_lower_limit,
        binUpperLimit = bin_upper_limit,
        binCount = bin_count,
        binCumCount = bin_cum_count,
        binProportion = bin_proportion,
        binCumProportion = bin_cum_proportion
    )

    private fun FrequencyTableData.normalized(
        elementNames: Map<Int, String>
    ) = NormalizedFrequency(
        elementName = elementNames.getValue(element_id_fk),
        name = name,
        cellLabel = cell_label,
        value = value,
        count = count,
        cumCount = cum_count,
        proportion = proportion,
        cumProportion = cum_proportion
    )
}
