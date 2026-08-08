package ksl.modeling.decision

import ksl.modeling.variable.Counter
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.GetValueIfc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  What "the accumulated quantity" actually is, for each of the three reward kinds (§4.2.5).
 *
 *  §4.2.5 said, for several revisions, that *all three* read `weightedSum` from the source's
 *  within-replication statistic and differ only in what the weights mean. That is true of
 *  `Response` and `TWResponse` and **false of `Counter`**, which has no `withinReplicationStatistic`
 *  at all — `CounterCIfc` is `ValueIfc` plus `acrossReplicationStatistic`, and the running total is
 *  `value`.
 *
 *  This is the same shape as §8.1.1's defect 2, where the design assumed `ResponseIfc` was a
 *  `GetValueIfc` and it was not. Both were assumptions about a KSL interface that a reading could
 *  not settle and a compile could. So this test pins the three accessors against real objects
 *  before M2's `RewardBinding` is written on top of them, rather than after.
 *
 *  `RewardSourceCIfc.accumulated()` is the abstraction that makes the difference invisible to
 *  everything downstream — the design already had it; only the prose disagreed.
 */
class RewardSourceAccessorTest {

    private class Sources(parent: ModelElement) : ModelElement(parent, "Sources") {
        /** OBSERVATION_SUM: a rate per observation. */
        val perObservation = Response(this, name = "PerObservation")
        /** TIME_INTEGRAL: a rate per unit per unit time. */
        val timeWeighted = TWResponse(this, name = "TimeWeighted", initialValue = 0.0)
        /** COUNTER_TOTAL: a rate per occurrence. */
        val occurrences = Counter(this, name = "Occurrences")

        /** Called at t = 1.5 and t = 2.5 — mid-interval, where a lag is visible. */
        var probe: (() -> Unit)? = null

        override fun initialize() {
            schedule(::tick, 1.0)
            schedule(::peek, 1.5)
            schedule(::peek, 2.5)
        }

        private fun peek(e: ksl.simulation.KSLEvent<Nothing>) { probe?.invoke() }

        private fun tick(e: ksl.simulation.KSLEvent<Nothing>) {
            perObservation.value = 3.0          // three observations of 3.0 -> sum 9.0
            timeWeighted.value = 2.0            // held at 2.0 from t=1 to t=3 -> area 4.0 by t=3
            occurrences.increment(1.0)
            if (time < 3.0) schedule(::tick, 1.0)
        }
    }

    /**
     *  The accumulated quantity, read **during** the run — which is the only reading that matters,
     *  and the one an earlier version of this test did not take.
     *
     *  It read after the replication ended, when KSL has flushed a time-weighted statistic's final
     *  area, and so it asserted the right numbers while being blind to the defect §4.10.4's matrix
     *  later found: `TimeWeightedStatistic` banks the previous value's area only when a NEW value
     *  arrives, so mid-run `weightedSum` lags by everything accrued since `timeOfChange`. On a
     *  quantity that changes rarely — on-hand inventory, a capacity, a queue empty for a shift —
     *  that lag is the whole interval, and every interval reward reads as zero.
     *
     *  The lesson is narrower than "test more": a test that samples only at the one instant the
     *  system tidies itself up cannot see a lag, and end-of-run is exactly that instant.
     */
    @Test
    fun eachRewardKindAccumulatesThroughTheAccessorThatActuallyExists() {
        val model = Model("RewardSources")
        val s = Sources(model)
        val readings = mutableListOf<Triple<Double, Double, Double>>()
        s.probe = {
            readings += Triple(
                rewardSourceFor(s.perObservation) { s.time }.accumulated(),
                rewardSourceFor(s.timeWeighted) { s.time }.accumulated(),
                rewardSourceFor(s.occurrences) { s.time }.accumulated()
            )
        }
        model.numberOfReplications = 1
        model.lengthOfReplication = 3.0
        model.simulate()

        println()
        println("Accumulated quantity read at t = 1.5 and 2.5, with the level held at 2.0 throughout:")
        readings.forEachIndexed { i, (obs, twr, cnt) ->
            println("  reading %d: OBSERVATION_SUM %6.2f   TIME_INTEGRAL %6.2f   COUNTER_TOTAL %6.2f"
                .format(i + 1, obs, twr, cnt))
        }

        assertTrue(readings.size == 2, "expected two mid-run readings; got ${readings.size}")
        val (obs1, twr1, cnt1) = readings[0]
        val (obs2, twr2, cnt2) = readings[1]

        // OBSERVATION_SUM: one observation of 3.0 by t=1.5, two by t=2.5.
        assertEquals(3.0, obs1, 1e-9)
        assertEquals(6.0, obs2, 1e-9)
        // COUNTER_TOTAL: the running count, live.
        assertEquals(1.0, cnt1, 1e-9)
        assertEquals(2.0, cnt2, 1e-9)
        // TIME_INTEGRAL: held at 2.0 from t=1, so area 1.0 by t=1.5 and 3.0 by t=2.5. This is the
        // assertion the end-of-run version could not make — weightedSum alone reports 0.0 at both.
        assertEquals(1.0, twr1, 1e-9,
            "the area since timeOfChange must be included; weightedSum alone would say 0.0")
        assertEquals(3.0, twr2, 1e-9)

        // And the difference between two readings is what a reward term actually uses.
        assertEquals(2.0, twr2 - twr1, 1e-9, "one unit of time at level 2.0")
    }

    @Test
    fun aCounterHasNoWithinReplicationStatistic() {
        val members = Counter::class.members.map { it.name }.toSet()
        println()
        println("Counter exposes: ${members.filter { "eplication" in it || it == "value" }.sorted()}")
        assertTrue("withinReplicationStatistic" !in members,
            "a Counter now has a within-replication statistic; §4.2.5 may be simplified to read " +
                "one accessor for all three reward kinds after all")
        assertTrue("value" in members, "a Counter's running total is its value")
        assertTrue("acrossReplicationStatistic" in members)
    }

    /** `RewardSourceCIfc` extends `GetValueIfc`, so an adapter is usable wherever a value is. */
    @Test
    fun aRewardSourceIsAlsoAPlainValue() {
        val model = Model("RewardSourceAsValue")
        val s = Sources(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 3.0
        model.simulate()
        val g: GetValueIfc = rewardSourceFor(s.occurrences) { s.time }
        assertEquals(3.0, g.value(), 1e-9)
    }
}
