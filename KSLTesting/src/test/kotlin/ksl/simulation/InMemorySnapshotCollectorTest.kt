package ksl.simulation

import ksl.examples.book.appendixD.GIGcQueue
import ksl.utilities.io.dbutil.SimulationSnapshot
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Verifies [InMemorySnapshotCollector] accumulation, drain, and close behaviour.
 *
 * Model: GIGcQueue M/M/1 (λ=1, μ=2). 3 replications × 500 min.
 */
class InMemorySnapshotCollectorTest {

    private fun buildModel(): Model {
        val model = Model("CollectorTestSim")
        GIGcQueue(model)
        model.numberOfReplications = 3
        model.lengthOfReplication = 500.0
        return model
    }

    @Test
    fun `collector accumulates one ExperimentStarted, three ReplicationCompleted, one ExperimentCompleted`() {
        val model = buildModel()
        val collector = InMemorySnapshotCollector(model.lifeCycleEmitters)

        model.simulate()

        val snapshots = collector.drain()

        val started = snapshots.filterIsInstance<SimulationSnapshot.ExperimentStarted>()
        val repCompleted = snapshots.filterIsInstance<SimulationSnapshot.ReplicationCompleted>()
        val expCompleted = snapshots.filterIsInstance<SimulationSnapshot.ExperimentCompleted>()
        val failed = snapshots.filterIsInstance<SimulationSnapshot.ExperimentFailed>()

        assertEquals(1, started.size, "ExperimentStarted count")
        assertEquals(3, repCompleted.size, "ReplicationCompleted count")
        assertEquals(1, expCompleted.size, "ExperimentCompleted count")
        assertEquals(0, failed.size, "ExperimentFailed count on clean run")
    }

    @Test
    fun `snapshots arrive in emission order`() {
        val model = buildModel()
        val collector = InMemorySnapshotCollector(model.lifeCycleEmitters)

        model.simulate()

        val snapshots = collector.drain()

        assertTrue(snapshots.first() is SimulationSnapshot.ExperimentStarted, "first snapshot must be ExperimentStarted")
        assertTrue(snapshots.last() is SimulationSnapshot.ExperimentCompleted, "last snapshot must be ExperimentCompleted")

        val repIds = snapshots
            .filterIsInstance<SimulationSnapshot.ReplicationCompleted>()
            .map { it.repId }
        assertEquals(listOf(1, 2, 3), repIds, "ReplicationCompleted must appear in order")
    }

    @Test
    fun `drain clears the buffer`() {
        val model = buildModel()
        val collector = InMemorySnapshotCollector(model.lifeCycleEmitters)

        model.simulate()

        val first = collector.drain()
        val second = collector.drain()

        assertTrue(first.isNotEmpty(), "first drain must return snapshots")
        assertTrue(second.isEmpty(), "second drain must be empty after buffer cleared")
    }

    @Test
    fun `close stops accumulation for subsequent simulation`() {
        val model = buildModel()
        val collector = InMemorySnapshotCollector(model.lifeCycleEmitters)

        model.simulate()
        collector.close()
        val afterFirstRun = collector.drain()

        // run again — collector is detached, nothing new should arrive
        model.simulate()
        val afterSecondRun = collector.drain()

        assertTrue(afterFirstRun.isNotEmpty(), "snapshots must exist from first run")
        assertTrue(afterSecondRun.isEmpty(), "no snapshots after close")
    }

    @Test
    fun `use in try-with-resources pattern compiles and runs correctly`() {
        val model = buildModel()
        var snapshotCount = 0

        InMemorySnapshotCollector(model.lifeCycleEmitters).use { collector ->
            model.simulate()
            snapshotCount = collector.drain().size
        }

        // 1 ExperimentStarted + 3 ReplicationCompleted + 1 ExperimentCompleted = 5
        assertEquals(5, snapshotCount, "expected 5 total snapshots for 3-rep run")
    }
}
