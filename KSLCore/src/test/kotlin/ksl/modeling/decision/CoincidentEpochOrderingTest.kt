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

        fun declare(priority: Int? = null) {
            val me = this.name
            decisionElement("${this.name}:Review") {
                observe(level)
                lever(this@Unit, 0.0..10.0, neutral = Neutral.Current { setting },
                    alias = "L") { v -> setting = v }
                every(10.0)
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
     *  The measurement risk 8 asked for: at equal priority, is the order declared or incidental?
     *
     *  It is incidental, and it is incidental to something less visible than the first guess.
     *  Swapping the two `decisionElement` declarations changes nothing; swapping the construction
     *  of the two subsystems that own them swaps the decisions.
     */
    @Test
    fun atEqualPrioritiesTheOrderFollowsConstructionOrderAndNotTheDeclarations() {
        println()
        val builtABDeclaredAB = pair(order(constructAFirst = true, declareAFirst = true))
        val builtABDeclaredBA = pair(order(constructAFirst = true, declareAFirst = false))
        val builtBADeclaredAB = pair(order(constructAFirst = false, declareAFirst = true))
        val builtBADeclaredBA = pair(order(constructAFirst = false, declareAFirst = false))

        println("PROBE-10.7 default priorities on both elements")
        println("    built A,B  declared A,B → decided ${builtABDeclaredAB.joinToString(", ")}")
        println("    built A,B  declared B,A → decided ${builtABDeclaredBA.joinToString(", ")}")
        println("    built B,A  declared A,B → decided ${builtBADeclaredAB.joinToString(", ")}")
        println("    built B,A  declared B,A → decided ${builtBADeclaredBA.joinToString(", ")}")

        // Reproducible. If this fails the subsystem is non-deterministic and every Level-1 and
        // Level-2 guarantee elsewhere in this suite is void, so it is checked before anything else.
        assertEquals(builtABDeclaredAB, pair(order(constructAFirst = true, declareAFirst = true)),
            "the tie-break must at least be reproducible across identical runs")

        // Declaration order is NOT what decides it. Both rows built A-then-B agree.
        assertEquals(builtABDeclaredAB, builtABDeclaredBA,
            "reversing the two decisionElement declarations changed the decision order, which " +
                "would mean the tie follows the declarations after all — the KDoc on " +
                "declaredEpochPriority used to say exactly that and this is what refuted it")

        // Construction order IS. The two pairs disagree, so something incidental is load-bearing.
        assertEquals(listOf("A", "B"), builtABDeclaredAB)
        assertEquals(listOf("B", "A"), builtBADeclaredAB)
        assertEquals(builtBADeclaredAB, builtBADeclaredBA)
        assertNotEquals(builtABDeclaredAB, builtBADeclaredAB,
            "if these agreed, coincident order would be independent of everything incidental and " +
                "§10.7's original claim would have been right after all")
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
