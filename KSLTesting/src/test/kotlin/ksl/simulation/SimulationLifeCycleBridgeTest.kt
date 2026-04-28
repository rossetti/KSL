package ksl.simulation

import ksl.examples.book.appendixD.GIGcQueue
import ksl.utilities.io.dbutil.SimulationSnapshot
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Verifies that [SimulationLifeCycleBridge] emits the correct [SimulationSnapshot]
 * variants at each lifecycle boundary, with correctly populated fields.
 *
 * Model: GIGcQueue M/M/1 (λ=1, μ=2). 3 replications × 500 min.
 * No database observer attached — bridge is the only observer via lifeCycleEmitters.
 */
class SimulationLifeCycleBridgeTest {

    private fun buildModel(): Model {
        val model = Model("BridgeTestSim")
        GIGcQueue(model)
        model.numberOfReplications = 3
        model.lengthOfReplication = 500.0
        return model
    }

    @Test
    fun `experimentStarted fires once with correct experiment metadata`() {
        val model = buildModel()
        var startedCount = 0
        var captured: SimulationSnapshot.ExperimentStarted? = null

        model.lifeCycleEmitters.experimentStarted.attach { snapshot ->
            startedCount++
            captured = snapshot
        }

        model.simulate()

        assertEquals(1, startedCount, "ExperimentStarted must fire exactly once")
        assertNotNull(captured)
        assertEquals("BridgeTestSim", captured!!.experiment.sim_name)
        assertTrue(captured!!.modelElements.isNotEmpty(), "modelElements must not be empty")
    }

    @Test
    fun `replicationCompleted fires once per replication with within-rep stats`() {
        val model = buildModel()
        val repIds = mutableListOf<Int>()
        var statsNonEmpty = true

        model.lifeCycleEmitters.replicationCompleted.attach { snapshot ->
            repIds.add(snapshot.repId)
            if (snapshot.withinRepStats.isEmpty()) statsNonEmpty = false
        }

        model.simulate()

        assertEquals(3, repIds.size, "ReplicationCompleted must fire once per replication")
        assertEquals(listOf(1, 2, 3), repIds, "repIds must be sequential")
        assertTrue(statsNonEmpty, "withinRepStats must not be empty for any replication")
    }

    @Test
    fun `experimentCompleted fires once with across-rep stats`() {
        val model = buildModel()
        var completedCount = 0
        var captured: SimulationSnapshot.ExperimentCompleted? = null

        model.lifeCycleEmitters.experimentCompleted.attach { snapshot ->
            completedCount++
            captured = snapshot
        }

        model.simulate()

        assertEquals(1, completedCount, "ExperimentCompleted must fire exactly once")
        assertNotNull(captured)
        assertTrue(captured!!.acrossRepStats.isNotEmpty(), "acrossRepStats must not be empty")
        assertEquals(3, captured!!.simulationRun.last_rep_id, "last_rep_id must equal number of replications")
        assertNotNull(captured!!.simulationRun.run_end_time_stamp, "run_end_time_stamp must be set")
    }

    @Test
    fun `experimentFailed does not fire on a clean run`() {
        val model = buildModel()
        var failedCount = 0

        model.lifeCycleEmitters.experimentFailed.attach { failedCount++ }

        model.simulate()

        assertEquals(0, failedCount, "ExperimentFailed must not fire on a successful run")
    }

    @Test
    fun `no snapshots emitted when no subscribers attached`() {
        val model = buildModel()
        // Access lifeCycleEmitters to trigger lazy init and bridge attachment,
        // but attach no subscribers. Simulation must complete without error.
        val emitters = model.lifeCycleEmitters
        assertFalse(emitters.experimentStarted.isObserved)

        assertDoesNotThrow { model.simulate() }
    }
}
