package ksl.modeling.decision

import ksl.modeling.decision.descriptor.RewardSense
import ksl.modeling.variable.TWResponse
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  How a `TWResponse` maintains its area, and why the reward path reads it rather than flushing it.
 *
 *  **KSL already solves this problem, and solves it with a write.** `TWResponse` overrides
 *  `initialize`, `timedUpdate`, `warmUp` and `replicationEnded`, and each does `value = value`:
 *
 *  ```
 *  override fun timedUpdate()      { super.timedUpdate();     value = value }
 *  override fun warmUp()           { super.warmUp(); timeOfChange = time; value = value }
 *  override fun replicationEnded() { super.replicationEnded(); value = value }
 *  ```
 *
 *  A self-assignment is not a no-op. `assignValue` banks `previousValue × (time − previousTimeOfChange)`
 *  into the within-replication statistic and advances `timeOfChange` to now — so `value = value` is
 *  precisely *"flush the area in flight"*. That is why `timedUpdate` uses it: a periodic report has
 *  to see the area up to and including the current instant, which is the same requirement §4.2.5's
 *  reward has at every epoch.
 *
 *  So the obvious mitigation for §8.1.4's defect B is to do what KSL does: assign the source to
 *  itself at each epoch, then difference plain `weightedSum`. **This design cannot**, and the two
 *  tests below establish why by measurement rather than by argument.
 */
class TimeWeightedAccumulationTest {

    /** A level that changes rarely — the shape where the lag is the whole interval. */
    private class Tank(parent: ModelElement, name: String) : ModelElement(parent, name) {
        val level = TWResponse(this, name = "$name:Level", initialValue = 2.0)
        var probe: (() -> Unit)? = null

        override fun initialize() {
            // One genuine change at t = 20, so there is both a "held" segment and a banked one.
            schedule({ level.value = 5.0 }, 20.0)
            for (t in listOf(5.0, 15.0, 25.0, 40.0)) schedule({ probe?.invoke() }, t)
        }

        private fun schedule(action: () -> Unit, at: Double) =
            schedule<Nothing>({ _: KSLEvent<Nothing> -> action() }, at)
    }

    /** The area under the level curve up to [t], by hand: 2.0 until 20, then 5.0. */
    private fun trueArea(t: Double): Double =
        if (t <= 20.0) 2.0 * t else 2.0 * 20.0 + 5.0 * (t - 20.0)

    /**
     *  The read-only form is exact at every instant, including the ones where `weightedSum` alone
     *  is stale — which is every instant between changes.
     */
    @Test
    fun theReadOnlyFormIsExactWhereWeightedSumAloneIsStale() {
        val model = Model("ReadOnlyArea")
        val tank = Tank(model, "T")
        val readings = mutableListOf<Triple<Double, Double, Double>>()
        val accessor = rewardSourceFor(tank.level) { tank.time }
        tank.probe = {
            readings += Triple(
                tank.time,
                tank.level.withinReplicationStatistic.weightedSum,   // what KSL has banked
                accessor.accumulated()                                // banked + in flight
            )
        }
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        model.simulate()

        println()
        println("level 2.0 until t=20, then 5.0:")
        println("   time   weightedSum   accumulated()   true area")
        for ((t, banked, acc) in readings) {
            println("  %5.1f   %11.1f   %13.1f   %9.1f".format(t, banked, acc, trueArea(t)))
        }

        for ((t, banked, acc) in readings) {
            assertEquals(trueArea(t), acc, 1e-9, "accumulated() must be the true area at t = $t")
            if (t != 20.0) {
                assertTrue(banked < acc,
                    "at t = $t the banked sum lags by the segment in flight; that lag is the defect")
            }
        }
        // At t = 5, nothing has been banked at all: weightedSum is 0 while the true area is 10.
        assertEquals(0.0, readings.first().second, 1e-9)
        assertEquals(10.0, readings.first().third, 1e-9)
    }

    /**
     *  **The disqualifying measurement.** Flushing with `value = value` computes the right number
     *  and changes the model, and §6.2 Level 2 is asserted at the grain that sees it.
     *
     *  `assignValue` does three observable things beyond banking the area: it collects an
     *  observation into the within-replication statistic, it notifies model-element observers, and
     *  it emits to any attached emitter. §4.4's no-op elision exists because of exactly this —
     *  *"writing a value back is not a no-op in KSL"* — so a reward path that flushed its sources
     *  would reintroduce, in the reward machinery, the fault the action machinery is built to avoid.
     *
     *  Both arms below run the identical model. One reads; one flushes. The areas agree to 1e-9 and
     *  the observation counts do not.
     */
    @Test
    fun flushingTheSourceWouldComputeTheSameAreaAndBreakLevelTwo() {
        fun run(flush: Boolean): Triple<Double, Double, Int> {
            val model = Model("Flush-$flush")
            val tank = Tank(model, "T")
            var lastArea = 0.0
            var notifications = 0
            tank.level.attachModelElementObserver(object : ksl.observers.ModelElementObserver() {
                override fun update(modelElement: ModelElement) { notifications++ }
            })
            val accessor = rewardSourceFor(tank.level) { tank.time }
            tank.probe = {
                if (flush) {
                    // KSL's own idiom, applied from the reward path.
                    tank.level.value = tank.level.value
                    lastArea = tank.level.withinReplicationStatistic.weightedSum
                } else {
                    lastArea = accessor.accumulated()
                }
            }
            model.numberOfReplications = 1
            model.lengthOfReplication = 50.0
            model.simulate()
            return Triple(lastArea, tank.level.withinReplicationStatistic.count, notifications)
        }

        val (readArea, readCount, readNotices) = run(flush = false)
        val (flushArea, flushCount, flushNotices) = run(flush = true)

        println()
        println("Four probes over one replication, identical models:")
        println("  read only : area %.1f at the last probe, %.0f observations, %d observer notices"
            .format(readArea, readCount, readNotices))
        println("  flushing  : area %.1f at the last probe, %.0f observations, %d observer notices"
            .format(flushArea, flushCount, flushNotices))

        assertEquals(readArea, flushArea, 1e-9,
            "both compute the same area — the disagreement is not arithmetic")
        assertTrue(flushCount > readCount,
            "flushing adds an observation to the source's within-replication statistic every time " +
                "it is called; that count is exactly what §6.2's fine grain compares, so a reward " +
                "path that flushed would fail Level 2 for every source it reads")
        assertTrue(flushNotices > readNotices,
            "and it notifies model-element observers, so a ResponseTrace on the source would show " +
                "entries the unmodified model never produced")
        println("  → flushing adds ${flushCount - readCount} observations and " +
            "${flushNotices - readNotices} notices that the unmodified model does not have")
    }

    /**
     *  The property that makes the read-only form safe to call from anywhere in the lifecycle:
     *  it does not care whether the source has flushed itself yet.
     *
     *  This matters because `replicationEnded()` runs on both the source and the decision element,
     *  in model-element construction order, and §4.10.3 has the element close its final interval
     *  there. If the formula were order-dependent, the last row of every trajectory would be right
     *  or wrong according to which element happened to be declared first — the class of defect
     *  §4.10.3 already avoids for the reward baseline by refusing to read sources in `initialize()`.
     */
    @Test
    fun theReadOnlyFormDoesNotDependOnWhetherTheSourceHasFlushedYet() {
        val model = Model("Ordering")
        val tank = Tank(model, "T")
        val accessor = rewardSourceFor(tank.level) { tank.time }
        var beforeFlush = 0.0
        var afterFlush = 0.0
        tank.probe = {
            if (tank.time == 40.0) {
                beforeFlush = accessor.accumulated()
                tank.level.value = tank.level.value      // simulate the source flushing first
                afterFlush = accessor.accumulated()
            }
        }
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        model.simulate()

        println()
        println("accumulated() at t = 40, either side of the source flushing itself:")
        println("  before flush: $beforeFlush")
        println("  after flush : $afterFlush")
        assertEquals(trueArea(40.0), beforeFlush, 1e-9)
        assertEquals(beforeFlush, afterFlush, 1e-9,
            "the banked half and the in-flight half trade places; the sum is invariant")
    }

    /**
     *  And what happens across a warm-up — where the answer depends on *which side of the warm-up
     *  event* the read happens, and that is not a wrinkle but the whole of §4.6.4.
     *
     *  `TWResponse.warmUp()` resets the within-replication statistic and sets `timeOfChange = time`
     *  before its self-assignment, so the pre-warm-up area is discarded rather than banked. A read
     *  *after* that sees area measured from the warm-up instant. A read at the same instant but
     *  **earlier in the event order** — which is where a probe at default priority lands, since
     *  `DEFAULT_WARMUP_EVENT_PRIORITY` sorts last — still sees the whole pre-warm-up area.
     *
     *  This is why §4.10.3 has the element **invalidate its baseline** at warm-up instead of
     *  carrying it across. Keeping it would make the first post-warm-up interval's reward the
     *  difference between a pre-warm-up reading and a post-warm-up one: 65.0 subtracted from
     *  something measured on a statistic that has since been reset to zero. The number would be
     *  negative, large, and entirely fictitious. Discarding one interval is the price of not
     *  needing to know any of this.
     */
    @Test
    fun aReadAcrossTheWarmUpDependsOnEventOrder_whichIsWhyTheBaselineIsInvalidated() {
        val model = Model("WarmUpArea")
        val tank = Tank(model, "T")
        val accessor = rewardSourceFor(tank.level) { tank.time }
        val readings = mutableListOf<Pair<Double, Double>>()
        tank.probe = { if (tank.time >= 25.0) readings += tank.time to accessor.accumulated() }
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        model.lengthOfReplicationWarmUp = 25.0
        model.simulate()

        println()
        println("warm-up at 25; level 2.0 until t=20 then 5.0:")
        for ((t, a) in readings) {
            val side = if (t == 25.0) "at the warm-up instant, BEFORE the warm-up event" else "after"
            println("  accumulated() at %5.1f = %6.1f   ($side)".format(t, a))
        }

        val atWarmUp = readings.first { it.first == 25.0 }.second
        val after = readings.first { it.first > 25.0 }

        assertEquals(65.0, atWarmUp, 1e-9,
            "a probe at default priority runs before DEFAULT_WARMUP_EVENT_PRIORITY, so it still " +
                "sees the whole pre-warm-up area — 2.0x20 + 5.0x5")
        assertEquals(5.0 * (after.first - 25.0), after.second, 1e-9,
            "once the warm-up event has run, the area is measured from the warm-up instant")

        // The two readings straddle a reset, so differencing them is meaningless. What makes that
        // worth an assertion rather than a remark is HOW it fails: not with a negative number or a
        // NaN, but with a plausible small one.
        val ifBaselineWereCarried = after.second - atWarmUp      // 75 - 65 = 10
        val truth = 5.0 * (after.first - 25.0)                   // 75
        println("  a baseline carried across the warm-up would report %.1f for [25, %.0f]; the "
            .format(ifBaselineWereCarried, after.first) + "true area is %.1f".format(truth))
        assertEquals(10.0, ifBaselineWereCarried, 1e-9)
        assertTrue(ifBaselineWereCarried in 0.0..truth,
            "the fiction is POSITIVE and smaller than the truth — %.1f against %.1f, an 87%% "
                .format(ifBaselineWereCarried, truth) +
                "understatement that no sanity check would catch. §4.10.3 invalidates the baseline " +
                "so this subtraction is never performed")
    }
}
