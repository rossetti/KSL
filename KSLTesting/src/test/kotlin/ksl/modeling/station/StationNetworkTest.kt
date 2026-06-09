package ksl.modeling.station

import ksl.examples.book.chapter4.TandemQueue
import ksl.examples.general.models.station.StationNetworkTandemQueue
import ksl.simulation.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase-0 regression tests for the [StationNetwork] substrate.
 *
 * Three tiers:
 *  - Smoke      : the network model runs and its system-level responses exist.
 *  - Analytical : the two-station tandem M/M/1 results obey queueing-theory
 *                 sign/ordering constraints.
 *  - Equivalence: the network-built tandem queue reproduces the hand-built
 *                 legacy [TandemQueue] (chapter 4) bit-for-bit, proving the new
 *                 primitives are a faithful, lower-boilerplate substitute.
 *
 * Config: 30 reps, 20000 length, 5000 warm-up, default KSL seed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StationNetworkTest {

    private companion object {
        const val REPS = 30
        const val LENGTH = 20000.0
        const val WARMUP = 5000.0
        const val TOL = 1.0E-9
    }

    private fun runNetworkModel(): StationNetworkTandemQueue {
        val m = Model("NetTQ-Test", autoCSVReports = false)
        val tq = StationNetworkTandemQueue(m, "TQ")
        m.numberOfReplications = REPS
        m.lengthOfReplication = LENGTH
        m.lengthOfReplicationWarmUp = WARMUP
        m.simulate()
        return tq
    }

    private fun runLegacyModel(): TandemQueue {
        val m = Model("LegacyTQ-Test", autoCSVReports = false)
        val tq = TandemQueue(m, "TQ")
        m.numberOfReplications = REPS
        m.lengthOfReplication = LENGTH
        m.lengthOfReplicationWarmUp = WARMUP
        m.simulate()
        return tq
    }

    @Test
    fun smokeResponsesExist() {
        val tq = runNetworkModel()
        assertNotNull(tq.network.numInSystem)
        assertNotNull(tq.network.systemTime)
        assertNotNull(tq.network.numCompleted)
        assertTrue(tq.network.numCompleted.acrossReplicationStatistic.average > 0.0)
    }

    @Test
    fun analyticalConstraints() {
        val tq = runNetworkModel()
        val ns = tq.network.numInSystem.acrossReplicationStatistic.average
        val st = tq.network.systemTime.acrossReplicationStatistic.average
        // Positive, finite WIP and system time
        assertTrue(ns > 0.0)
        assertTrue(st > 0.0)
        // Each station is M/M/1: rho1 = 4/6, rho2 = 3/6; expected E[T] = E[T1]+E[T2]
        //   E[T1] = 1/(1/4 - 1/6)... using service means 4 and 3, arrival mean 6:
        //   mu1 = 1/4, lambda = 1/6 -> Wq+Ws form. Expected total system time ~ 12 + 6 = 18.
        // Allow a generous band around the theoretical mean to stay a robust constraint.
        assertTrue(st in 12.0..26.0, "system time $st outside expected analytical band")
    }

    @Test
    fun equivalentToLegacyTandemQueue() {
        val net = runNetworkModel()
        val legacy = runLegacyModel()

        val netNS = net.network.numInSystem.acrossReplicationStatistic.average
        val netST = net.network.systemTime.acrossReplicationStatistic.average
        val netNP = net.network.numCompleted.acrossReplicationStatistic.average

        val legNS = legacy.numInSystem.acrossReplicationStatistic.average
        val legST = legacy.totalSystemTime.acrossReplicationStatistic.average
        val legNP = legacy.totalProcessed.acrossReplicationStatistic.average

        assertEquals(legNS, netNS, TOL, "number-in-system differs from legacy")
        assertEquals(legST, netST, TOL, "system-time differs from legacy")
        assertEquals(legNP, netNP, TOL, "number-completed differs from legacy")
    }
}
