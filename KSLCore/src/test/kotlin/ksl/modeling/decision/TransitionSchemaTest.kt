package ksl.modeling.decision

import ksl.examples.general.decision.reviewEvery
import ksl.modeling.decision.descriptor.FeasibilityPolicy
import ksl.modeling.decision.descriptor.RewardSense
import ksl.modeling.decision.descriptor.TerminationSource
import ksl.modeling.variable.TWResponse
import ksl.modeling.decision.capture.MemorySink
import ksl.simulation.Model
import ksl.simulation.ModelElement
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  §4.8.3 — M1 step 7e. What a row carries, as opposed to how many rows there are (§4.10.2.1).
 *
 *  Four of these assert corrections rather than new capability, and two of the four were **silently
 *  wrong data** rather than missing fields: the action column recorded a vector that had not been
 *  written, and a lever with nothing to choose from killed the replication instead of taking its
 *  neutral. Both were found by settling questions an implementability review had left open, which
 *  is the case for settling them in the specification rather than leaving them to the implementer.
 */
class TransitionSchemaTest {

    private class Tank(parent: ModelElement, name: String) : ModelElement(parent, name) {
        val level = TWResponse(this, name = "$name:Level", initialValue = 2.0)
        var setting: Double = 0.0
    }

    private class Fixed(private val value: Double) : PolicyIfc {
        override fun action(observation: DoubleArray, ctx: DecisionContext) = doubleArrayOf(value)
    }

    // ---------------------------------------------------------------- the applied action

    /**
     *  The action column is what was **written**, not what was asked for.
     *
     *  Before this step the record carried the pre-clamp vector: a rule asking for 99.0 against a
     *  lever declared `0.0..10.0` produced rows reading 99.0 while the model held 10.0. Every
     *  downstream use — fitting dynamics, off-policy correction, auditing the rule — reads that
     *  column as authoritative, so it is worse than a missing field.
     */
    @Test
    fun theActionColumnIsWhatWasWrittenNotWhatWasAskedFor() {
        val model = Model("Clamped")
        val tank = Tank(model, "T")
        val sink = MemorySink()
        tank.decisionElement("D") {
            observe(tank.level)
            lever(tank, 0.0..10.0, neutral = Neutral.Current { setting }) { v -> setting = v }
            reward(tank.level, rate = 1.0, sense = RewardSense.COST)
            captureTo { sink }
            feasibility = FeasibilityPolicy.CLAMP_THEN_REJECT
            policy = Fixed(99.0)
        }.reviewEvery(tank, 10.0)
        model.numberOfReplications = 1
        model.lengthOfReplication = 55.0
        model.simulate()

        println()
        println("rule asks 99.0 against a 0.0..10.0 lever, CLAMP_THEN_REJECT:")
        println("  lever finished at ${tank.setting}")
        println("  rows: ${sink.records.map { "a=${it.action[0]} proposed=${it.proposedAction?.get(0)}" }}")

        assertTrue(sink.records.isNotEmpty(), "nothing was captured, so nothing was checked")
        assertEquals(10.0, tank.setting, 1e-9, "the model held the clamped value")
        for (r in sink.records) {
            assertEquals(10.0, r.action[0], 1e-9,
                "the action column must be the value that moved the model, not the request")
            assertNotNull(r.proposedAction,
                "the request was repaired, so the rule's own vector is kept")
            assertEquals(99.0, r.proposedAction!![0], 1e-9, "and it is what the rule returned")
            assertTrue(r.wasRepaired)
        }
    }

    /** When the rule gets what it asks for, `proposedAction` is absent rather than a copy. */
    @Test
    fun proposedActionIsNullWhenNothingWasRepaired() {
        val model = Model("Unclamped")
        val tank = Tank(model, "T")
        val sink = MemorySink()
        tank.decisionElement("D") {
            observe(tank.level)
            lever(tank, 0.0..10.0, neutral = Neutral.Current { setting }) { v -> setting = v }
            reward(tank.level, rate = 1.0, sense = RewardSense.COST)
            captureTo { sink }
            policy = Fixed(4.0)
        }.reviewEvery(tank, 10.0)
        model.numberOfReplications = 1
        model.lengthOfReplication = 55.0
        model.simulate()

        assertTrue(sink.records.isNotEmpty())
        for (r in sink.records) {
            assertEquals(4.0, r.action[0], 1e-9)
            assertNull(r.proposedAction,
                "a non-null proposedAction must be a positive signal that something was " +
                    "repaired, not a column repeating `action` on every row")
            assertTrue(!r.wasRepaired)
            assertNull(r.leverUnavailable, "every lever had something to choose from")
        }
    }

    // ---------------------------------------------------------------- the empty feasible set

    /**
     *  §4.4.6.3: an empty feasible set **is not an error**. The lever takes its declared neutral,
     *  the epoch proceeds, and the row records which levers that happened to.
     *
     *  It used to throw `ActionValidationException` and kill the replication — and the exception's
     *  own message cited §4.4.6.3 by number, so the section had been read and the reverse built.
     */
    @Test
    fun anEmptyFeasibleSetTakesTheNeutralAndIsRecorded() {
        val model = Model("Empty")
        val tank = Tank(model, "T")
        val sink = MemorySink()
        tank.decisionElement("D") {
            observe(tank.level)
            // 𝒳(s) is empty from t = 25 onward: start > endInclusive.
            lever(tank, 0.0..10.0, neutral = Neutral.Current { setting },
                bounds = { if (time >= 25.0) 5.0..4.0 else 0.0..10.0 }) { v -> setting = v }
            reward(tank.level, rate = 1.0, sense = RewardSense.COST)
            captureTo { sink }
            // Asks 7.0 while there is a choice, then 3.0 once there is none. The neutral is the
            // held value, 7.0, so the request and the substitution DIFFER in the forced regime —
            // without that the two would coincide and the test would prove less than it appears to.
            policy = object : PolicyIfc {
                override fun action(observation: DoubleArray, ctx: DecisionContext) =
                    doubleArrayOf(if (tank.time >= 25.0) 3.0 else 7.0)
            }
        }.reviewEvery(tank, 10.0)
        model.numberOfReplications = 1
        model.lengthOfReplication = 55.0
        model.simulate()   // must not throw

        println()
        println("𝒳(s) empty from t=25; rule asks 7.0 then 3.0:")
        for (r in sink.records) {
            println("  t=%.0f a=%.1f unavailable=%s proposed=%s"
                .format(r.time, r.action[0], r.leverUnavailable?.get(0), r.proposedAction?.get(0)))
        }

        assertEquals(5, sink.records.size, "the run completed; no epoch was lost to an exception")

        // Epochs at 10 and 20 have a set; from 30 on it is empty. Rows are [10,20] [20,30] [30,40]
        // [40,50] [50,55], and each row's action is the one taken at its START.
        val available = sink.records.filter { it.time - it.tau < 25.0 }
        val forced = sink.records.filter { it.time - it.tau >= 25.0 }
        assertTrue(available.isNotEmpty() && forced.isNotEmpty(), "both regimes must be exercised")

        for (r in available) {
            assertEquals(7.0, r.action[0], 1e-9, "the rule got what it asked for")
            assertNull(r.leverUnavailable)
        }
        for (r in forced) {
            assertNotNull(r.leverUnavailable, "the row must say the set was empty")
            assertTrue(r.leverUnavailable!![0],
                "and name WHICH lever, so a trajectory distinguishes 'chose to do nothing' " +
                    "from 'had nothing to choose'")
            assertEquals(7.0, r.action[0], 1e-9,
                "the neutral here is Neutral.Current, and the lever was holding 7.0")
            assertNotNull(r.proposedAction,
                "the rule asked for 3.0 and got the neutral, so its own vector is kept — a " +
                    "substitution is a repair like clamping is, and reaches the same field")
            assertEquals(3.0, r.proposedAction!![0], 1e-9)
        }
    }

    // ---------------------------------------------------------------- element identity

    /** Two elements sharing one sink stay distinguishable, because the row names its element. */
    @Test
    fun rowsNameTheElementThatProducedThem() {
        val model = Model("TwoElements")
        val a = Tank(model, "A")
        val b = Tank(model, "B")
        val shared = MemorySink()
        a.decisionElement("DA") {
            observe(a.level)
            lever(a, 0.0..10.0, neutral = Neutral.Current { setting }) { v -> setting = v }
            reward(a.level, rate = 1.0, sense = RewardSense.COST)
            captureTo { shared }
            policy = Fixed(1.0)
        }.reviewEvery(a, 10.0)
        b.decisionElement("DB") {
            observe(b.level)
            lever(b, 0.0..10.0, neutral = Neutral.Current { setting }) { v -> setting = v }
            reward(b.level, rate = 1.0, sense = RewardSense.COST)
            captureTo { shared }
            policy = Fixed(2.0)
        }.reviewEvery(b, 10.0)
        model.numberOfReplications = 1
        model.lengthOfReplication = 55.0
        model.simulate()

        val byElement = shared.records.groupBy { it.elementName }
        println()
        println("two elements, one sink: ${byElement.mapValues { it.value.size }}")

        assertEquals(setOf("DA", "DB"), byElement.keys,
            "both elements wrote to the shared sink and both are identified")
        assertTrue(byElement.getValue("DA").all { it.action[0] == 1.0 })
        assertTrue(byElement.getValue("DB").all { it.action[0] == 2.0 })
    }

    // ---------------------------------------------------------------- value equality

    /**
     *  A `data class` over `DoubleArray` compares arrays by reference, so two records of identical
     *  content were unequal while their hash codes agreed — quiet, because unequal objects may
     *  share a hash code, and fatal to any comparison, deduplication or value assertion.
     */
    @Test
    fun recordsCompareByValue() {
        fun record(reward: Double = -1.0, proposed: DoubleArray? = null) = TransitionRecord(
            elementName = "D", replicationId = 1, epochIndex = 0, time = 10.0, tau = 10.0,
            state = doubleArrayOf(1.0, 2.0), action = doubleArrayOf(3.0),
            proposedAction = proposed, leverUnavailable = null,
            reward = reward, successorState = doubleArrayOf(4.0, 5.0),
            terminated = false, truncated = true, source = TerminationSource.RUN_LENGTH
        )

        val a = record()
        val b = record()
        println()
        println("two records of identical content: equal=${a == b}, set size=${setOf(a, b).size}")

        assertEquals(a, b, "identical contents must be equal")
        assertEquals(a.hashCode(), b.hashCode(), "and hash alike")
        assertEquals(1, setOf(a, b).size, "so a Set collapses them")

        // And it must still discriminate — an equals that returns true too readily is worse.
        assertTrue(a != record(reward = -2.0), "a differing scalar makes them unequal")
        assertTrue(a != record(proposed = doubleArrayOf(9.0)), "so does a differing array")
        assertTrue(record(proposed = doubleArrayOf(9.0)) == record(proposed = doubleArrayOf(9.0)),
            "and nullable arrays compare by content too")
        assertContentEquals(doubleArrayOf(1.0, 2.0), a.state)
    }
}
