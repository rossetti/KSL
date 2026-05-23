package ksl.simulation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

//@Disabled
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
    fun parallelDesignedExperimentMatchesDesignedExperimentWithDefaultIndependentStreams() = runBlocking {
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
    fun parallelDesignedExperimentUsesDefaultNumRepsPerDesignPointWhenNoOverrideSupplied() = runBlocking {
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
    fun parallelDesignedExperimentMethodArgumentOverridesDefaultNumRepsPerDesignPoint() = runBlocking {
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
    fun parallelDesignedExperimentUsesCumulativeIndependentStreamAdvancesByDefault() = runBlocking {
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
    fun parallelDesignedExperimentCanUseCommonRandomNumbers() = runBlocking {
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
    fun parallelDesignedExperimentAppliesCustomIndependentStreamSpacing() = runBlocking {
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
    fun parallelDesignedExperimentProducesDesignedExperimentStyleDataFrames() = runBlocking {
        val parallel = buildParallelDesignedExperiment()

        parallel.simulateAll(numRepsPerDesignPoint = REPS_PER_POINT)

        assertEquals(12, parallel.replicatedDesignPointsAsDataFrame().rowsCount())
        assertEquals(12, parallel.responseAsDataFrame("System Time").rowsCount())
        assertEquals(12, parallel.replicatedDesignPointsWithResponse("System Time").rowsCount())
        assertEquals(4, parallel.observationsAsMap("System Time").size)
    }

    @Test
    fun parallelDesignedExperimentCallbackUsesDesignPointOrder() = runBlocking {
        val parallel = buildParallelDesignedExperiment(length = 50.0, warmUp = 0.0)
        val callbackPoints = mutableListOf<Int>()

        parallel.simulateAll(
            numRepsPerDesignPoint = 1,
            onDesignPointComplete = { designPoint, snapshot ->
                callbackPoints.add(designPoint.number)
                assertTrue(snapshot != null, "Successful design point should provide a snapshot")
            }
        )

        assertEquals(
            parallel.design.designPoints().map { it.number },
            callbackPoints,
            "Callbacks should be emitted in design-point commit order"
        )
    }

    @Test
    fun parallelDesignedExperimentPropagatesCancellationInsteadOfRecordingDesignPointFailure() {
        val setup = buildDoeSetup("PDE_Cancel_${System.nanoTime()}")
        var buildCount = 0
        val cancellingBuilder = object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ExperimentRunParametersIfc?
            ): Model {
                buildCount++
                if (buildCount == 1) {
                    return buildModel(setup.modelName, length = 50.0, warmUp = 0.0)
                }
                throw CancellationException("intentional parallel design cancellation")
            }
        }
        val parallel = ParallelDesignedExperiment(
            name = "ParallelDesignedExperimentCancel_${System.nanoTime()}",
            modelBuilder = cancellingBuilder,
            factorSettings = setup.factorSettings,
            design = setup.design
        )

        assertFailsWith<CancellationException> {
            runBlocking { parallel.simulateAll(numRepsPerDesignPoint = 1) }
        }

        assertTrue(
            parallel.simulationRuns.isEmpty(),
            "Coroutine cancellation should not be converted into failed design-point runs"
        )
    }

    // ─────────────────────────────────────────────────────────────────
    //  experimentName override + useDesignPointOutputDirs (added when
    //  the Experiment GUI app started getting "Experiment_<counter>_DP_*"
    //  folder names whose prefix changed on every re-run; both knobs
    //  added together to give callers stable, human-readable names and
    //  a flat output layout when per-point dirs are not wanted).
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun explicitExperimentNameAnchorsPerPointNamesAndUseDirsFalseFlattensOutput() = runBlocking {
        val setup = buildDoeSetup("PDE_Anchored_${System.nanoTime()}")
        val outDir = java.nio.file.Files.createTempDirectory("pde-anchored-")
        val anchor = "MyAnchoredAnalysis"
        val parallel = ParallelDesignedExperiment(
            name = "ParallelDesignedExperiment_${System.nanoTime()}",
            modelBuilder = modelBuilder(setup.modelName, length = 50.0, warmUp = 0.0),
            factorSettings = setup.factorSettings,
            design = setup.design,
            pathToOutputDirectory = outDir,
            experimentName = anchor,
            useDesignPointOutputDirs = false
        )
        parallel.simulateAll(numRepsPerDesignPoint = 1)

        // Per-point experiment names persist with the anchor as prefix,
        // not the JVM-counter auto-name.
        val expNames = parallel.simulationRuns.map { it.experimentRunParameters.experimentName }
        assertTrue(
            expNames.isNotEmpty() && expNames.all { it.startsWith("${anchor}_DP_") },
            "All per-point experiment names should be '${anchor}_DP_<n>'; got $expNames"
        )

        // No per-point subdirectories — the flat layout puts per-point
        // diagnostic logs as kslOutput_DP_<n>.txt at the shared dir.
        val children = outDir.toFile().listFiles().orEmpty()
        val subdirs = children.filter { it.isDirectory }
        assertTrue(
            subdirs.none { it.name.endsWith("_OutputDir") },
            "useDesignPointOutputDirs = false should not create any *_OutputDir subdirs; got $subdirs"
        )
        val perPointLogs = children.filter { it.isFile && it.name.startsWith("kslOutput_DP_") }
        assertEquals(
            parallel.numSimulationRuns,
            perPointLogs.size,
            "Expected one kslOutput_DP_<n>.txt per design point; got ${perPointLogs.map { it.name }}"
        )
    }

    @Test
    fun defaultsPreserveLegacyAutoCounterNamingAndPerPointSubdirs() = runBlocking {
        val setup = buildDoeSetup("PDE_Legacy_${System.nanoTime()}")
        val outDir = java.nio.file.Files.createTempDirectory("pde-legacy-")
        val parallel = ParallelDesignedExperiment(
            name = "ParallelDesignedExperiment_${System.nanoTime()}",
            modelBuilder = modelBuilder(setup.modelName, length = 50.0, warmUp = 0.0),
            factorSettings = setup.factorSettings,
            design = setup.design,
            pathToOutputDirectory = outDir
            // experimentName + useDesignPointOutputDirs defaults intentionally untouched.
        )
        parallel.simulateAll(numRepsPerDesignPoint = 1)

        // Legacy behaviour: the per-point name prefix is the template
        // model's auto-counter name (Experiment_<counter>), and each
        // point gets its own *_OutputDir subdir.
        val expNames = parallel.simulationRuns.map { it.experimentRunParameters.experimentName }
        assertTrue(
            expNames.isNotEmpty() && expNames.all { it.contains("_DP_") },
            "Per-point experiment names should follow '<prefix>_DP_<n>'; got $expNames"
        )
        val subdirs = outDir.toFile().listFiles().orEmpty().filter {
            it.isDirectory && it.name.endsWith("_OutputDir")
        }
        assertEquals(
            parallel.numSimulationRuns,
            subdirs.size,
            "Legacy default should create one *_OutputDir per design point; got ${subdirs.map { it.name }}"
        )
    }

    // ─────────────────────────────────────────────────────────────────
    //  Per-design-point cancellation + per-point lifecycle callbacks
    //  (added for the Experiment app's live status display +
    //  per-point Cancel button).
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun onDesignPointStartFiresBeforeEachPerPointCoroutine() = runBlocking {
        val setup = buildDoeSetup("PDE_StartCb_${System.nanoTime()}")
        val started = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val completed = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val parallel = ParallelDesignedExperiment(
            name = "PDE_${System.nanoTime()}",
            modelBuilder = modelBuilder(setup.modelName, length = 30.0, warmUp = 0.0),
            factorSettings = setup.factorSettings,
            design = setup.design,
            pathToOutputDirectory = java.nio.file.Files.createTempDirectory("pde-startcb-")
        )

        parallel.simulateAll(
            numRepsPerDesignPoint = 1,
            onDesignPointStart = { dp -> started += dp.number },
            onDesignPointComplete = { dp, _ -> completed += dp.number }
        )

        assertEquals(parallel.numSimulationRuns, started.size,
            "onDesignPointStart should fire once per design point")
        assertEquals(parallel.design.designPoints().map { it.number }.toSet(), started.toSet(),
            "onDesignPointStart should fire for every point in the design")
        assertEquals(started.toSet(), completed.toSet(),
            "Every started point should also complete (none missing)")
    }

    @Test
    fun cancelDesignPointSkipsTargetedPointAndContinuesOthers() = runBlocking {
        val setup = buildDoeSetup("PDE_CancelOne_${System.nanoTime()}")
        val outDir = java.nio.file.Files.createTempDirectory("pde-cancel-")
        // Long-ish replication so we have time to observe point 1
        // start and cancel it before the model finishes naturally.
        val parallel = ParallelDesignedExperiment(
            name = "PDE_${System.nanoTime()}",
            modelBuilder = modelBuilder(setup.modelName, length = 2000.0, warmUp = 0.0),
            factorSettings = setup.factorSettings,
            design = setup.design,
            pathToOutputDirectory = outDir
        )

        val cancelled = java.util.Collections.synchronizedList(mutableListOf<Int>())
        // Signal fired the moment point 1's coroutine has started; a
        // sibling coroutine awaits it and then calls cancelDesignPoint.
        // Cross-coroutine cancellation (vs. cancelling from inside the
        // start callback on the supervisor coroutine) avoids the
        // supervisorScope edge case where cancelling a child synchronously
        // from the supervisor body fails the parent.
        val pointOneStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val simJob = launch {
            parallel.simulateAll(
                numRepsPerDesignPoint = 1,
                onDesignPointStart = { dp ->
                    if (dp.number == 1) pointOneStarted.complete(Unit)
                },
                onDesignPointCancelled = { dp -> cancelled += dp.number }
            )
        }
        pointOneStarted.await()
        assertTrue(
            parallel.cancelDesignPoint(1),
            "cancelDesignPoint should find the Job for the in-flight point"
        )
        simJob.join()

        assertTrue(
            1 in cancelled,
            "Point 1 should have been cancelled; got cancelled = $cancelled"
        )
        // Cancelled point is NOT recorded in simulationRuns (the commit
        // phase skips DB writes + run accounting for cancelled outcomes).
        val completedPointIds = parallel.simulationRuns.map { run ->
            run.experimentRunParameters.experimentName.substringAfterLast("_DP_").toInt()
        }
        assertTrue(
            1 !in completedPointIds,
            "Cancelled point should not appear in simulationRuns; got $completedPointIds"
        )
        assertTrue(
            completedPointIds.isNotEmpty(),
            "At least one non-cancelled point should have completed"
        )
    }

    @Test
    fun cancelDesignPointOnUnknownIdReturnsFalse() = runBlocking {
        val setup = buildDoeSetup("PDE_CancelUnknown_${System.nanoTime()}")
        val parallel = ParallelDesignedExperiment(
            name = "PDE_${System.nanoTime()}",
            modelBuilder = modelBuilder(setup.modelName, length = 10.0, warmUp = 0.0),
            factorSettings = setup.factorSettings,
            design = setup.design,
            pathToOutputDirectory = java.nio.file.Files.createTempDirectory("pde-cancel-unk-")
        )
        // Before any run is in flight, no point is active.
        assertFalse(
            parallel.cancelDesignPoint(999),
            "cancelDesignPoint on an unknown / inactive point should return false"
        )
        // Even after a completed run, prior points are no longer active.
        parallel.simulateAll(numRepsPerDesignPoint = 1)
        assertFalse(
            parallel.cancelDesignPoint(1),
            "cancelDesignPoint on a completed point should return false"
        )
    }
}
