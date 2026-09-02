package ksl.modeling.decision

import ksl.modeling.decision.descriptor.EpochProvenance
import ksl.modeling.decision.descriptor.RewardSense
import ksl.modeling.decision.descriptor.TerminationSource
import ksl.modeling.variable.TWResponse
import ksl.sdm.capture.MemorySink
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  S§C.11 — the invocation contract, for the entry points the caller drives.
 *
 *  These assert the three properties that decoupled invocation introduces and that nothing else in
 *  the suite reaches, because every other test drives the element through its own scheduled epochs:
 *
 *  1. **Re-entrancy is refused** (§C.11.1). The failure it prevents is silent, so a test that only
 *     checked "does not crash" would pass against the defect.
 *  2. **Two decisions at one instant are allowed and counted** (§C.11.2), and the count is the thing
 *     that makes the loss visible rather than silent.
 *  3. **Deferring changes nothing but the moment** (§C.11.3). If a deferred decision at a quiescent
 *     instant produced a different trajectory from an immediate one taken at the same point, the two
 *     entry points would be two behaviours rather than one behaviour with two schedules, and the
 *     guidance to "defer when in doubt" would be advice to change your answer.
 */
class InvocationContractTest {

    /** The suite's usual fixture: level pinned at 2.0, so an interval of length tau accrues -2 tau. */
    private class Tank(parent: ModelElement, name: String) : ModelElement(parent, name) {
        val level = TWResponse(this, name = "$name:Level", initialValue = 2.0)
        var setting: Double = 0.0
    }

    /**
     *  Counts the calls the policy actually receives, which is the number of decisions taken.
     *
     *  It must return a value that **changes** the lever rather than `ctx.neutralAction`. A SETTING
     *  lever written the value it already holds is elided (R5), so a neutral policy issues no write
     *  at all -- and the lever's write function is exactly what these tests need to run, since it is
     *  the path by which a model reaches back into `decide`. Cycling 1..5 keeps every action inside
     *  the declared 0..10 range and guarantees consecutive actions differ.
     */
    private class CountingPolicy : PolicyIfc {
        var decisions = 0
            private set

        override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
            decisions++
            return doubleArrayOf((decisions % 5).toDouble() + 1.0)
        }
    }

    /**
     *  Calls the element at declared times, which is what a modeler now does. [callsPerFiring] is
     *  what makes the same-instant case reachable without contriving two callers.
     */
    private class Driver(
        parent: ModelElement,
        private val times: List<Double>,
        private val callsPerFiring: Int = 1,
        private val deferred: Boolean = false
    ) : ModelElement(parent, "Driver") {

        lateinit var element: DecisionElement

        private inner class Fire : EventAction<Nothing>() {
            override fun action(event: KSLEvent<Nothing>) {
                repeat(callsPerFiring) {
                    if (deferred) element.requestDecision("driven") else element.decide("driven")
                }
            }
        }

        override fun initialize() {
            for (t in times) Fire().schedule(t)
        }
    }

    private class Arm(val sink: MemorySink, val policy: CountingPolicy, val element: DecisionElement)

    /**
     *  One model, one element, driven by [Driver]. The element declares no timing of its own, which
     *  is the post-refactoring shape; the legacy scheduled path is exercised by the rest of the
     *  suite and is not used here.
     */
    private fun runArm(
        times: List<Double>,
        callsPerFiring: Int = 1,
        deferred: Boolean = false,
        horizon: Double = 20.0,
        leverWrite: ((Tank, Double, DecisionElement) -> Unit)? = null
    ): Arm {
        val model = Model("Invocation")
        val tank = Tank(model, "T")
        val driver = Driver(model, times, callsPerFiring, deferred)
        val sink = MemorySink()
        val policy = CountingPolicy()
        lateinit var self: DecisionElement
        val e = tank.decisionElement("D") {
            observe(tank.level)
            lever(tank, 0.0..10.0, neutral = Neutral.Current { setting }) { v ->
                if (leverWrite != null) leverWrite(tank, v, self) else tank.setting = v
            }
            reward(tank.level, rate = 1.0, sense = RewardSense.COST)
            captureTo { sink }
            this.policy = policy
        }
        self = e
        driver.element = e
        model.numberOfReplications = 1
        model.lengthOfReplication = horizon
        model.simulate()
        return Arm(sink, policy, e)
    }

    // ---- 1. Re-entrancy ---------------------------------------------------------

    @Test
    @DisplayName("A lever write that calls decide() is refused, naming the probable cause")
    fun leverWriteReentryIsRefused() {
        // The refusal arrives WRAPPED, and that is worth asserting rather than working around.
        // `ActionBinding.apply` wraps anything a lever's write function throws in an
        // ActionApplicationException so it can report how much of a multi-lever action had been
        // written when the failure happened -- here, "0 of 1 writes". The diagnosis is on the cause.
        // This is the same shape §8 records for controls, whose refusals surface wrapped in an
        // InvocationTargetException with the message on the cause.
        val outer = assertFailsWith<ActionApplicationException> {
            runArm(times = listOf(5.0)) { _, _, element -> element.decide("nested") }
        }
        val thrown = outer.cause as? ReentrantDecisionException
            ?: error("expected the re-entrancy refusal as the cause, got ${outer.cause}")
        assertEquals("D", thrown.elementName)
        assertEquals("nested", thrown.reason)
        // The message must name the cause, not merely the fact: a reader who sees only "already in
        // progress" has to rediscover that a lever write is what closes the loop.
        assertTrue(thrown.message!!.contains("lever"), "the message should name the usual cause")
        assertTrue(thrown.message!!.contains("requestDecision"), "the message should name the repair")
    }

    @Test
    @DisplayName("The same lever write using requestDecision() is accepted, and decides again")
    fun leverWriteMayDeferInstead() {
        // S§C.11.4 Example 3's repair. requestDecision only schedules, so the guard is never tripped
        // and the second decision happens after the first has returned. Exactly one extra decision:
        // the deferred epoch's own lever write requests nothing, because by then the guard is clear
        // and the write runs with the element idle -- so this also checks the deferral does not
        // cascade unboundedly.
        val arm = runArm(times = listOf(5.0)) { tank, v, element ->
            tank.setting = v
            if (element.lastDecisionReason == "driven") element.requestDecision("post-write")
        }
        assertEquals(2, arm.policy.decisions, "one driven decision plus one deferred")
        assertEquals("post-write", arm.element.lastDecisionReason)
    }

    // ---- 2. Two decisions at one instant ---------------------------------------

    @Test
    @DisplayName("Two decisions at one instant are both taken, and the lost row is counted")
    fun sameInstantIsAllowedAndCounted() {
        val arm = runArm(times = listOf(5.0), callsPerFiring = 2)

        // Both decisions happen. This is the half that a refusal would break: two demands arriving
        // together, each triggering a review, is a correct model.
        assertEquals(2, arm.policy.decisions)

        // And the first one's transition is lost, because the interval it bounds has no duration.
        // Exactly one such discard: decision 1 -> decision 2 is the zero-length pair.
        assertEquals(1, arm.element.discardedZeroLengthCount)

        // The visible consequence: one row rather than two. The row that survives is the one from
        // the second decision to the end of the replication.
        assertEquals(1, arm.sink.records.size)
        assertEquals(5.0, arm.sink.records.single().time - arm.sink.records.single().tau)
        assertTrue(arm.sink.records.all { it.tau > 0.0 }, "no zero-length row is ever written")
    }

    @Test
    @DisplayName("Distinct instants lose nothing")
    fun distinctInstantsLoseNothing() {
        val arm = runArm(times = listOf(5.0, 10.0))
        assertEquals(2, arm.policy.decisions)
        assertEquals(0, arm.element.discardedZeroLengthCount)
        assertEquals(2, arm.sink.records.size)
    }

    // ---- 3. Deferring changes nothing but the moment ---------------------------

    @Test
    @DisplayName("A deferred decision records what an immediate one records, except its provenance")
    fun deferredMatchesImmediateExceptProvenance() {
        val immediate = runArm(times = listOf(5.0, 10.0), deferred = false)
        val deferred = runArm(times = listOf(5.0, 10.0), deferred = true)

        assertEquals(immediate.policy.decisions, deferred.policy.decisions)
        assertEquals(immediate.sink.records.size, deferred.sink.records.size)

        // Deferral moves the epoch into an event of the element's own. At a quiescent instant that
        // must change nothing the epoch measures -- same state, same action, same reward, same
        // interval -- because otherwise "defer when in doubt" would be advice to change your answer.
        for ((i, d) in deferred.sink.records.withIndex()) {
            val m = immediate.sink.records[i]
            assertEquals(m.epochIndex, d.epochIndex)
            assertEquals(m.time, d.time)
            assertEquals(m.tau, d.tau)
            assertEquals(m.reward, d.reward)
            assertEquals(m.state.toList(), d.state.toList())
            assertEquals(m.action.toList(), d.action.toList())
            assertEquals(m.successorState.toList(), d.successorState.toList())
            assertEquals(m.reason, d.reason)

            // The one field that must differ is the one that exists to record the difference. A
            // consumer wanting only states the library guaranteed consistent filters on this.
            assertEquals(EpochProvenance.IMMEDIATE, m.provenance)
            assertEquals(EpochProvenance.DEFERRED, d.provenance)
        }
    }

    @Test
    @DisplayName("The caller's reason reaches the record and the policy's context")
    fun reasonReachesTheRecordAndTheContext() {
        lateinit var seen: MutableList<String>
        val model = Model("Invocation")
        val tank = Tank(model, "T")
        val driver = Driver(model, listOf(5.0, 10.0))
        val sink = MemorySink()
        seen = mutableListOf()
        val e = tank.decisionElement("D") {
            observe(tank.level)
            lever(tank, 0.0..10.0, neutral = Neutral.Current { setting }) { v -> tank.setting = v }
            reward(tank.level, rate = 1.0, sense = RewardSense.COST)
            captureTo { sink }
            policy = PolicyIfc { _, ctx -> seen += ctx.reason; doubleArrayOf(1.0) }
        }
        driver.element = e
        model.numberOfReplications = 1
        model.lengthOfReplication = 20.0
        model.simulate()

        assertEquals(listOf("driven", "driven"), seen, "the rule is told why it is being consulted")
        assertTrue(sink.records.isNotEmpty())
        assertTrue(sink.records.all { it.reason == "driven" },
            "every row carries the reason of the epoch that OPENED its interval")
    }

    // ---- 4. The guards around the entry points ---------------------------------

    @Test
    @DisplayName("Deciding outside a replication is refused")
    fun decideOutsideARunIsRefused() {
        val model = Model("Invocation")
        val tank = Tank(model, "T")
        val e = tank.decisionElement("D") {
            observe(tank.level)
            lever(tank, 0.0..10.0, neutral = Neutral.Current { setting }) { v -> tank.setting = v }
            policy = NeutralPolicy
        }
        assertFailsWith<IllegalStateException> { e.decide("too early") }
        assertFailsWith<IllegalStateException> { e.requestDecision("too early") }
    }

    @Test
    @DisplayName("A decision requested after the episode has ended is ignored and counted")
    fun decisionAfterEpisodeEndIsIgnored() {
        // maxEpochs(1) ends the episode at the second call; the third and fourth arrive after it.
        val model = Model("Invocation")
        val tank = Tank(model, "T")
        val driver = Driver(model, listOf(2.0, 4.0, 6.0, 8.0))
        val policy = CountingPolicy()
        val e = tank.decisionElement("D") {
            observe(tank.level)
            lever(tank, 0.0..10.0, neutral = Neutral.Current { setting }) { v -> tank.setting = v }
            reward(tank.level, rate = 1.0, sense = RewardSense.COST)
            maxEpochs(1)
            this.policy = policy
        }
        driver.element = e
        model.numberOfReplications = 1
        model.lengthOfReplication = 20.0
        model.simulate()

        // One decision taken; the epoch at t=4 classifies the ending and takes none.
        assertEquals(1, policy.decisions)
        assertEquals(TerminationSource.MAX_EPOCHS, e.lastTermination)
        // The two calls after the episode ended were ignored rather than obeyed: applying an action
        // after the episode it belongs to has ended is the mistake step 5 exists to prevent.
        assertEquals(2, e.ignoredAfterEpisodeEndCount)
    }
}
