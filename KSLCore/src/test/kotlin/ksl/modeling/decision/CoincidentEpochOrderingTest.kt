package ksl.modeling.decision

import ksl.modeling.variable.TWResponse
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 *  §10.7 — what actually decides the order when two elements' epochs coincide.
 *
 *  OOD §10.7 claimed the order "is determined by their event priorities and is therefore declared
 *  rather than accidental." Risk 8 recorded the doubt: two elements that both take the default
 *  `epochPriority` have *equal* priorities, so priority decides nothing between them and something
 *  else must. This class finds out what, by measurement.
 *
 *  **What it found.** `KSLEvent.compareTo` breaks a time-and-priority tie on event **id** — "lower
 *  id, implies created earlier" (`KSLEvent.kt:206-210`). Each element schedules its first epoch in
 *  `initialize()`, and `initialize()` runs over the model-element tree, so the tie follows **the
 *  order in which the elements' owning model elements were constructed**.
 *
 *  That is *not* the order of the `decisionElement { }` declarations, which is what an earlier
 *  version of this test — and the `declaredEpochPriority` KDoc — assumed. Declaration order was
 *  measured and it makes no difference: two subsystems built A-then-B decide A-then-B whether the
 *  decision elements inside them are declared in that order or the reverse. Construction order of
 *  the *subsystems* is what moves it. The distinction is not pedantry; it is the difference between
 *  a line a modeler can see at the point of declaration and one they cannot.
 *
 *  Either way the order is deterministic and reproducible, and either way it is **not declared**.
 *  So §10.7's claim is narrowed to what is true, the default is left alone — an automatically
 *  distinct default priority per element would hide the tie rather than resolve it, and would take
 *  away the ability to express a genuine tie — and the remedy is asserted here instead: an explicit
 *  `epochPriority` beats the tie-break in both directions, so a model that needs an order can state
 *  one.
 *
 *  The design still gives a coincidence no game-theoretic meaning (§1.6); that is untouched. The
 *  question here is narrower and purely mechanical: what runs first, and on account of what.
 */
class CoincidentEpochOrderingTest {

    /**
     *  One decision element that records its own name the instant its rule is called, into a list
     *  shared with its siblings. The list is the order of decisions at each coincident instant.
     */
    private class Unit(
        parent: ModelElement,
        name: String,
        private val trace: MutableList<String>
    ) : ModelElement(parent, name) {

        val level = TWResponse(this, name = "${this.name}:Level", initialValue = 1.0)
        var setting: Double = 0.0

        /** Asks for a decision at the current instant, through the deferred entry point. */
        fun review() = review.requestDecision("review")

        lateinit var review: DecisionElement

        fun declare(priority: Int? = null) {
            val me = this.name
            review = decisionElement("${this.name}:Review") {
                observe(level)
                lever(this@Unit, 0.0..10.0, neutral = Neutral.Current { setting },
                    alias = "L") { v -> setting = v }
                if (priority != null) epochPriority = priority
                policy = PolicyIfc { _, _ -> trace += me; doubleArrayOf(1.0) }
            }
        }
    }

    /**
     *  Builds a two-element model, runs it over two coincident epochs, and returns the order in
     *  which the two elements decided.
     *
     *  The two orderings are separate knobs on purpose: they are what distinguishes the claim this
     *  test corrects from the claim it establishes.
     *
     *  @param constructAFirst construct subsystem A before B, or B before A
     *  @param declareAFirst call A's `decisionElement { }` before B's, or the reverse
     *  @param aPriority explicit epoch priority for A, or `null` to take the default
     *  @param bPriority explicit epoch priority for B, or `null` to take the default
     */
    /**
     *  Asks both units for a decision at each coincident instant.
     *
     *  Through `requestDecision`, deliberately: what survives of R15 once the element no longer
     *  owns its timing is the ordering of two *deferred* epochs at one instant. Both post a
     *  zero-delay event, and the executive orders those by `epochPriority` and then by event id.
     *  An immediate `decide` would order by nothing but the order the caller happened to write,
     *  which is the caller's own business and not a property of this subsystem.
     */
    private class Coincide(
        parent: ModelElement,
        private val first: Unit,
        private val second: Unit,
        private val times: List<Double>
    ) : ModelElement(parent, "Coincide") {
        private inner class Fire : EventAction<Nothing>() {
            override fun action(event: KSLEvent<Nothing>) {
                first.review(); second.review()
            }
        }
        override fun initialize() { for (t in times) Fire().schedule(t) }
    }

    private fun order(
        constructAFirst: Boolean,
        declareAFirst: Boolean = constructAFirst,
        aPriority: Int? = null,
        bPriority: Int? = null
    ): List<String> {
        val model = Model("Coincident")
        val trace = mutableListOf<String>()
        val a: Unit
        val b: Unit
        if (constructAFirst) { a = Unit(model, "A", trace); b = Unit(model, "B", trace) }
        else { b = Unit(model, "B", trace); a = Unit(model, "A", trace) }
        if (declareAFirst) { a.declare(aPriority); b.declare(bPriority) }
        else { b.declare(bPriority); a.declare(aPriority) }
        // The requests are made in a fixed order every time, so anything that varies below varies
        // for a reason other than the order they were asked in.
        Coincide(model, a, b, listOf(10.0, 20.0))
        model.numberOfReplications = 1
        model.lengthOfReplication = 25.0          // epochs at 10 and 20, both coincident
        model.simulate()
        return trace.toList()
    }

    /** Both epochs coincide, so the trace must be the same pair twice; returns that pair. */
    private fun pair(trace: List<String>): List<String> {
        assertEquals(4, trace.size, "two elements deciding at t=10 and t=20 is four rule calls")
        assertEquals(trace.subList(0, 2), trace.subList(2, 4),
            "the order at the second coincident epoch differed from the first, so it is not even " +
                "stable within a run — nothing below would mean anything")
        return trace.subList(0, 2)
    }

    /**
     *  What R15 becomes once the element does not own its timing.
     *
     *  The old finding was that at equal priorities the order followed the *construction* order of
     *  the subsystems that owned the elements -- deterministic, reproducible, and invisible at every
     *  site that could have cared. It no longer can: both elements are asked in an order the caller
     *  writes down, so at equal priorities the order is the order they were asked in, whichever way
     *  the subsystems were built or declared.
     *
     *  That is a strictly better position than the one §10.7 had to document, and it is the half of
     *  R15 worth keeping a test for.
     */
    @Test
    fun atEqualPrioritiesTheOrderFollowsTheOrderTheDecisionsWereRequested() {
        println()
        val builtABDeclaredAB = pair(order(constructAFirst = true, declareAFirst = true))
        val builtABDeclaredBA = pair(order(constructAFirst = true, declareAFirst = false))
        val builtBADeclaredAB = pair(order(constructAFirst = false, declareAFirst = true))
        val builtBADeclaredBA = pair(order(constructAFirst = false, declareAFirst = false))

        println("PROBE-10.7 default priorities on both elements, both asked A then B")
        println("    built A,B declared A,B -> ${builtABDeclaredAB.joinToString(", ")}")
        println("    built A,B declared B,A -> ${builtABDeclaredBA.joinToString(", ")}")
        println("    built B,A declared A,B -> ${builtBADeclaredAB.joinToString(", ")}")
        println("    built B,A declared B,A -> ${builtBADeclaredBA.joinToString(", ")}")

        // A is asked first in every arm, so A decides first in every arm -- and neither the
        // construction order nor the declaration order shows through any more.
        for (arm in listOf(builtABDeclaredAB, builtABDeclaredBA, builtBADeclaredAB, builtBADeclaredBA)) {
            assertEquals(listOf("A", "B"), arm,
                "at equal priorities the order must follow the order the decisions were requested; " +
                    "if construction order still showed through, the incidental dependency §10.7 " +
                    "had to document would have survived the decoupling")
        }
    }

    /**
     *  The remedy, measured. An element that must act first can say so, and what it says beats the
     *  tie-break in **both** directions — which is what makes correcting the claim, rather than
     *  changing the default, the right response to the finding above.
     */
    @Test
    fun anExplicitEpochPriorityDecidesTheOrderWhicheverWayTheElementsAreBuilt() {
        println()
        // MEDIUM_PRIORITY (10 000) sorts ahead of the default MEDIUM_LOW_PRIORITY (100 000):
        // KSLEvent.compareTo takes the smaller priority first.
        val ahead = KSLEvent.MEDIUM_PRIORITY

        val bAheadBuiltAB = pair(order(constructAFirst = true, bPriority = ahead))
        val bAheadBuiltBA = pair(order(constructAFirst = false, bPriority = ahead))
        val aAheadBuiltAB = pair(order(constructAFirst = true, aPriority = ahead))
        val aAheadBuiltBA = pair(order(constructAFirst = false, aPriority = ahead))

        println("PROBE-10.7 one element given epochPriority = MEDIUM_PRIORITY")
        println("    B ahead, built A,B → ${bAheadBuiltAB.joinToString(", ")}")
        println("    B ahead, built B,A → ${bAheadBuiltBA.joinToString(", ")}")
        println("    A ahead, built A,B → ${aAheadBuiltAB.joinToString(", ")}")
        println("    A ahead, built B,A → ${aAheadBuiltBA.joinToString(", ")}")

        assertEquals(listOf("B", "A"), bAheadBuiltAB,
            "B was constructed second and given the earlier priority; priority must win")
        assertEquals(listOf("B", "A"), bAheadBuiltBA)
        assertEquals(listOf("A", "B"), aAheadBuiltBA,
            "A was constructed second and given the earlier priority; priority must win here too, " +
                "or the remedy only works when it agrees with the construction order and is no remedy")
        assertEquals(listOf("A", "B"), aAheadBuiltAB)
    }

    /**
     *  The priority an element will actually use is readable before the run, so a model that cares
     *  about coincident order can check it rather than infer it from a trace.
     */
    @Test
    fun theEpochPriorityAnElementWillUseIsVisibleFromOutside() {
        val model = Model("Visible")
        val trace = mutableListOf<String>()
        val a = Unit(model, "A", trace)
        val b = Unit(model, "B", trace)
        a.declare()
        b.declare(KSLEvent.MEDIUM_PRIORITY)

        val ea = model.getModelElement("A:Review") as DecisionElement
        val eb = model.getModelElement("B:Review") as DecisionElement
        println()
        println("declared epoch priorities: A=${ea.declaredEpochPriority} B=${eb.declaredEpochPriority}")

        assertEquals(KSLEvent.MEDIUM_LOW_PRIORITY, ea.declaredEpochPriority,
            "the default is documented as MEDIUM_LOW_PRIORITY; a modeler comparing two elements " +
                "needs the default to be a stated number rather than one they must discover")
        assertEquals(KSLEvent.MEDIUM_PRIORITY, eb.declaredEpochPriority)
        assertTrue(eb.declaredEpochPriority < ea.declaredEpochPriority,
            "and lower sorts earlier, so the comparison a modeler would make is the right one")
    }
}
