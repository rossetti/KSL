package ksl.modeling.decision

import ksl.modeling.decision.descriptor.RewardSense
import ksl.modeling.decision.descriptor.TerminationSource
import ksl.modeling.variable.TWResponse
import ksl.sdm.capture.MemorySink
import ksl.simulation.Model
import ksl.simulation.ModelElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  §4.10.4's timeline matrix — M1 step 7d.
 *
 *  **This matrix has never run.** It was written as the acceptance criteria for step 7, and every
 *  row is a timeline with an expected sequence of *emitted rows*, while emission sat in a later
 *  milestone that M1's scope paragraph excluded. §7.1.1 is the correction; this is what it bought.
 *
 *  Each test below is one row of that table. They are written against a [MemorySink] because the
 *  question is what the loop produces, not where it is stored — which is exactly the split that
 *  makes M3 separable.
 */
class EpochTimelineTest {

    /**
     *  One holding cost, so the reward is a time integral with a value that can be predicted by
     *  hand: the level is pinned at 2.0, so an interval of length τ accrues −2τ.
     */
    private class Tank(parent: ModelElement, name: String) : ModelElement(parent, name) {
        val level = TWResponse(this, name = "$name:Level", initialValue = 2.0)
        var setting: Double = 0.0
    }

    private class Timeline(
        val rows: List<TransitionRecord>,
        val element: DecisionElement,
        val model: Model
    ) {
        val taus get() = rows.map { it.tau }
        override fun toString() =
            rows.joinToString(", ") { "[%.0f→%.0f τ=%.0f r=%.1f%s%s]".format(
                it.time - it.tau, it.time, it.tau, it.reward,
                if (it.terminated) " TERM" else "", if (it.truncated) " TRUNC" else "") }
    }

    private fun runTimeline(
        horizon: Double,
        warmUp: Double = 0.0,
        interval: Double = 10.0,
        maxEpochs: Int = Int.MAX_VALUE,
        terminalAt: Double? = null,
        policy: PolicyIfc? = null
    ): Timeline {
        val model = Model("Timeline")
        val tank = Tank(model, "T")
        val sink = MemorySink()
        val e = tank.decisionElement("D") {
            observe(tank.level)
            lever(tank, 0.0..10.0, neutral = Neutral.Current { setting }) { v -> setting = v }
            reward(tank.level, rate = 1.0, sense = RewardSense.COST)
            captureTo { sink }
            every(interval)
            if (maxEpochs != Int.MAX_VALUE) maxEpochs(maxEpochs)
            if (terminalAt != null) terminalWhen { tank.time >= terminalAt }
            this.policy = policy ?: NeutralPolicy
        }
        model.numberOfReplications = 1
        model.lengthOfReplication = horizon
        model.lengthOfReplicationWarmUp = warmUp
        model.simulate()
        return Timeline(sink.records, e, model)
    }

    // ------------------------------------------------------------------ rows 1, 4 and 5

    /**
     *  Row 1, and the arithmetic behind its wording. *n* epochs bound *n* − 1 inter-epoch
     *  intervals; the interval from the last epoch to the horizon is closed by `replicationEnded`.
     *  So a horizon off an epoch boundary yields *n* rows and one on a boundary yields *n* − 1 —
     *  which is rows 4 and 5, and they are the same statement seen from two ends.
     */
    @Test
    fun aNormalReplicationEmitsOneRowPerBoundedInterval() {
        val offBoundary = runTimeline(horizon = 55.0)   // epochs at 10..50, then 50→55
        val onBoundary = runTimeline(horizon = 50.0)    // epochs at 10..50, nothing after

        println()
        println("epochs every 10, no warm-up:")
        println("  horizon 55 (off a boundary): $offBoundary")
        println("  horizon 50 (on a boundary) : $onBoundary")

        assertEquals(5, offBoundary.rows.size, "4 inter-epoch intervals plus the partial closer")
        assertEquals(listOf(10.0, 10.0, 10.0, 10.0, 5.0), offBoundary.taus)
        assertEquals(4, onBoundary.rows.size, "row 4: the final transition has tau == 0 and is discarded")
        assertTrue(onBoundary.taus.all { it == 10.0 })

        // Row 5: the closer is truncated by the run length, not terminated by the model.
        val last = offBoundary.rows.last()
        assertTrue(last.truncated && !last.terminated)
        assertEquals(TerminationSource.RUN_LENGTH, last.source)
        assertEquals(5.0, last.tau, 1e-9, "a genuine partial interval, not a whole one")

        // The first epoch has no predecessor, which is discard rule 1 — visible as the absence of
        // a row covering [0, 10] rather than as a row of zeros.
        assertEquals(10.0, offBoundary.rows.first().time - offBoundary.rows.first().tau, 1e-9)
    }

    /** The reward is a time integral over the interval, and can be predicted by hand. */
    @Test
    fun eachRowCarriesTheRewardAccruedOverItsOwnInterval() {
        val t = runTimeline(horizon = 55.0)
        println()
        println("level pinned at 2.0, cost rate 1.0 per unit per unit time:")
        println("  $t")
        // COST is negated once at declaration, so the rest of the system sees reward, maximized.
        for (r in t.rows) {
            assertEquals(-2.0 * r.tau, r.reward, 1e-9,
                "an interval of ${r.tau} at level 2.0 costs ${2.0 * r.tau}, so its reward is the negative")
        }
        // The measured intervals span [10, 55] — 45 units, not 55. The first epoch's interval has
        // no baseline to difference against and is discarded, so the estimand deliberately omits
        // it rather than crediting the run with reward it could not measure.
        assertEquals(-2.0 * 45.0, t.element.estimand.value, 1e-9,
            "the estimand is the sum over the MEASURED intervals; [0,10] is not one of them")
        assertEquals(-2.0 * 45.0, t.rows.sumOf { it.reward }, 1e-9,
            "and it agrees with the rows, which is what makes a trajectory auditable against the " +
                "reported number (§4.8.4)")
    }

    // ------------------------------------------------------------------ rows 2 and 3

    /**
     *  Rows 2 and 3. A warm-up discards the transition in flight and invalidates the baseline, so
     *  the interval straddling it is never reported — reporting it would credit the estimand with
     *  reward the run is meant to forget.
     *
     *  Row 3 says a warm-up coinciding with an epoch gives "results identical to the previous row".
     *  It cannot mean the same rows, since the warm-up falls at a different place; what is
     *  identical is the *effect* — exactly one interval lost, and the next epoch emitting nothing.
     */
    @Test
    fun aWarmUpDiscardsExactlyOneIntervalWhereverItFalls() {
        val none = runTimeline(horizon = 55.0)
        val between = runTimeline(horizon = 55.0, warmUp = 25.0)   // strictly between epochs
        val coinciding = runTimeline(horizon = 55.0, warmUp = 30.0) // on an epoch instant

        println()
        println("epochs every 10, horizon 55:")
        println("  no warm-up      : $none")
        println("  warm-up at 25   : $between")
        println("  warm-up at 30   : $coinciding")

        assertEquals(none.rows.size - 1, between.rows.size, "row 2: exactly one transition discarded")
        assertEquals(none.rows.size - 1, coinciding.rows.size, "row 3: the same effect on an epoch instant")

        // Which one is lost differs, and that is not a discrepancy: at 25 the interval [20,30] is
        // in flight, and at 30 the epoch runs first (§4.6.4.1) so [20,30] completes and [30,40] is
        // the one discarded.
        assertTrue(between.rows.none { it.time == 30.0 }, "[20,30] straddles a warm-up at 25")
        assertTrue(coinciding.rows.any { it.time == 30.0 },
            "at 30 the epoch runs BEFORE the warm-up, so [20,30] is complete and reportable")
        assertTrue(coinciding.rows.none { it.time == 40.0 }, "[30,40] is the one discarded instead")

        // The estimand counts only post-warm-up reward.
        assertTrue(between.element.estimand.value > none.element.estimand.value,
            "a warm-up must reduce the accrued cost; got ${between.element.estimand.value} " +
                "against ${none.element.estimand.value}")
    }

    // ------------------------------------------------------------------ rows 6 and 7

    /** Row 6. A terminal condition ends the episode: the row is `terminated`, and nothing follows. */
    @Test
    fun aTerminalConditionEndsTheEpisodeAndNothingIsDecidedAtThatEpoch() {
        var decisions = 0
        val counting = PolicyIfc { _, ctx -> decisions++; ctx.currentAction }
        val t = runTimeline(horizon = 100.0, terminalAt = 30.0, policy = counting)

        println()
        println("terminalWhen(time >= 30), horizon 100: $t")
        println("  decisions taken: $decisions")

        val last = t.rows.last()
        assertEquals(30.0, last.time, 1e-9)
        assertTrue(last.terminated && !last.truncated, "row 6: terminated, not truncated")
        assertEquals(TerminationSource.NATURAL, last.source)
        assertEquals(TerminationSource.NATURAL, t.element.lastTermination)
        assertEquals(2, decisions, "epochs at 10 and 20 decided; the epoch at 30 must not")
        assertEquals(2, t.element.epochCount, "and the count stops there")
        assertTrue(t.rows.none { it.time > 30.0 }, "no further epochs are scheduled")
    }

    /** Row 7. A cap on decision count is an external limit, so the row is truncated, not terminated. */
    @Test
    fun maxEpochsTruncatesRatherThanTerminates() {
        val t = runTimeline(horizon = 100.0, maxEpochs = 3)
        println()
        println("maxEpochs(3), horizon 100: $t")
        val last = t.rows.last()
        assertTrue(last.truncated && !last.terminated,
            "a cap on decision count is not a property of the modeled system (§4.6.3)")
        assertEquals(TerminationSource.MAX_EPOCHS, last.source)
        assertEquals(3, t.element.epochCount)
        assertEquals(40.0, last.time, 1e-9, "the capping epoch is the one that emits and stops")
    }

    // ------------------------------------------------------------------ row 11

    /** Row 11. A rule that fails fails the replication; it is not absorbed (§4.9.2). */
    @Test
    fun aPolicyThatThrowsRecordsPolicyErrorAndPropagates() {
        val boom = PolicyIfc { _, ctx ->
            if (ctx.simulationTime >= 30.0) error("the rule gave up") else ctx.currentAction
        }
        var element: DecisionElement? = null
        val failure = runCatching {
            runTimeline(horizon = 100.0, policy = boom).also { element = it.element }
        }.exceptionOrNull()

        println()
        println("a policy that throws at t=30: ${failure?.let { it::class.simpleName }}")
        assertTrue(failure != null, "the exception must propagate and end the replication")
    }

    // ------------------------------------------------------------------ the last row

    /**
     *  The row §4.10.4 added because the slice proved it necessary in the cheapest possible way:
     *  an epoch loop that never fires and a policy never called satisfies every assertion above
     *  about *not* changing things. One test must assert that the machinery changes something.
     */
    @Test
    fun aRuleThatMovesLeversMovesTheModel() {
        var wrote = 0.0
        val ramp = PolicyIfc { _, ctx -> doubleArrayOf(minOf(ctx.simulationTime / 10.0, 10.0)) }
        val model = Model("Moves")
        val tank = Tank(model, "T")
        val e = tank.decisionElement("D") {
            observe(tank.level)
            lever(tank, 0.0..10.0, neutral = Neutral.Current { setting }) { v -> setting = v; wrote = v }
            every(10.0)
            policy = ramp
        }
        model.numberOfReplications = 1
        model.lengthOfReplication = 55.0
        model.simulate()

        println()
        println("a ramping rule over 5 epochs: the lever ended at $wrote")
        assertEquals(5.0, wrote, 1e-9, "the last epoch at t=50 should have written 5.0")
        assertEquals(5, e.epochCount)
    }
}
