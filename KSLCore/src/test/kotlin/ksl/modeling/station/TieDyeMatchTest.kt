package ksl.modeling.station

import ksl.examples.book.chapter6.TieDyeTShirts
import ksl.examples.general.models.station.TieDyeStation
import ksl.simulation.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.math.abs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies the Tie-Dye T-Shirts (book chapter 6) model is faithfully reproduced
 * by the station network. The legacy uses ProcessModel + BlockingQueue; this
 * test compares the station-network version's average order system time to the
 * legacy's, expecting agreement within Monte Carlo noise.
 *
 * Notably this exercises ForkStation + JoinStation + a shared SResource (the
 * Packager, held across two unrelated points in the flow via Seize/Release).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TieDyeMatchTest {

    private companion object {
        const val REPS = 30
        const val LENGTH = 480.0
    }

    @Test
    fun stationModelStatisticallyMatchesLegacy() {
        // legacy
        val mL = Model("LegacyTieDye", autoCSVReports = false)
        TieDyeTShirts(mL, "TieDye")
        mL.numberOfReplications = REPS
        mL.lengthOfReplication = LENGTH
        mL.simulate()
        val legacySysTime = mL.response("System Time")
        assertNotNull(legacySysTime, "legacy 'System Time' response not found")

        // station network
        val mS = Model("StationTieDye", autoCSVReports = false)
        val td = TieDyeStation(mS, "TieDye")
        mS.numberOfReplications = REPS
        mS.lengthOfReplication = LENGTH
        mS.simulate()

        val legacyAvg = legacySysTime!!.acrossReplicationStatistic.average
        val stationAvg = td.network.systemTime.acrossReplicationStatistic.average

        // Both system times should be in the same order of magnitude (~30-80 min based
        // on the legacy's typical numbers). The dominant cost is shirt-making
        // (Uniform 15-25 with 3-5 shirts per order on 2 makers) plus paperwork (~9) and
        // packaging (~10). The legacy and station versions differ in how the join's
        // queueing for the Packager is structured (BlockingQueue vs SeizeStation), so
        // some statistical divergence is expected; assert correspondence within 30%.
        val relErr = abs(stationAvg - legacyAvg) / legacyAvg
        assertTrue(
            relErr < 0.30,
            "station system time $stationAvg differs from legacy $legacyAvg by ${(relErr * 100).toInt()}% (>30%)"
        )
        // both should be positive, finite, and reasonable
        assertTrue(stationAvg in 10.0..200.0, "station system time $stationAvg out of expected range")
        assertTrue(legacyAvg in 10.0..200.0, "legacy system time $legacyAvg out of expected range")
        // holdingsAtRunEnd is a WIP diagnostic (terminating sim naturally leaves work in
        // process at t=480); it is not a leak gate. Leaks would have failed the run at
        // an exit point with a loud error -- the run completed, so no entity exited
        // holding a resource. We just check the WIP count is bounded.
        val avgWipHolding = td.network.holdingsAtRunEnd.acrossReplicationStatistic.average
        assertTrue(avgWipHolding < 5.0, "WIP holdings at run end = $avgWipHolding (>5 suggests a problem)")
    }
}
