package ksl.modeling.decision

import ksl.examples.general.decision.reviewEvery
import ksl.modeling.decision.descriptor.RewardSense
import ksl.modeling.variable.TWResponse
import ksl.modeling.decision.capture.MemorySink
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  Phase 7 / D12 — the periodic case, packaged, and the claim that packaging it gives back what
 *  element-owned timing used to provide.
 *
 *  Three things were listed as costs of the decoupling and are not: the simple case became verbose,
 *  the construction guarantee became a warning, and R2b became the caller's problem. Each was a cost
 *  of shipping only a bare driver. This asserts that a composition gets them back — and, crucially,
 *  that it does so **without changing what happens**: the trajectory it produces is the same one the
 *  bare driver produces at the same interval, so this is convenience rather than a second mechanism.
 */
class PeriodicDecisionElementTest {

    private class Tank(parent: ModelElement, name: String) : ModelElement(parent, name) {
        val level = TWResponse(this, name = "$name:Level", initialValue = 2.0)
        var setting: Double = 0.0
    }

    private class CountingPolicy : PolicyIfc {
        var decisions = 0
            private set

        override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
            decisions++
            return doubleArrayOf((decisions % 5).toDouble() + 1.0)
        }
    }

    // ---- The construction guarantee, restored ----------------------------------

    @Test
    @DisplayName("A degenerate review interval is refused at construction, as it used to be")
    fun aDegenerateIntervalIsRefusedAtConstruction() {
        // `build()` used to refuse these, on the ground that such an element "is built, never
        // schedules an epoch, never decides, and reports nothing". With timing owned by the caller
        // that refusal had nothing to check. It has something to check again here, which is the
        // point of the class: the interval is a constructor argument, so the silent no-decisions
        // failure is unreachable through it.
        for (bad in listOf(0.0, -5.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val model = Model("Degenerate")
            val tank = Tank(model, "T")
            val t = assertFailsWith<IllegalArgumentException> {
                PeriodicDecisionElement(tank, interval = bad, name = "R") {
                    observe(tank.level)
                    lever(tank, 0.0..10.0, neutral = Neutral.Current { setting }) { v -> tank.setting = v }
                    policy = NeutralPolicy
                }
            }
            assertTrue(t.message!!.contains("finite"), "every refusal must say what was wrong: $bad")
        }
    }

    @Test
    @DisplayName("An element built this way always has a caller, so it always decides")
    fun anElementBuiltThisWayAlwaysDecides() {
        val model = Model("Decides")
        val tank = Tank(model, "T")
        val policy = CountingPolicy()
        val r = PeriodicDecisionElement(tank, interval = 10.0, name = "R") {
            observe(tank.level)
            lever(tank, 0.0..10.0, neutral = Neutral.Current { setting }) { v -> tank.setting = v }
            reward(tank.level, rate = 1.0, sense = RewardSense.COST)
            this.policy = policy
        }
        model.numberOfReplications = 1
        // 55 rather than 50, deliberately. A horizon that falls exactly on a review boundary makes
        // `replicationEnded` close an interval of no duration, which is discarded -- R4's
        // "the run ends on an epoch boundary" case, correct and expected. Landing off the boundary
        // keeps the assertion below about the thing it is actually about.
        model.lengthOfReplication = 55.0
        model.simulate()

        assertEquals(5, policy.decisions, "reviews at 10, 20, 30, 40, 50")
        assertEquals(5, r.element.epochCount)
        // No runaway is possible: a review that only fires on a timer cannot ask for a decision
        // during a decision, which is the fault the drain cap exists to diagnose. So nothing is lost
        // to a zero-length interval at all.
        assertEquals(0, r.element.discardedZeroLengthCount)
    }

    // ---- The claim that matters: it changes nothing ----------------------------

    @Test
    @DisplayName("It produces the trajectory the bare driver produces at the same interval")
    fun theCompositeAndTheBareDriverAgree() {
        fun composed(): List<TransitionRecord> {
            val model = Model("Composed")
            val tank = Tank(model, "T")
            val sink = MemorySink()
            val r = PeriodicDecisionElement(tank, interval = 10.0, name = "R") {
                observe(tank.level)
                lever(tank, 0.0..10.0, neutral = Neutral.Current { setting }) { v -> tank.setting = v }
                reward(tank.level, rate = 1.0, sense = RewardSense.COST)
                policy = CountingPolicy()
            }
            r.element.attachTransitionSink(sink)
            model.numberOfReplications = 1
            model.lengthOfReplication = 50.0
            model.simulate()
            return sink.records
        }

        fun assembled(): List<TransitionRecord> {
            val model = Model("Assembled")
            val tank = Tank(model, "T")
            val sink = MemorySink()
            val e = tank.decisionElement("R:Decision") {
                observe(tank.level)
                lever(tank, 0.0..10.0, neutral = Neutral.Current { setting }) { v -> tank.setting = v }
                reward(tank.level, rate = 1.0, sense = RewardSense.COST)
                policy = CountingPolicy()
            }.reviewEvery(tank, 10.0)
            e.attachTransitionSink(sink)
            model.numberOfReplications = 1
            model.lengthOfReplication = 50.0
            model.simulate()
            return sink.records
        }

        val a = composed()
        val b = assembled()
        assertEquals(a.size, b.size)
        assertTrue(a.isNotEmpty(), "the arms produced no rows, so this asserts nothing")

        // Element names are compared out: the composite names its element after itself, and the
        // assembled arm names it by hand. Everything that describes what HAPPENED must agree, or
        // this is a second mechanism rather than a convenience over the first.
        for ((x, y) in a.zip(b)) {
            assertEquals(x.epochIndex, y.epochIndex)
            assertEquals(x.time, y.time)
            assertEquals(x.tau, y.tau)
            assertEquals(x.reward, y.reward)
            assertEquals(x.reason, y.reason)
            assertEquals(x.provenance, y.provenance)
            assertEquals(x.state.toList(), y.state.toList())
            assertEquals(x.action.toList(), y.action.toList())
            assertEquals(x.successorState.toList(), y.successorState.toList())
            assertEquals(x.terminated, y.terminated)
            assertEquals(x.truncated, y.truncated)
            assertEquals(x.source, y.source)
        }
    }

    // ---- simopt still reaches the review period --------------------------------

    @Test
    @DisplayName("The review period is a control on a named type")
    fun theReviewPeriodIsAControl() {
        val model = Model("Controls")
        val tank = Tank(model, "T")
        PeriodicDecisionElement(tank, interval = 10.0, name = "R") {
            observe(tank.level)
            lever(tank, 0.0..10.0, neutral = Neutral.Current { setting }) { v -> tank.setting = v }
            maxEpochs(50)
            policy = NeutralPolicy
        }
        val keys = model.controls().controlKeys().filter { it.startsWith("R") }.sorted()
        println()
        println("controls: $keys")

        // Before the decoupling this was `R.epochInterval` on the element. It is now on the object
        // that schedules the reviews -- which is a named type, so a study finds it by looking for
        // the type rather than by knowing which model element happens to do the scheduling.
        assertTrue("R.interval" in keys,
            "a review period is an ordinary decision variable and simopt must still reach it: $keys")
        assertTrue("R:Decision.maxEpochs" in keys, "and the element's own cap is still its own: $keys")
    }
}
