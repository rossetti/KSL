package ksl.simulation

import ksl.examples.book.chapter7.ResourcePoolExample
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertTrue

/**
 * Regression tests for ResourcePoolExample (Chapter 7, Ch7Example3).
 *
 * ResourcePoolExample models two resource pools sharing resources:
 *  - pool1: {John, Paul, George}  — 3 resources
 *  - pool2: {Ringo, George}       — 2 resources (George is shared)
 * Arriving customers are routed to pool1 or pool2 with probability 0.5 each.
 *
 * Streams: arrivals = ExponentialRV(mean=1, stream 1),
 *          service  = ExponentialRV(mean=3, stream 2),
 *          routing  = BernoulliRV(p=0.5,   stream 3).
 *
 * ResourcePoolExample has no public properties; all statistics are accessed
 * via the model's response and counter lists by name.
 *
 * Three test tiers:
 *  - Smoke     : structural checks
 *  - Analytical: utilisation and WIP sanity bounds
 *  - Golden    : exact bit-identical values (fast config: 5 reps × 2 000 min,
 *                warm-up 500, default KSL seed)
 *
 * Golden constants start as Double.NaN (discovery mode).  When NaN the test
 * prints the current value and passes; replace NaN with the printed value to
 * enable the exact-equality guard.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResourcePoolingTest {

    // ── Configuration ─────────────────────────────────────────────────────────

    companion object {
        private const val FAST_REPS   = 5
        private const val FAST_LENGTH = 2000.0
        private const val FAST_WARMUP = 500.0

        // Golden values — ResourcePoolExample
        // Fast config: 5 reps × 2 000 min, warm-up 500 | default KSL seed
        private const val WIP1_AVG  = 2.3359260209570376
        private const val WIP1_VAR  = 0.045363158043284495
        private const val TIS1_AVG  = 4.619535201752069
        private const val TIS1_VAR  = 0.2235867424425525
        private const val WIP2_AVG  = 3.3907266405375323
        private const val WIP2_VAR  = 0.26637661479564817
        private const val TIS2_AVG  = 6.8597585975398925
        private const val TIS2_VAR  = 0.8123299878393663
    }

    // ── Shared simulation state ───────────────────────────────────────────────

    private lateinit var rpModel: Model

    @BeforeAll
    fun runSimulation() {
        rpModel = Model("ResourcePool-Test")
        rpModel.numberOfReplications     = FAST_REPS
        rpModel.lengthOfReplication      = FAST_LENGTH
        rpModel.lengthOfReplicationWarmUp = FAST_WARMUP
        ResourcePoolExample(rpModel)
        rpModel.simulate()
    }

    // ── Tier 1: Smoke ─────────────────────────────────────────────────────────

    @Test
    fun resourcePoolHasExpectedResponses() {
        assertTrue(rpModel.responses.isNotEmpty(), "Model must have responses")
        val names = rpModel.responseNames
        assertTrue(names.any { "WIP1"         in it }, "Expected WIP1 response")
        assertTrue(names.any { "TimeInSystem1" in it }, "Expected TimeInSystem1 response")
        assertTrue(names.any { "WIP2"         in it }, "Expected WIP2 response")
        assertTrue(names.any { "TimeInSystem2" in it }, "Expected TimeInSystem2 response")
    }

    @Test
    fun resourcePoolAcrossReplicationCountEqualsReplications() {
        val wip1 = rpModel.responses.first { "WIP1" in it.name }
        assertEquals(FAST_REPS.toDouble(), wip1.acrossReplicationStatistic.count, 0.0)
    }

    // ── Tier 2: Analytical Bounds ─────────────────────────────────────────────

    @Test
    fun wip1AndWip2ArePositive() {
        val wip1 = rpModel.responses.first { "WIP1" in it.name }.acrossReplicationStatistic.average
        val wip2 = rpModel.responses.first { "WIP2" in it.name }.acrossReplicationStatistic.average
        assertTrue(wip1 > 0.0, "WIP1 must be positive; got $wip1")
        assertTrue(wip2 > 0.0, "WIP2 must be positive; got $wip2")
    }

    @Test
    fun timeInSystemValuesArePositive() {
        val tis1 = rpModel.responses.first { "TimeInSystem1" in it.name }.acrossReplicationStatistic.average
        val tis2 = rpModel.responses.first { "TimeInSystem2" in it.name }.acrossReplicationStatistic.average
        assertTrue(tis1 > 0.0, "TimeInSystem1 must be positive; got $tis1")
        assertTrue(tis2 > 0.0, "TimeInSystem2 must be positive; got $tis2")
    }

    @Test
    fun resourceUtilisationsAreBetweenZeroAndOne() {
        // Each named resource (John, Paul, George, Ringo) exposes a utilisation response.
        // George is shared between pool1 and pool2 so his utilisation may be higher than
        // the others; all must remain in [0, 1].
        val utilisationResponses = rpModel.responses.filter { "Util" in it.name || "util" in it.name }
        assertTrue(utilisationResponses.isNotEmpty(), "Expected at least one utilisation response")
        for (r in utilisationResponses) {
            val u = r.acrossReplicationStatistic.average
            assertTrue(u in 0.0..1.0, "Utilisation ${r.name} = $u is out of [0,1]")
        }
    }

    // ── Tier 3: Golden Values ─────────────────────────────────────────────────

    @Test
    fun wip1Golden() {
        val stat = rpModel.responses.first { "WIP1" in it.name }.acrossReplicationStatistic
        assertGolden(WIP1_AVG, stat.average,  "WIP1_AVG")
        assertGolden(WIP1_VAR, stat.variance, "WIP1_VAR")
    }

    @Test
    fun timeInSystem1Golden() {
        val stat = rpModel.responses.first { "TimeInSystem1" in it.name }.acrossReplicationStatistic
        assertGolden(TIS1_AVG, stat.average,  "TIS1_AVG")
        assertGolden(TIS1_VAR, stat.variance, "TIS1_VAR")
    }

    @Test
    fun wip2Golden() {
        val stat = rpModel.responses.first { "WIP2" in it.name }.acrossReplicationStatistic
        assertGolden(WIP2_AVG, stat.average,  "WIP2_AVG")
        assertGolden(WIP2_VAR, stat.variance, "WIP2_VAR")
    }

    @Test
    fun timeInSystem2Golden() {
        val stat = rpModel.responses.first { "TimeInSystem2" in it.name }.acrossReplicationStatistic
        assertGolden(TIS2_AVG, stat.average,  "TIS2_AVG")
        assertGolden(TIS2_VAR, stat.variance, "TIS2_VAR")
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun assertGolden(expected: Double, actual: Double, name: String) {
        if (expected.isNaN()) {
            println("GOLDEN_DISCOVERY: $name = $actual")
            return
        }
        assertEquals(expected, actual, 0.0, "Golden regression failed for $name")
    }
}
