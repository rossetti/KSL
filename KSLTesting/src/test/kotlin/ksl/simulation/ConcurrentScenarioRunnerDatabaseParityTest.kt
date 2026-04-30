package ksl.simulation

import ksl.controls.experiments.ConcurrentScenarioRunner
import ksl.controls.experiments.Scenario
import ksl.controls.experiments.ScenarioRunner
import ksl.examples.book.appendixD.GIGcQueue
import ksl.utilities.io.dbutil.AcrossRepStatTableData
import ksl.utilities.io.dbutil.ExperimentTableData
import ksl.utilities.io.dbutil.KSLDatabase
import ksl.utilities.io.dbutil.ModelElementTableData
import ksl.utilities.io.dbutil.SimulationRunTableData
import ksl.utilities.io.dbutil.WithinRepCounterStatTableData
import ksl.utilities.io.dbutil.WithinRepStatTableData
import ksl.utilities.random.rvariable.ExponentialRV
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConcurrentScenarioRunnerDatabaseParityTest {

    companion object {
        private const val REPS = 5
        private const val LENGTH = 1000.0
        private const val WARMUP = 200.0

        private val SCENARIO_NAMES = listOf("OneServer", "TwoServers", "ThreeServers")
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

    private fun buildQueueScenario(
        scenarioName: String,
        modelName: String,
        numServers: Int,
        arrivalStream: Int,
        serviceStream: Int
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
}
