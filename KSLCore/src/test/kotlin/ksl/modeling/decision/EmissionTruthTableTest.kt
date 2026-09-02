package ksl.modeling.decision

import ksl.examples.general.decision.reviewEvery
import ksl.modeling.decision.descriptor.RewardSense
import ksl.modeling.variable.TWResponse
import ksl.modeling.decision.capture.MemorySink
import ksl.simulation.Model
import ksl.simulation.ModelElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  §4.10.2.1 — the emission truth table, asserted rather than documented.
 *
 *  §4.10.4's timeline matrix asserts the *sequence* of emitted rows. This class asserts the
 *  *count*, which is a different property and was the one that kept going wrong: the prose
 *  describing how many rows a run emits was written five times and was wrong twice, in two
 *  different ways, while the code was right throughout. A count that lives in sentences drifts.
 *
 *  Three things are asserted, and the third is the reason this class exists.
 *
 *  1. **The identity.** `attempts = D + 1` per episode, where *D* is the number of decisions, and
 *     therefore `rows = D + 1 - discards`. The `+ 1` is `replicationEnded()` closing the final
 *     partial interval; it holds for an episode that ends early too, because such an episode does
 *     not decide at its final epoch and `replicationEnded()` then attempts nothing.
 *  2. **The discard count**, `1 + [a warm-up occurs] + [the run ends on an epoch boundary]`.
 *  3. **That discard rule 2 never fires.** `reward == null` requires an invalidated baseline, and
 *     every site that invalidates one — `initialize()` and `warmUp()` — clears the pending
 *     transition in the same breath, so rule 1 always claims the discard first. The rule is kept
 *     as a guard on that two-site invariant. An unreachable guard is worth keeping *only* if
 *     something checks that it is still unreachable, because otherwise the next reader cannot tell
 *     a deliberate net from dead code. This test is that check: if a later edit separates the two
 *     sites, `noBaseline` becomes non-zero and this fails, at which point the guard is exactly
 *     what you want to have kept.
 *
 *  Expected counts below are written out per scenario rather than computed from the formula. A
 *  test that recomputes the rule it is checking cannot catch a wrong rule.
 */
class EmissionTruthTableTest {

    /** The [EpochTimelineTest] tank: level pinned at 2.0, so an interval of length τ accrues −2τ. */
    private class Tank(parent: ModelElement, name: String) : ModelElement(parent, name) {
        val level = TWResponse(this, name = "$name:Level", initialValue = 2.0)
        var setting: Double = 0.0
    }

    /** Counts calls to `action`, which is *D*. */
    private class CountingPolicy(private val inner: PolicyIfc = NeutralPolicy) : PolicyIfc {
        var decisions = 0
            private set

        override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
            decisions++
            return inner.action(observation, ctx)
        }
    }

    private class Run(
        val census: DecisionElement.EmissionCensus,
        val decisions: Int,
        val rows: Int,
        val reps: Int
    )

    private fun run(
        horizon: Double,
        warmUp: Double = 0.0,
        interval: Double = 10.0,
        reps: Int = 1,
        maxEpochs: Int = Int.MAX_VALUE,
        terminalAt: Double? = null
    ): Run {
        val model = Model("TruthTable")
        val tank = Tank(model, "T")
        val sink = MemorySink()
        val counter = CountingPolicy()
        val e = tank.decisionElement("D") {
            observe(tank.level)
            lever(tank, 0.0..10.0, neutral = Neutral.Current { setting }) { v -> setting = v }
            reward(tank.level, rate = 1.0, sense = RewardSense.COST)
            captureTo { sink }
            if (maxEpochs != Int.MAX_VALUE) maxEpochs(maxEpochs)
            if (terminalAt != null) terminalWhen { tank.time >= terminalAt }
            this.policy = counter
        }.reviewEvery(tank, interval)
        model.numberOfReplications = reps
        model.lengthOfReplication = horizon
        model.lengthOfReplicationWarmUp = warmUp
        model.simulate()
        return Run(e.census, counter.decisions, sink.records.size, reps)
    }

    /**
     *  The identity and the per-rule counts, over the configurations §4.10.2.1 tabulates. Each
     *  expectation is the number written in that table, per replication.
     */
    @Test
    fun theCensusMatchesTheTruthTable() {
        data class Case(
            val label: String,
            val run: Run,
            val noPredecessorPerRep: Int,
            val zeroLengthPerRep: Int
        )

        val cases = listOf(
            // Horizon on an epoch boundary: the first epoch has no predecessor, and the closer
            // from replicationEnded() has tau == 0.
            Case("horizon on a boundary", run(horizon = 50.0), 1, 1),
            // Horizon off a boundary: the closer covers a genuine partial interval and is emitted.
            Case("horizon off a boundary", run(horizon = 55.0), 1, 0),
            // A warm-up costs a second no-predecessor discard, whether or not it lands on an epoch.
            Case("warm-up off an epoch", run(horizon = 55.0, warmUp = 25.0), 2, 0),
            Case("warm-up on an epoch", run(horizon = 55.0, warmUp = 30.0), 2, 0),
            Case("warm-up and boundary", run(horizon = 50.0, warmUp = 30.0), 2, 1),
            // An early ending consumes the pending transition at its own epoch, so there is no
            // zero-length closer and replicationEnded() attempts nothing.
            Case("terminalWhen", run(horizon = 100.0, terminalAt = 30.0), 1, 0),
            Case("maxEpochs", run(horizon = 100.0, maxEpochs = 3), 1, 0),
            // Several replications: the counters are per-experiment totals.
            Case("three replications", run(horizon = 50.0, reps = 3), 1, 1),
            Case("three reps, warm-up", run(horizon = 10_000.0, warmUp = 2_000.0, interval = 5.0, reps = 3), 2, 1)
        )

        println()
        println("§4.10.2.1 census — expected counts are per replication:")
        for (c in cases) {
            val r = c.run
            println("  %-24s D=%-5d %s".format(c.label, r.decisions, r.census))

            assertEquals(c.noPredecessorPerRep * r.reps, r.census.noPredecessor,
                "${c.label}: expected ${c.noPredecessorPerRep} no-predecessor discard(s) per " +
                    "replication over ${r.reps} replication(s)")
            assertEquals(c.zeroLengthPerRep * r.reps, r.census.zeroLength,
                "${c.label}: expected ${c.zeroLengthPerRep} zero-length discard(s) per " +
                    "replication over ${r.reps} replication(s)")

            // The identity: one emit attempt per decision, plus one closer per episode.
            assertEquals(r.decisions + r.reps, r.census.attempts,
                "${c.label}: attempts must be D + 1 per episode — every epoch attempts one emit, " +
                    "and replicationEnded() attempts one more exactly when the episode did not " +
                    "already end at an epoch (§4.10.2.1)")
            assertEquals(r.census.attempts - r.census.discards, r.census.emitted,
                "${c.label}: every attempt either emits or is discarded for exactly one reason")
            assertEquals(r.census.emitted, r.rows,
                "${c.label}: the sink received one row per emission")

            // The gap a reader is most likely to get wrong: it is one *less* than the discards.
            assertEquals(r.census.discards - r.reps, r.decisions - r.rows,
                "${c.label}: the gap between decisions and rows is discards - 1 per episode, not " +
                    "the discard count — this is the arithmetic §8.2.9 got wrong twice")
        }
    }

    /**
     *  Discard rule 2 is unreachable, and this is the assertion that keeps it honest.
     *
     *  Deliberately run over every shape that invalidates a baseline: a warm-up on an epoch, a
     *  warm-up off one, a warm-up immediately before the horizon, and many replications, each of
     *  which invalidates at `initialize()`. If any state exists in which a transition is pending
     *  while its baseline is not, one of these finds it.
     */
    @Test
    fun discardRuleTwoNeverFires() {
        val runs = mapOf(
            "no warm-up" to run(horizon = 55.0),
            "warm-up off an epoch" to run(horizon = 55.0, warmUp = 25.0),
            "warm-up on an epoch" to run(horizon = 55.0, warmUp = 30.0),
            "warm-up just before the horizon" to run(horizon = 55.0, warmUp = 54.0),
            "warm-up at the first epoch" to run(horizon = 55.0, warmUp = 10.0),
            "many replications" to run(horizon = 50.0, reps = 20),
            "early ending after a warm-up" to run(horizon = 100.0, warmUp = 25.0, terminalAt = 60.0)
        )

        println()
        println("discard rule 2 (unmeasurable reward) across every baseline-invalidating shape:")
        for ((label, r) in runs) {
            println("  %-32s %s".format(label, r.census))
            assertTrue(r.census.attempts > 0, "$label: nothing ran, so nothing was checked")
            assertEquals(0, r.census.noBaseline,
                "$label: discard rule 2 fired. It is unreachable by construction — every site " +
                    "that calls rewards.invalidate() also clears the pending transition, so rule " +
                    "1 claims the discard first (§4.10.2.1). If this fails, that two-site " +
                    "invariant has been broken: the guard just caught a transition whose reward " +
                    "could not be measured, and the fix is to restore the invariant or to make " +
                    "the rule's reachability deliberate and update §4.10.2.1.")
        }

        // And the warm-up discards really are being counted somewhere — otherwise the assertion
        // above would pass on a build where warm-up silently stopped discarding anything.
        val withWarmUp = runs.getValue("warm-up off an epoch").census
        val without = runs.getValue("no warm-up").census
        assertEquals(without.noPredecessor + 1, withWarmUp.noPredecessor,
            "a warm-up must cost exactly one further no-predecessor discard; if this fails, the " +
                "zero above is measuring nothing")
    }
}
