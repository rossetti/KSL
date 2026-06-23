package ksl.modeling.station

import ksl.examples.book.chapter7.StemFairMixerEnhancedSched
import ksl.examples.general.models.station.StemFairEnhancedStation
import ksl.simulation.Model
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.math.abs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies the chapter-7 enhanced STEM Fair Mixer demonstration.
 *
 * The station model is a simplified faithful rendering of the legacy:
 * NHPP arrivals (12 hour-segment piecewise-constant rate), capacity-scheduled
 * recruiters (6 hour-long intervals each), three student classes via marking,
 * and walking delays driven by a sampled walking speed -- the same primitives
 * the legacy uses. Two simplifications are documented on the class kdoc:
 * (a) no door-closing dynamics, (b) each student visits one recruiter instead
 * of both. Both simplifications make exact match impossible; the test verifies
 * the model produces stationary positive system time in the right order of
 * magnitude and that all three classes participate.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StemFairEnhancedTest {

    @Test
    fun stationModelRunsCleanlyAndProducesAllClasses() {
        val m = Model("EnhancedMixer", autoCSVReports = false)
        val sf = StemFairEnhancedStation(m, "Mixer")
        m.numberOfReplications = 30
        m.lengthOfReplication = 360.0    // 6 hours
        m.simulate()

        // overall system time is positive and plausible (single-recruiter visit, walking)
        val overall = sf.network.systemTime.acrossReplicationStatistic.average
        assertTrue(overall in 1.0..120.0, "overall system time $overall out of expected range")

        // each class produced observations
        val recOnly = sf.network.classSystemTime("RecruitingOnly")
        val mixer = sf.network.classSystemTime("Mixer")
        val leaver = sf.network.classSystemTime("Leaver")
        assertNotNull(recOnly)
        assertNotNull(mixer)
        assertNotNull(leaver)
        assertTrue(recOnly!!.acrossReplicationStatistic.average > 0.0, "RecruitingOnly should have observations")
        assertTrue(mixer!!.acrossReplicationStatistic.average > 0.0, "Mixer should have observations")
        assertTrue(leaver!!.acrossReplicationStatistic.average > 0.0, "Leaver should have observations")

        // class ordering (sanity): mixers spend more time than recruiting-only (they also do conversation)
        assertTrue(
            mixer.acrossReplicationStatistic.average > recOnly.acrossReplicationStatistic.average,
            "Mixer time ${mixer.acrossReplicationStatistic.average} should exceed RecruitingOnly ${recOnly.acrossReplicationStatistic.average}"
        )

        // exit-time validation: no leaks
        assertTrue(sf.network.holdingsAtRunEnd.acrossReplicationStatistic.average == 0.0)
    }

    @Test
    fun stationVsLegacyOrderOfMagnitudeComparison() {
        // Same configuration, both runs. Loose comparison: the legacy includes
        // door closing + two-recruiter visits, the station version simplifies both.
        // We just verify they're in the same broad range (a sanity check).
        val reps = 30
        val length = 360.0

        val mS = Model("StationEnhanced", autoCSVReports = false)
        val sf = StemFairEnhancedStation(mS, "Mixer")
        mS.numberOfReplications = reps
        mS.lengthOfReplication = length
        mS.simulate()
        val stationAvg = sf.network.systemTime.acrossReplicationStatistic.average

        val mL = Model("LegacyEnhanced", autoCSVReports = false)
        StemFairMixerEnhancedSched(mL, "Mixer")
        mL.numberOfReplications = reps
        mL.lengthOfReplication = length
        mL.simulate()
        val legacyResp = mL.response("OverallSystemTime")
        if (legacyResp == null || legacyResp.acrossReplicationStatistic.average.isNaN()) {
            println("legacy OverallSystemTime not available; skipping comparison")
            return
        }
        val legacyAvg = legacyResp.acrossReplicationStatistic.average

        // Both should be in the order of 10s to 100 minutes; allow a generous band.
        assertTrue(stationAvg in 1.0..120.0)
        assertTrue(legacyAvg in 1.0..120.0)
        val relErr = abs(stationAvg - legacyAvg) / legacyAvg
        // Loose: simplification (single recruiter, no closing) means we don't expect
        // a tight match, but they should be in the same order of magnitude.
        assertTrue(
            relErr < 2.0,
            "station system time $stationAvg vs legacy $legacyAvg differ by ${(relErr * 100).toInt()}% (>200%)"
        )
    }
}
