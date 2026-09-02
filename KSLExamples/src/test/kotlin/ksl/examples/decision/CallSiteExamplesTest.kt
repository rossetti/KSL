package ksl.examples.decision

import ksl.modeling.decision.ActionApplicationException
import ksl.modeling.decision.DecisionContext
import ksl.modeling.decision.PolicyIfc
import ksl.modeling.decision.ReentrantDecisionException
import ksl.sdm.capture.MemorySink
import ksl.simulation.Model
import org.junit.jupiter.api.DisplayName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 *  S§C.11.4 — the R2b examples, asserted.
 *
 *  **The negative cases are the point.** An example that shows only the correct call site teaches
 *  nothing about why the position matters, so each wrong one is run here and its wrongness is
 *  measured: a missed reorder, a decision about the wrong moment, a refusal, and a cost recorded
 *  against the wrong interval. If a future change made any of them stop being wrong, this fails and
 *  the guide's advice would need revisiting rather than quietly becoming false.
 */
class CallSiteExamplesTest {

    /** Wraps a rule and keeps what it was shown, so a test can see the state the rule decided on. */
    private class Recording(private val inner: PolicyIfc) : PolicyIfc {
        val seen = mutableListOf<List<Double>>()
        override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
            seen += observation.toList()
            return inner.action(observation, ctx)
        }
    }

    private class Arm(val room: StockRoom, val probe: Recording)

    private fun run(site: CallSite): Arm {
        val model = Model("CallSite-$site")
        val room = StockRoom(model, site, name = "Room")
        val probe = Recording(OrderUpTo(reorderPoint = 5.0, orderUpTo = 20.0))
        room.review.policy = probe
        model.numberOfReplications = 1
        model.lengthOfReplication = 20.0
        model.simulate()
        return Arm(room, probe)
    }

    // ---- Example 1 and 2: the torn read, and the early call ---------------------

    @Test
    @DisplayName("Calling last is the only arm that notices the reorder point")
    fun onlyTheCorrectCallSiteOrders() {
        val correct = run(CallSite.CORRECT)
        val torn = run(CallSite.TORN)
        val early = run(CallSite.EARLY)

        println()
        println("orders placed: CORRECT=%.0f TORN=%.0f EARLY=%.0f".format(
            correct.room.ordersPlaced.value, torn.room.ordersPlaced.value, early.room.ordersPlaced.value))

        // On hand 10, demand 12, reorder point 5: after the update the position is -2 and an order
        // is due. Only the arm that finished its update first can see that.
        assertEquals(1.0, correct.room.ordersPlaced.value,
            "the position after the demand is -2, which is at or below the reorder point of 5")
        assertEquals(listOf(-2.0, 0.0), correct.probe.seen.single(),
            "and the rule saw the state a finished system would show: position -2, on hand 0")

        // Both wrong arms miss it, and miss it silently: the run completes, the report is fine,
        // and the model has quietly run a policy nobody wrote.
        assertEquals(0.0, torn.room.ordersPlaced.value, "the torn read misses the crossing")
        assertEquals(0.0, early.room.ordersPlaced.value, "the early call misses it too")
    }

    @Test
    @DisplayName("The torn read shows a state no finished system could produce; the early one does not")
    fun tornAndEarlyAreDifferentMistakes() {
        val torn = run(CallSite.TORN).probe.seen.single()
        val early = run(CallSite.EARLY).probe.seen.single()

        println()
        println("state the rule was shown — TORN: $torn   EARLY: $early   (position, onHand)")

        // TORN: position says 10, on hand says 0. Those two cannot both be true of any moment; the
        // line that reconciles them had not run. This is the R2b violation proper.
        assertEquals(listOf(10.0, 0.0), torn,
            "a torn read is exactly this: two observations that disagree with each other")

        // EARLY: position 10 with on hand 10 is perfectly consistent -- it is simply the state
        // before the demand. R2b does not catch this and no rule could, because deciding on
        // pre-event state is sometimes precisely right.
        assertEquals(listOf(10.0, 10.0), early,
            "the early call is consistent and about the wrong moment, which is a different fault")
        assertNotEquals(torn, early, "and the two must not be conflated")
    }

    // ---- Example 3: re-entrancy ------------------------------------------------

    @Test
    @DisplayName("A lever write that calls decide() is refused; deferring instead works")
    fun reEntrancyIsRefusedAndDeferralIsTheRepair() {
        val refused = assertFailsWith<ActionApplicationException> {
            val model = Model("ReEntrant")
            ReEntrantRoom(model, deferInstead = false, name = "R")
            model.numberOfReplications = 1
            model.lengthOfReplication = 20.0
            model.simulate()
        }
        // The refusal arrives on the cause: applying an action wraps whatever a lever write throws,
        // so it can report how much of a multi-lever action had been written.
        assertTrue(refused.cause is ReentrantDecisionException,
            "the refusal must be the re-entrancy guard's, not something else: ${refused.cause}")

        val model = Model("Deferred")
        val room = ReEntrantRoom(model, deferInstead = true, name = "R")
        model.numberOfReplications = 1
        model.lengthOfReplication = 20.0
        model.simulate()

        println()
        println("deferring instead: ${room.decisions.value} decisions taken")
        assertEquals(2.0, room.decisions.value,
            "requestDecision only schedules, so the second decision happens after the first has " +
                "returned")

        // Worth stating plainly, because the first version of this example got it wrong and ran
        // 2.1 billion decisions: `requestDecision` is re-entrancy-safe and is NOT termination-safe.
        // It cannot re-enter, because it only schedules -- but a write that always asks for another
        // decision asks forever, all at the same instant, since a zero-delay event lands at the
        // current time. The guard belongs to the caller and there has to be one. `maxEpochs` is the
        // only thing that bounds it otherwise, which is why it survived the decoupling.
    }

    // ---- Example 4: the reward on the wrong row --------------------------------

    @Test
    @DisplayName("Counting after the call moves a cost to the next interval")
    fun aCountAfterTheCallLandsInTheNextInterval() {
        fun rewards(countFirst: Boolean): Pair<List<Double>, Double> {
            val model = Model("Shortfall-$countFirst")
            val room = ShortfallRoom(model, countBeforeDeciding = countFirst, name = "S")
            val sink = MemorySink()
            room.review.attachTransitionSink(sink)
            model.numberOfReplications = 1
            model.lengthOfReplication = 20.0
            model.simulate()
            return sink.records.map { it.reward } to room.review.estimand.value
        }

        val (countedFirst, totalFirst) = rewards(countFirst = true)
        val (countedAfter, totalAfter) = rewards(countFirst = false)

        println()
        println("per-row rewards, counted BEFORE deciding: $countedFirst  (episode total $totalFirst)")
        println("per-row rewards, counted AFTER  deciding: $countedAfter  (episode total $totalAfter)")

        // The substantive claim: the cost has moved to a different interval, so a row is credited
        // with something that happened before the decision it belongs to. A learner trains on
        // these rows, not on the total.
        assertNotEquals(countedFirst, countedAfter,
            "counting on the far side of the call must move the cost to a different row")
        assertEquals(listOf(-25.0, 0.0), countedFirst,
            "counted first, the cost lands in the interval the decision closed")
        assertEquals(listOf(-25.0, -25.0), countedAfter,
            "counted after, it lands in the interval the decision opened -- one row late")

        // Whether the episode TOTAL also moves depends on where the episode boundaries fall: here
        // an increment slips past the last epoch in one arm and not the other. In a run where every
        // increment falls strictly inside the episode the totals agree and only the rows differ,
        // which is the case worth fearing -- nothing in the standard report would show it.
        println("(totals differ here only because a boundary increment falls outside one episode)")
        assertNotEquals(totalFirst, totalAfter)
    }
}
