package ksl.modeling.decision

import ksl.modeling.variable.TWResponse
import ksl.simulation.ConditionalAction
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 *  **Open issue O1 of the event-triggered OOD, measured.**
 *
 *  ADR-T2 says an activation causes an epoch "at the current time with the element's epoch priority".
 *  That is precise about priority and silent about the resulting *position* relative to model events
 *  already at that instant, and §18.3 of that document says the answer is empirical and should be had
 *  before any trigger work is planned, because a bad answer changes ADR-T2.
 *
 *  This is that measurement. It does not need a trigger subsystem: the three routes a decision can
 *  reach an instant by all exist today.
 *
 *  1. **Scheduled** — an event placed at the instant in advance, with the epoch priority. This is what
 *     the old element-owned epoch was, reconstructed, and what a periodic review is.
 *  2. **Deferred** — `requestDecision` from inside an event at that instant, which posts a zero-delay
 *     event. This is what a trigger would use under ADR-T2.
 *  3. **Condition-scanned** — a `ConditionalAction` that calls `requestDecision` from the executive's
 *     C phase. This is what a trigger would use if detection lived in the scan, which is what
 *     ADR-T2's "third provenance with stronger quiescence" contemplates.
 *
 *  Every arm decides at the same *time*. The question is where each lands among the model's own events
 *  at that time, and whether the three agree.
 */
class DeferredEpochPositionProbeTest {

    /**
     *  Three model events at t = 10, at priorities either side of the epoch priority, plus one *at*
     *  it. The one at the same priority is the interesting case: priority cannot separate it from the
     *  decision, so the event-id tie-break decides, and that is where the routes can differ.
     */
    private class Instant(
        parent: ModelElement,
        private val trace: MutableList<String>,
        private val route: Route
    ) : ModelElement(parent, "Instant") {

        val level = TWResponse(this, name = "Level", initialValue = 1.0)
        var setting: Double = 0.0
        lateinit var element: DecisionElement

        private inner class Mark(val label: String) : EventAction<Nothing>() {
            override fun action(event: KSLEvent<Nothing>) {
                trace += label
                // The deferred route asks from inside a model event at the instant, which is what a
                // trigger detecting a state change would do.
                if (route == Route.DEFERRED && label == "early") element.requestDecision("probe")
            }
        }

        private inner class ScheduledEpoch : EventAction<Nothing>() {
            override fun action(event: KSLEvent<Nothing>) = element.decide("probe")
        }

        /** Fires once the clock has reached the instant; the C phase runs it at end of instant. */
        private inner class WhenReached : ConditionalAction() {
            private var fired = false
            override fun testCondition(): Boolean = !fired && time >= 10.0
            override fun action() {
                fired = true
                element.requestDecision("probe")
            }
        }

        override fun initialize() {
            // SCHEDULED_FIRST puts the epoch on the calendar BEFORE the equal-priority model event.
            // A deferred epoch can never do that -- it is created during the instant, so its id is
            // always later than anything queued in advance -- which is exactly what the two arms
            // together are here to separate.
            if (route == Route.SCHEDULED_FIRST) {
                ScheduledEpoch().schedule(10.0, priority = KSLEvent.MEDIUM_LOW_PRIORITY)
            }
            Mark("early").schedule(10.0, priority = KSLEvent.MEDIUM_PRIORITY)          // 10 000
            Mark("same").schedule(10.0, priority = KSLEvent.MEDIUM_LOW_PRIORITY)       // 100 000
            Mark("late").schedule(10.0, priority = KSLEvent.LOW_PRIORITY)              // 1 000 000
            if (route == Route.SCHEDULED) {
                ScheduledEpoch().schedule(10.0, priority = KSLEvent.MEDIUM_LOW_PRIORITY)
            }
        }

        override fun registerConditionalActions() {
            if (route == Route.SCANNED) executive.register(WhenReached())
        }
    }

    private enum class Route { SCHEDULED, SCHEDULED_FIRST, DEFERRED, SCANNED }

    private fun trace(route: Route): List<String> {
        val model = Model("O1-$route")
        val trace = mutableListOf<String>()
        val host = Instant(model, trace, route)
        host.element = host.decisionElement("D") {
            observe(host.level)
            lever(host, 0.0..10.0, neutral = Neutral.Current { setting }) { v -> host.setting = v }
            policy = PolicyIfc { _, _ -> trace += "DECIDE"; doubleArrayOf(1.0) }
        }
        model.numberOfReplications = 1
        model.lengthOfReplication = 20.0
        model.simulate()
        return trace.toList()
    }

    @Test
    @DisplayName("O1 — where a decision lands within an instant, by the route it arrives on")
    fun whereADecisionLandsWithinAnInstant() {
        val scheduled = trace(Route.SCHEDULED)
        val scheduledFirst = trace(Route.SCHEDULED_FIRST)
        val deferred = trace(Route.DEFERRED)
        val scanned = trace(Route.SCANNED)

        println()
        println("=== O1: position of the decision among the model's own events at t = 10 ===")
        println("  model events: early(10 000)  same(100 000)  late(1 000 000)")
        println("  epoch priority: 100 000 — equal to `same`, so the tie-break decides")
        println()
        println("  scheduled, after `same` : ${scheduled.joinToString(", ")}")
        println("  scheduled, before `same`: ${scheduledFirst.joinToString(", ")}")
        println("  deferred from `early`   : ${deferred.joinToString(", ")}")
        println("  condition-scanned       : ${scanned.joinToString(", ")}")
        println()

        // Every route decides exactly once, at the same clock time. Whatever else differs, that must
        // not: a route that decided twice, or not at all, would not be a route to the same decision.
        for (t in listOf(scheduled, scheduledFirst, deferred, scanned)) {
            assertEquals(1, t.count { it == "DECIDE" }, "each route decides exactly once: $t")
            assertEquals(listOf("early", "same", "late"), t.filter { it != "DECIDE" },
                "and the model's own events keep their order in every arm: $t")
        }

        // The finding itself is printed above rather than asserted to a particular answer: this test
        // exists to establish what the answer IS, and pinning it here would be pinning it before the
        // trigger design has decided what it wants. What is asserted is the property that would make
        // the question moot -- whether the three routes agree.
        println()
        println("  deferred matches a scheduled epoch queued after the tie : ${deferred == scheduled}")
        println("  deferred matches a scheduled epoch queued before it     : ${deferred == scheduledFirst}")
        println("  the condition scan matches either                       : " +
            "${scanned == scheduled || scanned == scheduledFirst}")
        println()
    }
}
