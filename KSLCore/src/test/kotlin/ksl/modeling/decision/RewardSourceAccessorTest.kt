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

        override fun initialize() {
            schedule(::tick, 1.0)
        }

        private fun tick(e: ksl.simulation.KSLEvent<Nothing>) {
            perObservation.value = 3.0          // three observations of 3.0 -> sum 9.0
            timeWeighted.value = 2.0            // held at 2.0 from t=1 to t=3 -> area 4.0 by t=3
            occurrences.increment(1.0)
            if (time < 3.0) schedule(::tick, 1.0)
        }
    }

    /**
     *  The three adapters M2 will need, written here so that what each reads is a fact rather
     *  than a claim. Each returns the quantity whose difference between epochs is the interval's
     *  contribution (§4.2.5).
     */
    private fun accumulatorFor(source: Any): RewardSourceCIfc = when (source) {
        is TWResponse -> object : RewardSourceCIfc {
            override val name = source.name
            override fun value(): Double = source.value
            // The time integral: weightedSum over a TimeWeightedStatistic weights by duration.
            override fun accumulated(): Double = source.withinReplicationStatistic.weightedSum
        }
        is Response -> object : RewardSourceCIfc {
            override val name = source.name
            override fun value(): Double = source.value
            // Unit weights, so weightedSum is the plain sum of the values observed.
            override fun accumulated(): Double = source.withinReplicationStatistic.weightedSum
        }
        is Counter -> object : RewardSourceCIfc {
            override val name = source.name
            override fun value(): Double = source.value
            // A Counter has NO withinReplicationStatistic. Its running total IS its value.
            override fun accumulated(): Double = source.value
        }
        else -> error("not a reward source: ${source::class.simpleName}")
    }

    @Test
    fun eachRewardKindAccumulatesThroughTheAccessorThatActuallyExists() {
        val model = Model("RewardSources")
        val s = Sources(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 3.0
        model.simulate()

        val obs = accumulatorFor(s.perObservation)
        val twr = accumulatorFor(s.timeWeighted)
        val cnt = accumulatorFor(s.occurrences)

        println()
        println("Accumulated quantity at t = 3, three ticks at t = 1, 2, 3:")
        println("  OBSERVATION_SUM (Response)   %8.2f   via withinReplicationStatistic.weightedSum"
            .format(obs.accumulated()))
        println("  TIME_INTEGRAL   (TWResponse) %8.2f   via withinReplicationStatistic.weightedSum"
            .format(twr.accumulated()))
        println("  COUNTER_TOTAL   (Counter)    %8.2f   via value — it has no within-replication statistic"
            .format(cnt.accumulated()))

        assertEquals(9.0, obs.accumulated(), 1e-9, "three observations of 3.0 should sum to 9.0")
        assertEquals(4.0, twr.accumulated(), 1e-9, "held at 2.0 from t=1 to t=3 is an area of 4.0")
        assertEquals(3.0, cnt.accumulated(), 1e-9, "three increments of 1.0")
    }

    /**
     *  The assertion that would have caught the prose defect: a `Counter` does not have the member
     *  §4.2.5 said all three sources share. Stated as a property of the KSL type rather than of
     *  this design, because that is what it is.
     */
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
        val g: GetValueIfc = accumulatorFor(s.occurrences)
        assertEquals(3.0, g.value(), 1e-9)
    }
}
