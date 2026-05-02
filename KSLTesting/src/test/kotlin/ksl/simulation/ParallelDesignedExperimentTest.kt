package ksl.simulation

import ksl.controls.experiments.DesignPointRandomStreamPolicy
import ksl.controls.experiments.DesignedExperiment
import ksl.controls.experiments.ExperimentalDesignIfc
import ksl.controls.experiments.Factor
import ksl.controls.experiments.ParallelDesignedExperiment
import ksl.controls.experiments.TwoLevelFactor
import ksl.controls.experiments.TwoLevelFactorialDesign
import ksl.examples.book.appendixD.GIGcQueue
import ksl.utilities.random.rvariable.ExponentialRV
import org.jetbrains.kotlinx.dataframe.api.*
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParallelDesignedExperimentTest {

    companion object {
        private const val SERVER_LOW = 1.0
        private const val SERVER_HIGH = 2.0
        private const val ST_LOW = 0.5
        private const val ST_HIGH = 0.8
        private const val REPS_PER_POINT = 3
        private const val LENGTH = 1000.0
        private const val WARMUP = 200.0
        private const val ARRIVAL_STREAM = 1
        private const val SERVICE_STREAM = 2
    }

    private data class DoeSetup(
        val modelName: String,
        val serviceKey: String,
        val factorSettings: Map<Factor, String>,
        val design: ExperimentalDesignIfc
    )

    private fun buildModel(modelName: String, length: Double = LENGTH, warmUp: Double = WARMUP): Model {
        val model = Model(modelName, autoCSVReports = false)
        model.lengthOfReplication = length
        model.lengthOfReplicationWarmUp = warmUp
        GIGcQueue(
            model,
            numServers = 1,
            ad = ExponentialRV(1.0, ARRIVAL_STREAM),
            sd = ExponentialRV(0.5, SERVICE_STREAM),
            name = "MM1Q"
        )
        return model
    }

    private fun modelBuilder(modelName: String, length: Double = LENGTH, warmUp: Double = WARMUP): ModelBuilderIfc {
        return object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ExperimentRunParametersIfc?
            ): Model = buildModel(modelName, length, warmUp)
        }
    }

    private fun buildDoeSetup(modelName: String = "PDE_${System.nanoTime()}"): DoeSetup {
        val fServer = TwoLevelFactor("Server", SERVER_LOW, SERVER_HIGH)
        val fST = TwoLevelFactor("MeanST", ST_LOW, ST_HIGH)
        val design = TwoLevelFactorialDesign(setOf(fServer, fST))
        val serviceKey = "$modelName:ServiceTime.mean"
        val factors = mapOf<Factor, String>(
            fServer to "MM1Q.numServers",
            fST to serviceKey
        )
        return DoeSetup(modelName, serviceKey, factors, design)
    }

    private fun buildParallelDesignedExperiment(
        setup: DoeSetup = buildDoeSetup(),
        length: Double = LENGTH,
        warmUp: Double = WARMUP
    ): ParallelDesignedExperiment {
        return ParallelDesignedExperiment(
            name = "ParallelDesignedExperiment_${System.nanoTime()}",
            modelBuilder = modelBuilder(setup.modelName, length, warmUp),
            factorSettings = setup.factorSettings,
            design = setup.design
        )
    }

    private fun inputKey(run: ksl.controls.experiments.SimulationRun, setup: DoeSetup): Pair<Double, Double> {
        return run.inputs.getValue("MM1Q.numServers") to run.inputs.getValue(setup.serviceKey)
    }

    @Test
    fun parallelDesignedExperimentMatchesDesignedExperimentWithDefaultIndependentStreams() {
        val setup = buildDoeSetup("PDE_Parity_${System.nanoTime()}")
        val sequentialModel = buildModel(setup.modelName)
        val sequential = DesignedExperiment(
            "SequentialDOEParity_${System.nanoTime()}",
            sequentialModel,
            setup.factorSettings,
            setup.design
        )
        sequential.simulateAll(numRepsPerDesignPoint = REPS_PER_POINT)

        val parallel = buildParallelDesignedExperiment(setup)
        parallel.simulateAll(numRepsPerDesignPoint = REPS_PER_POINT)

        val sequentialRuns = sequential.simulationRuns.associateBy { inputKey(it, setup) }
        val parallelRuns = parallel.simulationRuns.associateBy { inputKey(it, setup) }

        assertEquals(sequentialRuns.keys, parallelRuns.keys)
        for (key in sequentialRuns.keys) {
            assertArrayEquals(
                sequentialRuns.getValue(key).replicationObservations("System Time")!!,
                parallelRuns.getValue(key).replicationObservations("System Time")!!,
                1.0e-10,
                "Parallel design point $key must match legacy DesignedExperiment observations"
            )
        }
    }

    @Test
    fun parallelDesignedExperimentUsesDefaultNumRepsPerDesignPointWhenNoOverrideSupplied() {
        val parallel = buildParallelDesignedExperiment(length = 50.0, warmUp = 0.0)
        parallel.defaultNumRepsPerDesignPoint = 4

        parallel.simulateAll()

        assertEquals(4, parallel.numSimulationRuns, "2^2 design must produce four design-point runs")
        for (run in parallel.simulationRuns) {
            assertEquals(
                4,
                run.numberOfReplications,
                "defaultNumRepsPerDesignPoint should apply when no method override is supplied"
            )
        }
    }

    @Test
    fun parallelDesignedExperimentMethodArgumentOverridesDefaultNumRepsPerDesignPoint() {
        val parallel = buildParallelDesignedExperiment(length = 50.0, warmUp = 0.0)
        parallel.defaultNumRepsPerDesignPoint = 2

        parallel.simulateAll(numRepsPerDesignPoint = 5)

        assertEquals(4, parallel.numSimulationRuns, "2^2 design must produce four design-point runs")
        for (run in parallel.simulationRuns) {
            assertEquals(
                5,
                run.numberOfReplications,
                "simulateAll(numRepsPerDesignPoint) should take precedence over the class default"
            )
        }
    }

    @Test
    fun parallelDesignedExperimentUsesCumulativeIndependentStreamAdvancesByDefault() {
        val parallel = buildParallelDesignedExperiment(length = 50.0, warmUp = 0.0)

        parallel.simulateAll(numRepsPerDesignPoint = 3)

        assertEquals(DesignPointRandomStreamPolicy.INDEPENDENT_RANDOM_STREAMS, parallel.streamPolicy)
        assertEquals(
            listOf(0, 3, 6, 9),
            parallel.simulationRuns.map {
                it.experimentRunParameters.numberOfStreamAdvancesPriorToRunning
            }
        )
    }

    @Test
    fun parallelDesignedExperimentCanUseCommonRandomNumbers() {
        val parallel = buildParallelDesignedExperiment(length = 50.0, warmUp = 0.0)
        parallel.useCommonRandomNumbers()

        parallel.simulateAll(numRepsPerDesignPoint = 3)

        assertEquals(DesignPointRandomStreamPolicy.COMMON_RANDOM_NUMBERS, parallel.streamPolicy)
        assertEquals(
            listOf(0, 0, 0, 0),
            parallel.simulationRuns.map {
                it.experimentRunParameters.numberOfStreamAdvancesPriorToRunning
            }
        )
    }

    @Test
    fun parallelDesignedExperimentAppliesCustomIndependentStreamSpacing() {
        val parallel = buildParallelDesignedExperiment(length = 50.0, warmUp = 0.0)
        parallel.useIndependentRandomStreams(startingStreamAdvance = 2, streamAdvanceSpacing = 10)

        parallel.simulateAll(numRepsPerDesignPoint = 3)

        assertEquals(
            listOf(2, 12, 22, 32),
            parallel.simulationRuns.map {
                it.experimentRunParameters.numberOfStreamAdvancesPriorToRunning
            }
        )
    }

    @Test
    fun parallelDesignedExperimentRejectsInvalidFactorSettings() {
        val setup = buildDoeSetup("PDE_Invalid_${System.nanoTime()}")
        val badFactors = setup.factorSettings.toMutableMap()
        val firstFactor = badFactors.keys.first()
        badFactors[firstFactor] = "NotAControl.orParameter"

        val exception = assertFailsWith<IllegalArgumentException> {
            ParallelDesignedExperiment(
                name = "ParallelDesignedExperimentInvalid_${System.nanoTime()}",
                modelBuilder = modelBuilder(setup.modelName),
                factorSettings = badFactors,
                design = setup.design
            )
        }

        assertTrue(exception.message!!.contains("invalid input names"))
    }

    @Test
    fun parallelDesignedExperimentProducesDesignedExperimentStyleDataFrames() {
        val parallel = buildParallelDesignedExperiment()

        parallel.simulateAll(numRepsPerDesignPoint = REPS_PER_POINT)

        assertEquals(12, parallel.replicatedDesignPointsAsDataFrame().rowsCount())
        assertEquals(12, parallel.responseAsDataFrame("System Time").rowsCount())
        assertEquals(12, parallel.replicatedDesignPointsWithResponse("System Time").rowsCount())
        assertEquals(4, parallel.observationsAsMap("System Time").size)
    }
}
