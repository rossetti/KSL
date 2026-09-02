package ksl.modeling.decision

import ksl.examples.general.decision.reviewEvery
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
 *  §4.7 — who owns a `ManagedPolicyIfc`, and what happens when one model is run more than once.
 *
 *  **The rule is that the element closes what the element opened.** It opens a sink, through the
 *  factory at `beforeExperiment()`, so it closes the sink. It never opened the policy — the user
 *  constructed it and assigned it — so it closes one only when that policy is *replaced*, and the
 *  last policy assigned is the user's to close.
 *
 *  This is `WelchFileObserver`'s arrangement, which §4.7 already cites as the pattern being
 *  followed: per-experiment setup and teardown in `beforeExperiment`/`afterExperiment`, and a
 *  `close()` that the owner calls when finished with the object, documented idempotent
 *  (`observers/welch/WelchFileObserver.kt:100`). The first implementation merged the two by
 *  calling `close()` at `afterExperiment()`, and the merge is what produced both defects below.
 */
class PolicyOwnershipTest {

    private class Tank(parent: ModelElement, name: String) : ModelElement(parent, name) {
        val level = TWResponse(this, name = "$name:Level", initialValue = 2.0)
        var setting: Double = 0.0
    }

    /** Stands in for a policy holding a file, a connection, or a loaded model. */
    private class Resourceful(private val label: String = "p") : ManagedPolicyIfc {
        var experimentsStarted = 0; private set
        var experimentsEnded = 0; private set
        var episodes = 0; private set
        var closes = 0; private set
        var decisionsAfterClose = 0; private set
        var episodesAfterClose = 0; private set
        private var closed = false

        override fun beforeExperiment() { experimentsStarted++ }
        override fun afterExperiment() { experimentsEnded++ }
        override fun beforeEpisode(episodeIndex: Int) {
            episodes++
            if (closed) episodesAfterClose++
        }
        override fun afterEpisode(episodeIndex: Int, source: TerminationSource) {}
        override fun close() { closes++; closed = true }

        override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
            if (closed) decisionsAfterClose++
            return doubleArrayOf(1.0)
        }

        override fun toString() = label
    }

    private fun build(model: Model, p: PolicyIfc, sink: MemorySink? = null): DecisionElement {
        val tank = Tank(model, "T")
        return tank.decisionElement("D") {
            observe(tank.level)
            lever(tank, 0.0..10.0, neutral = Neutral.Current { setting }) { v -> setting = v }
            reward(tank.level, rate = 1.0, sense = RewardSense.COST)
            if (sink != null) captureTo { sink }
            policy = p
        }.reviewEvery(tank, 10.0)
    }

    /**
     *  The defect this test exists for: one model, simulated twice.
     *
     *  Measured before the fix, on this exact shape — the policy was closed at the end of run 1 and
     *  then asked to decide **twelve** more times during run 2, with no error and nothing in the
     *  output to show for it. Re-running one model is what a parameter sweep does and what
     *  simulation optimization (B.5) does, so this is the ordinary path rather than a corner.
     */
    @Test
    fun aModelSimulatedTwiceDoesNotRunAgainstAClosedPolicy() {
        val model = Model("Twice")
        val p = Resourceful()
        build(model, p)
        model.numberOfReplications = 2
        model.lengthOfReplication = 55.0

        model.simulate()
        val closesAfterRunOne = p.closes
        model.simulate()

        println()
        println("one model, two runs of two replications:")
        println("  beforeExperiment=${p.experimentsStarted} afterExperiment=${p.experimentsEnded}")
        println("  episodes=${p.episodes} closes=${p.closes} decisionsAfterClose=${p.decisionsAfterClose}")

        assertEquals(0, closesAfterRunOne,
            "the element must not close a policy it did not open, and it cannot know whether " +
                "another experiment is coming")
        assertEquals(0, p.closes, "still not closed after the second run")
        assertEquals(0, p.decisionsAfterClose, "so nothing decided against released resources")
        assertEquals(0, p.episodesAfterClose)

        assertEquals(2, p.experimentsStarted, "the per-experiment hook fires on every run")
        assertEquals(2, p.experimentsEnded, "and its pair fires the same number of times")
        assertEquals(4, p.episodes, "two replications per run, one episode each (§4.6.3)")
    }

    /**
     *  Replacement is the one moment the element is finished with a policy it was handed, so it is
     *  the one moment it closes one. §4.9's k-policy comparison assigns k rules to one element in a
     *  loop; leaving the k-1 superseded rules unclosed would leak whatever they hold.
     */
    @Test
    fun replacingAPolicyClosesTheOneItReplacedExactlyOnce() {
        val model = Model("Swap")
        val a = Resourceful("a")
        val e = build(model, a)
        model.numberOfReplications = 1
        model.lengthOfReplication = 55.0

        model.simulate()
        assertEquals(0, a.closes, "finishing an experiment is not finishing with the policy")

        val b = Resourceful("b")
        e.policy = b
        model.simulate()

        println()
        println("swap after a run: a.closes=${a.closes} b.closes=${b.closes} " +
            "b.decisionsAfterClose=${b.decisionsAfterClose}")

        assertEquals(1, a.closes,
            "exactly once. It used to be twice — once at afterExperiment() and once again on " +
                "replacement — which is why close() is specified idempotent")
        assertEquals(0, b.closes, "the incumbent is not closed")
        assertEquals(0, b.decisionsAfterClose)
        assertEquals(1, b.experimentsStarted, "and it was set up for the run it took part in")
    }

    /** Assigning the same instance back is a no-op, not a close. */
    @Test
    fun reassigningTheSamePolicyDoesNotCloseIt() {
        val model = Model("Same")
        val p = Resourceful()
        val e = build(model, p)

        e.policy = p

        assertEquals(0, p.closes,
            "assigning the same policy reads as a no-op at the call site, and closing there " +
                "would release a live resource the element is about to keep using")
    }

    /**
     *  The other half of the rule: the element *did* open the sink, so it closes it — every run,
     *  and a re-run gets a fresh one from the factory rather than a closed one.
     */
    @Test
    fun theElementClosesTheSinkItOpenedOnEveryRun() {
        class CountingSink : TransitionSink {
            var closes = 0
            var writesAfterClose = 0
            val rows = mutableListOf<TransitionRecord>()
            private var closed = false
            override fun write(record: TransitionRecord) {
                if (closed) writesAfterClose++
                rows.add(record)
            }
            override fun close() { closes++; closed = true }
        }

        val model = Model("Sinks")
        val made = mutableListOf<CountingSink>()
        val tank = Tank(model, "T")
        tank.decisionElement("D") {
            observe(tank.level)
            lever(tank, 0.0..10.0, neutral = Neutral.Current { setting }) { v -> setting = v }
            reward(tank.level, rate = 1.0, sense = RewardSense.COST)
            captureTo { CountingSink().also { made.add(it) } }
            policy = NeutralPolicy
        }.reviewEvery(tank, 10.0)
        model.numberOfReplications = 1
        model.lengthOfReplication = 55.0

        model.simulate()
        model.simulate()

        println()
        println("sinks made=${made.size} closes=${made.map { it.closes }} rows=${made.map { it.rows.size }}")

        assertEquals(2, made.size, "one sink per experiment, from the factory")
        assertTrue(made.all { it.closes == 1 }, "each closed exactly once, by the element that opened it")
        assertTrue(made.all { it.writesAfterClose == 0 }, "and none written to afterwards")
        assertTrue(made.all { it.rows.isNotEmpty() }, "both runs captured something")
    }

    // ---------------------------------------------------------------- failure injection

    /**
     *  §4.7's close paths under failure — the step 9/10 criterion that had not been exercised.
     *
     *  Teardown has two obligations, a managed policy's and a sink's, and they are independent.
     *  Each must be attempted even when the other fails, so that a secondary failure cannot mask a
     *  primary one, and the caller must learn about both.
     */
    @Test
    fun bothTeardownsAreAttemptedWhenEitherFails() {
        class FailingSink(val fail: Boolean) : TransitionSink {
            var closes = 0; var writes = 0
            override fun write(record: TransitionRecord) { writes++ }
            override fun close() { closes++; if (fail) throw IllegalStateException("sink close failed") }
        }
        class FailingPolicy(val fail: Boolean) : ManagedPolicyIfc {
            var teardowns = 0
            override fun afterExperiment() { teardowns++; if (fail) throw IllegalStateException("policy teardown failed") }
            override fun action(observation: DoubleArray, ctx: DecisionContext) = doubleArrayOf(1.0)
        }

        fun run(policyFails: Boolean, sinkFails: Boolean): Triple<Throwable?, FailingPolicy, FailingSink> {
            val model = Model("Teardown")
            val p = FailingPolicy(policyFails)
            val sink = FailingSink(sinkFails)
            val tank = Tank(model, "T")
            tank.decisionElement("D") {
                observe(tank.level)
                lever(tank, 0.0..10.0, neutral = Neutral.Current { setting }) { v -> setting = v }
                reward(tank.level, rate = 1.0, sense = RewardSense.COST)
                captureTo { sink }
                policy = p
            }.reviewEvery(tank, 10.0)
            model.numberOfReplications = 1
            model.lengthOfReplication = 55.0
            var root = runCatching { model.simulate() }.exceptionOrNull()
            while (root?.cause != null && root.cause !== root) root = root.cause
            return Triple(root, p, sink)
        }

        println()

        val (t1, p1, s1) = run(policyFails = false, sinkFails = true)
        println("sink close fails    → ${t1?.message}; policy teardown ran = ${p1.teardowns}")
        assertEquals("sink close failed", t1?.message, "the caller learns the sink failed")
        assertEquals(1, p1.teardowns, "and the policy's teardown still ran")
        assertTrue(s1.writes > 0, "the run actually captured something, so this is not vacuous")

        val (t2, p2, s2) = run(policyFails = true, sinkFails = false)
        println("policy teardown fails → ${t2?.message}; sink closed = ${s2.closes}")
        assertEquals("policy teardown failed", t2?.message)
        assertEquals(1, s2.closes, "the sink is still closed — the element opened it and owes it that")
        assertEquals(1, p2.teardowns)

        val (t3, p3, s3) = run(policyFails = true, sinkFails = true)
        println("both fail             → primary=${t3?.message}; suppressed=${t3?.suppressed?.map { it.message }}")
        assertEquals(1, p3.teardowns); assertEquals(1, s3.closes)
        assertEquals("policy teardown failed", t3?.message, "the first failure is the primary one")
        assertEquals(listOf("sink close failed"), t3?.suppressed?.map { it.message },
            "and the second is suppressed onto it rather than lost — a secondary failure must " +
                "not mask a primary one, and must not vanish either")
    }

    /** A policy with no external resource needs none of this, which is §4.7's headline promise. */
    @Test
    fun anOrdinaryPolicyNeedsNoLifecycleAtAll() {
        val model = Model("Plain")
        val sink = MemorySink()
        build(model, NeutralPolicy, sink)
        model.numberOfReplications = 1
        model.lengthOfReplication = 55.0
        model.simulate()
        model.simulate()
        assertTrue(sink.records.isNotEmpty(), "it simply runs, twice, with nothing to remember")
    }
}
