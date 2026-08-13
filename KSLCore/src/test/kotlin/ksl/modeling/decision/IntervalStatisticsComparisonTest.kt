package ksl.modeling.decision

import ksl.modeling.variable.ResponseInterval
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TimeSeriesResponse
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  KSL already collects statistics over repeating intervals, in two places, and §5 commits this work
 *  to using what exists rather than building an equivalent. So before `ObservationKind`
 *  (§4.2.4.1) is implemented, the question is whether `ResponseInterval` and `TimeSeriesResponse`
 *  are the thing to delegate to.
 *
 *  **They use the same algorithm as `RewardBinding`, and they were written first.** Both snapshot
 *  `weightedSum`, `sumOfWeights` and `count` at the interval start and difference them at the end:
 *
 *  ```kotlin
 *  val sum    = w.weightedSum      - data.mySumAtStart
 *  val denom  = w.sumOfWeights     - data.mySumOfWeightsAtStart
 *  val numObs = w.count            - data.myNumObsAtStart
 *  if (numObs == 0.0) { if (key is TWResponse) data.myResponse.value = key.value }
 *  else if (denom != 0.0)          data.myResponse.value = sum / denom
 *  ```
 *
 *  That is convergent evidence for the shape of §4.2.5's reward: difference an accumulation across
 *  a boundary, and handle the no-observations case specially. The design did not invent it.
 *
 *  It is also where the two part company, and this test exists to establish which of them is right
 *  by measurement rather than by reading. §8.1.4's defect B was `weightedSum` lagging behind the
 *  area in flight; these classes read the same lagging quantity, at both ends.
 */
class IntervalStatisticsComparisonTest {

    /**
     *  A level of 2.0 that becomes 5.0 at *t* = 15, and never changes again.
     *
     *  Over the interval [10, 20] the true time-average is `(2.0×5 + 5.0×5) / 10 = 3.5`.
     */
    private class Tank(parent: ModelElement, name: String) : ModelElement(parent, name) {
        val level = TWResponse(this, name = "$name:Level", initialValue = 2.0)
        var probe: ((Double) -> Unit)? = null

        override fun initialize() {
            schedule<Nothing>({ _: KSLEvent<Nothing> -> level.value = 5.0 }, 15.0)
            for (t in listOf(10.0, 20.0)) schedule<Nothing>({ _: KSLEvent<Nothing> -> probe?.invoke(time) }, t)
        }
    }

    private val trueAverage = (2.0 * 5.0 + 5.0 * 5.0) / 10.0    // 3.5 over [10, 20]

    /**
     *  `ResponseInterval`, `TimeSeriesResponse` and the reward accumulator, over the same interval
     *  of the same model.
     *
     *  The first two report the average over `[timeOfChange_at_start, timeOfChange_at_end]` rather
     *  than over `[start, end]`, because `weightedSum` and `sumOfWeights` both stop at the last
     *  change. When the value moves once mid-interval, that window is not the interval.
     */
    @Test
    fun theTwoKslFacilitiesAndTheRewardAccumulatorDisagreeOnAMidIntervalChange() {
        val model = Model("IntervalCompare")
        val tank = Tank(model, "T")

        val interval = ResponseInterval(tank, theDuration = 10.0, label = "Window")
        interval.startTime = 10.0
        val intervalAvg = interval.addResponseToInterval(tank.level)

        val series = TimeSeriesResponse(tank, periodLength = 10.0, numPeriods = 2, response = tank.level)

        // The reward path's accumulator, sampled at the same two instants.
        val accessor = rewardSourceFor(tank.level)
        var areaAtStart = 0.0
        var areaAtEnd = 0.0
        tank.probe = { t -> if (t == 10.0) areaAtStart = accessor.accumulated() else areaAtEnd = accessor.accumulated() }

        model.numberOfReplications = 1
        model.lengthOfReplication = 25.0
        model.simulate()

        val rewardAvg = (areaAtEnd - areaAtStart) / 10.0
        val intervalReported = intervalAvg.withinReplicationStatistic.weightedAverage
        val seriesReported = series.responsePeriodDataAsList(tank.level)
            .first { it.period == 2 }.value!!

        println()
        println("Level 2.0 until t=15, then 5.0. Interval [10, 20]:")
        println("  true time-average over the interval : %.4f".format(trueAverage))
        println("  ResponseInterval                    : %.4f".format(intervalReported))
        println("  TimeSeriesResponse (period 2)       : %.4f".format(seriesReported))
        println("  reward accumulator, differenced     : %.4f".format(rewardAvg))
        println()
        println("  area at t=10 : %.1f   area at t=20 : %.1f   (both include the segment in flight)"
            .format(areaAtStart, areaAtEnd))

        assertEquals(trueAverage, rewardAvg, 1e-9,
            "the reward accumulator adds the in-flight segment at both ends, so its difference is " +
                "the exact area over the interval (§8.1.4)")

        assertEquals(intervalReported, seriesReported, 1e-9,
            "ResponseInterval and TimeSeriesResponse implement the same algorithm and must agree")

        // **This assertion is inverted from what it was, and the inversion is the point.**
        //
        // It used to require the two KSL facilities to DISAGREE with the true average, because
        // they reported over [lastChangeBefore(start), timeOfChange] rather than over the
        // interval — the same in-flight-segment defect §8.1.4 found in the reward accumulator,
        // present in KSL's own interval machinery. §5.1 cited that difference as the reason this
        // subsystem accumulates rewards itself instead of reusing those facilities.
        //
        // KSL fixed it upstream, independently, in `c555be2` "Include the segment in progress when
        // an interval reads a time-weighted response". All three now agree, and the justification
        // §5.1 rested on is gone. The response is not to keep asserting a disagreement that no
        // longer exists but to assert the agreement, and to delegate: `rewardSourceFor` now reads
        // `TWResponse.withinReplicationWeightedSum` rather than recomputing the same expression,
        // so there is one definition of "area so far" in the library instead of two.
        assertEquals(trueAverage, intervalReported, 1e-9,
            "ResponseInterval now includes the segment in progress, so it reports the average over " +
                "the interval asked for. If this fails, the upstream fix has regressed and §5.1's " +
                "reuse argument needs revisiting again")
        println()
        println("  → all three agree: the two KSL facilities and the reward accumulator now")
        println("    compute the same area, and the accumulator reads KSL's accessor for it")
    }

    /**
     *  And where they are exact: an interval with **no** change in it.
     *
     *  `numObs == 0` is special-cased to the current value, on the reasoning that a constant height
     *  over the window has that height as its average. That case is exact, and it is the common one
     *  — which is why the discrepancy above is easy to miss.
     */
    @Test
    fun bothFormsAgreeExactlyWhenNothingChangesInsideTheInterval() {
        val model = Model("QuietInterval")
        val tank = Tank(model, "T")
        // [30, 40] is entirely after the single change at t = 15.
        val interval = ResponseInterval(tank, theDuration = 10.0, label = "Quiet")
        interval.startTime = 30.0
        val intervalAvg = interval.addResponseToInterval(tank.level)

        val accessor = rewardSourceFor(tank.level)
        var a30 = 0.0
        var a40 = 0.0
        tank.probe = { }
        val sampler = object : ModelElement(tank, "Sampler") {
            override fun initialize() {
                schedule<Nothing>({ _: KSLEvent<Nothing> -> a30 = accessor.accumulated() }, 30.0)
                schedule<Nothing>({ _: KSLEvent<Nothing> -> a40 = accessor.accumulated() }, 40.0)
            }
        }
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        model.simulate()

        val rewardAvg = (a40 - a30) / 10.0
        val reported = intervalAvg.withinReplicationStatistic.weightedAverage

        println()
        println("Interval [30, 40], no change inside it (the level moved at t=15):")
        println("  ResponseInterval  : %.4f".format(reported))
        println("  reward accumulator: %.4f".format(rewardAvg))
        println("  (sampler element ${sampler.name} exists only to read at the boundaries)")

        assertEquals(5.0, reported, 1e-9, "numObs == 0 is special-cased to the current value")
        assertEquals(5.0, rewardAvg, 1e-9, "and the accumulator agrees, by a different route")
    }

    /**
     *  A `Counter` over an interval is exact in both, and for the same reason: a counter's total is
     *  live, so there is nothing in flight to lag. §4.2.5's `COUNTER_TOTAL` differences `value`
     *  exactly as `ResponseInterval.EndIntervalAction` does — `key.value - data.myTotalAtStart`.
     *
     *  Recorded because it locates the disagreement precisely: it is not about differencing across
     *  a boundary, which all three do identically. It is about what a `TWResponse`'s accumulation
     *  reads mid-interval.
     */
    @Test
    fun aCounterOverAnIntervalIsExactInBothForms() {
        val model = Model("CounterInterval")
        val holder = object : ModelElement(model, "H") {
            val ticks = ksl.modeling.variable.Counter(this, name = "Ticks")
            override fun initialize() {
                for (t in 1..24) schedule<Nothing>({ _: KSLEvent<Nothing> -> ticks.increment() }, t.toDouble())
            }
        }
        val interval = ResponseInterval(holder, theDuration = 10.0, label = "Window")
        interval.startTime = 10.0
        val count = interval.addCounterToInterval(holder.ticks)

        model.numberOfReplications = 1
        model.lengthOfReplication = 25.0
        model.simulate()

        println()
        println("One increment per unit time, interval [10, 20]:")
        println("  ResponseInterval counter total: ${count.withinReplicationStatistic.weightedAverage}")
        assertEquals(10.0, count.withinReplicationStatistic.weightedAverage, 1e-9,
            "ten increments in ten units; a counter has nothing in flight, so the snapshot " +
                "difference is exact")
    }
}
