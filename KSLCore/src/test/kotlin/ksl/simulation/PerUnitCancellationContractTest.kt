package ksl.simulation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import ksl.controls.experiments.ConcurrentScenarioRunner
import ksl.controls.experiments.Factor
import ksl.controls.experiments.ParallelDesignedExperiment
import ksl.controls.experiments.Scenario
import ksl.controls.experiments.TwoLevelFactor
import ksl.controls.experiments.TwoLevelFactorialDesign
import ksl.examples.book.appendixD.GIGcQueue
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The per-unit cancellation contract shared by [ParallelDesignedExperiment]
 * and [ConcurrentScenarioRunner]: what the cancel entry point's boolean means,
 * and what happens to a unit whose work has already finished.
 *
 * These tests live in `ksl.simulation` rather than `ksl.controls.experiments`
 * (where the classes under test live) because the gating fixture subclasses
 * [ModelElement], and because the existing `ParallelDesignedExperimentTest`
 * is already here.
 *
 * **Why there are no sleeps or timing assumptions.** Every window these tests
 * need is created structurally: sibling units block inside their own model's
 * `beforeExperiment` until the test releases them, so the runner's commit
 * phase provably cannot start. The target unit signals its real completion
 * from `afterExperiment`. The `await` timeouts on the latch are deadlock
 * safety valves, never the mechanism under test — no assertion depends on
 * them elapsing.
 */
class PerUnitCancellationContractTest {

    companion object {
        /** Deadlock safety valve only. No assertion depends on this. */
        private const val GATE_TIMEOUT_SEC = 60L
        private const val REPLICATION_LENGTH = 2000.0
    }

    /**
     * Attached to every model the fixtures build. Non-target units block in
     * `beforeExperiment` until released, holding the run open; the target
     * unit reports the instant its experiment ends.
     */
    private class ExperimentGate(
        parent: ModelElement,
        private val isTarget: Boolean,
        private val gate: CountDownLatch,
        private val onTargetExperimentEnd: () -> Unit
    ) : ModelElement(parent, "ExperimentGate") {

        override fun beforeExperiment() {
            if (!isTarget) gate.await(GATE_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)
        }

        override fun afterExperiment() {
            if (isTarget) onTargetExperimentEnd()
        }
    }

    // ---------------------------------------------------------------- PDE --

    private class DoeFixture {
        val gate = CountDownLatch(1)
        val targetDone = CompletableDeferred<Unit>()
        val cancelled: MutableList<Int> = java.util.Collections.synchronizedList(mutableListOf<Int>())

        private val modelName = "PDECancel_${System.nanoTime()}"

        fun build(): ParallelDesignedExperiment {
            val fServer = TwoLevelFactor("Server", 1.0, 2.0)
            val fST = TwoLevelFactor("MeanST", 0.5, 0.8)
            val design = TwoLevelFactorialDesign(setOf(fServer, fST))
            val factorSettings = mapOf<Factor, String>(
                fServer to "MM1Q.numServers",
                fST to "$modelName:ServiceTime.mean"
            )
            val builder = object : ModelBuilderIfc {
                override fun build(
                    modelConfiguration: Map<String, String>?,
                    experimentRunParameters: ExperimentRunParametersIfc?
                ): Model {
                    val m = Model(modelName, autoCSVReports = false)
                    m.lengthOfReplication = REPLICATION_LENGTH
                    m.lengthOfReplicationWarmUp = 0.0
                    GIGcQueue(
                        m, numServers = 1,
                        ad = ExponentialRV(1.0, 1), sd = ExponentialRV(0.5, 2), name = "MM1Q"
                    )
                    // The design point number is only knowable from the
                    // experiment name the runner assigns, which is not yet
                    // set at build time — so decide target-ness lazily.
                    ExperimentGateForPoint(m, gate, targetDone)
                    return m
                }
            }
            return ParallelDesignedExperiment(
                name = "PDECancel_PDE_${System.nanoTime()}",
                modelBuilder = builder,
                factorSettings = factorSettings,
                design = design,
                pathToOutputDirectory = java.nio.file.Files.createTempDirectory("pde-cancel-contract-")
            )
        }

        fun committedPointIds(pde: ParallelDesignedExperiment): List<Int> =
            pde.simulationRuns.map {
                it.experimentRunParameters.experimentName.substringAfterLast("_DP_").toInt()
            }.sorted()
    }

    /**
     * Design-point variant of [ExperimentGate]: target-ness depends on the
     * experiment name, which the runner assigns after the model is built, so
     * the decision has to happen inside the lifecycle callbacks.
     */
    private class ExperimentGateForPoint(
        parent: ModelElement,
        private val gate: CountDownLatch,
        private val targetDone: CompletableDeferred<Unit>
    ) : ModelElement(parent, "ExperimentGateForPoint") {

        private fun isTargetPoint(): Boolean = model.experimentName.endsWith("_DP_1")

        override fun beforeExperiment() {
            if (!isTargetPoint()) gate.await(GATE_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)
        }

        override fun afterExperiment() {
            if (isTargetPoint()) targetDone.complete(Unit)
        }
    }

    @Test
    @DisplayName("A design point whose experiment finished is never reported cancelled (D2)")
    fun completedDesignPointIsNeverReportedCancelled(): Unit = runBlocking {
        val fixture = DoeFixture()
        val pde = fixture.build()

        val simJob = launch {
            pde.simulateAll(
                numRepsPerDesignPoint = 1,
                onDesignPointCancelled = { dp -> fixture.cancelled += dp.number }
            )
        }

        // Point 1's replications are provably done at this point.
        fixture.targetDone.await()
        pde.cancelDesignPoint(1)
        fixture.gate.countDown()
        simJob.join()

        val committed = fixture.committedPointIds(pde)
        assertFalse(
            1 in fixture.cancelled,
            "Point 1 completed its experiment, so it must not be reported cancelled; " +
                "cancelled = ${fixture.cancelled}"
        )
        assertTrue(
            1 in committed,
            "Point 1 completed its experiment, so its results must be committed; " +
                "committed = $committed"
        )
    }

    @Test
    @DisplayName("cancelDesignPoint returns false once the point's work is done (D1)")
    fun cancelDesignPointReturnsFalseOnceWorkIsDone(): Unit = runBlocking {
        val fixture = DoeFixture()
        val pde = fixture.build()

        val simJob = launch {
            pde.simulateAll(
                numRepsPerDesignPoint = 1,
                onDesignPointCancelled = { dp -> fixture.cancelled += dp.number }
            )
        }

        fixture.targetDone.await()
        val returned = pde.cancelDesignPoint(1)
        fixture.gate.countDown()
        simJob.join()

        assertFalse(
            returned,
            "Point 1 had already finished its experiment, so cancelDesignPoint must " +
                "report false rather than claiming it cancelled something"
        )
    }

    // ---------------------------------------------------------------- CSR --

    private fun scenarioNamed(
        scenarioName: String,
        isTarget: Boolean,
        gate: CountDownLatch,
        onTargetDone: () -> Unit
    ): Scenario {
        val builder = object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ExperimentRunParametersIfc?
            ): Model {
                val m = Model("Model_$scenarioName", autoCSVReports = false)
                GIGcQueue(
                    m, numServers = 1,
                    ad = ExponentialRV(1.0, 1), sd = ExponentialRV(0.5, 2), name = "MM1Q"
                )
                ExperimentGate(m, isTarget, gate, onTargetDone)
                return m
            }
        }
        return Scenario(
            modelBuilder = builder,
            name = scenarioName,
            numberReplications = 1,
            lengthOfReplication = REPLICATION_LENGTH,
            lengthOfReplicationWarmUp = 0.0
        )
    }

    @Test
    @DisplayName("A scenario whose experiment finished is never reported cancelled (D2)")
    fun completedScenarioIsNeverReportedCancelled(): Unit = runBlocking {
        val gate = CountDownLatch(1)
        val targetDone = CompletableDeferred<Unit>()
        val runner = ConcurrentScenarioRunner(
            name = "CSRCancel_${System.nanoTime()}",
            scenarioList = listOf(
                scenarioNamed("TARGET", isTarget = true, gate = gate) { targetDone.complete(Unit) },
                scenarioNamed("SIB_A", isTarget = false, gate = gate) { },
                scenarioNamed("SIB_B", isTarget = false, gate = gate) { }
            ),
            pathToOutputDirectory = java.nio.file.Files.createTempDirectory("csr-cancel-contract-"),
            kslDb = null
        )

        val withSnapshot = java.util.Collections.synchronizedList(mutableListOf<String>())
        val nullSnapshot = java.util.Collections.synchronizedList(mutableListOf<String>())

        val simJob = launch {
            runner.simulate(
                onScenarioComplete = { name, snapshot ->
                    if (snapshot == null) nullSnapshot += name else withSnapshot += name
                }
            )
        }

        targetDone.await()
        val returned = runner.cancelScenario("TARGET")
        gate.countDown()
        simJob.join()

        assertFalse(
            returned,
            "TARGET had already finished its experiment, so cancelScenario must " +
                "report false rather than claiming it cancelled something"
        )
        assertTrue(
            "TARGET" in withSnapshot,
            "TARGET completed its replications, so it must be reported with a real " +
                "snapshot; withSnapshot = $withSnapshot, nullSnapshot = $nullSnapshot"
        )
    }
}
