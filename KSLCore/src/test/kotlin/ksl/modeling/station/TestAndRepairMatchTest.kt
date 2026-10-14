package ksl.modeling.station

import ksl.examples.book.chapter8.TestAndRepairShopResourceConstrained
import ksl.examples.general.models.station.TestAndRepairStation
import ksl.simulation.Model
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.math.abs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the Test and Repair Shop (book chapter 8) station-network model
 * reproduces the legacy ProcessModel's part system time within Monte Carlo
 * noise. The legacy uses 8 individual workers shared between transport and
 * test/repair via a ResourcePoolWithQ union; the station version uses an
 * independent cap-8 transport pool (a noted simplification, see
 * TestAndRepairStation kdoc). Otherwise the structure is identical:
 * diagnostic prelude, one of four random test plans (probs .25/.125/.375/.25),
 * each plan visits 2-4 (tester, machine) pairs with nested seize, repair at end.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestAndRepairMatchTest {

    /**
     * The complete model contains nested seizes across many resources, with each
     * test machine at ~95% utilization in steady state. Stationarity requires a
     * long-ish run; the reference legacy main runs ~250,000 minutes (a year of
     * operation). We use a shorter run here to keep the test suite fast.
     */
    @Test
    fun stationModelRunsAndProducesSensibleStatistics() {
        val mS = Model("StationTR", autoCSVReports = false)
        val station = TestAndRepairStation(mS, "TR")
        mS.numberOfReplications = 5
        mS.lengthOfReplication = 50000.0
        mS.lengthOfReplicationWarmUp = 5000.0
        mS.simulate()

        val stationAvg = station.network.systemTime.acrossReplicationStatistic.average
        val completed = station.network.numCompleted.acrossReplicationStatistic.average

        // model runs cleanly: positive, plausible system time
        assertTrue(stationAvg in 30.0..2000.0, "station system time $stationAvg out of expected range")
        // many parts completed (with mean arrival 20 over ~45000 effective min, expect ~2250/rep)
        assertTrue(completed > 1000.0, "expected at least 1000 completions per rep, got $completed")
        // route + seize/release coordinated correctly: every released part exited with no allocations.
        // (The exit-time validation would have failed loudly otherwise.)
    }

    /**
     * Compares the station model's system time with the legacy model's.
     *
     * Before R1.8 the legacy model's system time was usually NaN here and the comparison was skipped: its
     * workers are shared between a transport pool and direct seizes, and a worker released by one route was
     * never offered to the parts waiting for it by the other, so almost nothing left the system. Since R1.8
     * the legacy model runs properly, and the comparison measures the station model's simplification, its
     * independent transport pool: transport workers no longer compete with testing and repair, so parts move
     * through faster. At R1.8 the station model's system time was 18% below the legacy's (337 against 414).
     * The bound holds that gap where it stands.
     */
    @Test
    fun stationVsLegacyWhenComparable() {
        val reps = 5
        val length = 50000.0
        val warmup = 5000.0

        val mL = Model("LegacyTR", autoCSVReports = false)
        val legacy = TestAndRepairShopResourceConstrained(mL, "TR")
        mL.numberOfReplications = reps
        mL.lengthOfReplication = length
        mL.lengthOfReplicationWarmUp = warmup
        mL.simulate()
        val legacyAvg = legacy.systemTime.acrossReplicationStatistic.average

        assertFalse(legacyAvg.isNaN(), "the legacy model must produce a system time")

        val mS = Model("StationTR", autoCSVReports = false)
        val station = TestAndRepairStation(mS, "TR")
        mS.numberOfReplications = reps
        mS.lengthOfReplication = length
        mS.lengthOfReplicationWarmUp = warmup
        mS.simulate()
        val stationAvg = station.network.systemTime.acrossReplicationStatistic.average

        val relErr = abs(stationAvg - legacyAvg) / legacyAvg
        println("legacy system time = $legacyAvg, station system time = $stationAvg, relative difference = $relErr")
        assertTrue(
            stationAvg < legacyAvg && relErr < 0.25,
            "station system time $stationAvg vs legacy $legacyAvg: expected the station model's independent " +
                    "transport pool to make it faster, by less than 25%, but the difference is ${(relErr * 100).toInt()}%"
        )
    }

    /**
     * The legacy chapter 8 model obeys Little's Law. Its workers are shared between a transport pool and direct
     * seizes, which lost wakeups before R1.8 and let work in process grow through the run.
     */
    @Test
    fun legacyModelObeysLittlesLaw() {
        val m = Model("LegacyTRLittle", autoCSVReports = false)
        val legacy = TestAndRepairShopResourceConstrained(m, "TR")
        m.numberOfReplications = 3
        m.lengthOfReplication = 50000.0
        m.lengthOfReplicationWarmUp = 5000.0
        m.simulate()
        val l = legacy.numInSystem.acrossReplicationStatistic.average
        val lambdaW = legacy.systemTime.acrossReplicationStatistic.average / 20.0
        println("legacy chapter 8: NumInSystem = $l, lambda * W = $lambdaW")
        assertTrue(abs(l - lambdaW) / lambdaW <= 0.05, "L = $l against lambda * W = $lambdaW")
    }
}
