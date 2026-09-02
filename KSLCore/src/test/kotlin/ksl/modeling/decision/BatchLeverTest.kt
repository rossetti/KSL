package ksl.modeling.decision

import ksl.examples.general.decision.reviewEvery
import ksl.modeling.decision.descriptor.RewardSense
import ksl.modeling.variable.TWResponse
import ksl.simulation.Model
import ksl.simulation.ModelElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  §4.4.5 — the model-authored atomic multi-lever write.
 *
 *  ADR-6 promises the ordering rule and the endpoint guarantee and explicitly does **not** promise
 *  cross-lever atomicity, naming this as the escape hatch where atomicity is genuinely required.
 *  Until now the escape hatch threw at the declaration, so a model needing it had no route at all
 *  and the coverage matrix carried it as REFUSED.
 *
 *  The case it exists for is a group under an equality budget. Writing `(3, 3)` to a pair currently
 *  at `(4, 2)` under `sum == 6` passes through `(3, 2)` — sum 5 — between the two writes. Nothing
 *  the element can do about that with individual writes: the writes have synchronous consequences
 *  inside the model, so buffering them under a lock would not help. One function that moves both is
 *  the only thing that can.
 */
class BatchLeverTest {

    /**
     *  Two staffing pools under a fixed headcount, with an observer that records the *total* every
     *  time either pool changes — which is how a model sees its own intermediate states.
     */
    private class Pools(parent: ModelElement, name: String) : ModelElement(parent, name) {
        val load = TWResponse(this, name = "${this.name}:Load", initialValue = 2.0)
        var a: Int = 4
            private set
        var b: Int = 2
            private set

        /** Every total this model has ever held, in order. */
        val totalsSeen = mutableListOf<Int>()

        private fun observe() { totalsSeen += a + b }

        fun setA(v: Int) { a = v; observe() }
        fun setB(v: Int) { b = v; observe() }

        /** The atomic move: both pools change, one observation. */
        fun setBoth(values: DoubleArray) {
            a = values[0].toInt()
            b = values[1].toInt()
            observe()
        }
    }

    private fun build(model: Model, batched: Boolean, target: DoubleArray): Pools {
        val p = Pools(model, "P")
        p.decisionElement("P:Review") {
            observe(p.load)
            val ra = lever(p, 0..6, neutral = Neutral.Current { a.toDouble() }, alias = "A") { v -> setA(v.toInt()) }
            val rb = lever(p, 0..6, neutral = Neutral.Current { this.b.toDouble() }, alias = "B") { v -> setB(v.toInt()) }
            budget(ra, rb, total = 6.0)
            if (batched) batchLever(ra, rb) { values -> p.setBoth(values) }
            policy = PolicyIfc { _, _ -> target.copyOf() }
        }.reviewEvery(p, 10.0)
        return p
    }

    /**
     *  The defect the escape hatch exists for, and the proof that it is closed.
     *
     *  Unbatched, the pair transits a total of 5 on its way from (4,2) to (3,3). Batched, every
     *  total the model ever observes is 6.
     */
    @Test
    fun aBatchNeverLetsTheModelSeeAnIntermediateTotal() {
        fun run(batched: Boolean): Pools {
            val model = Model(if (batched) "Batched" else "Unbatched")
            val p = build(model, batched, doubleArrayOf(3.0, 3.0))
            model.numberOfReplications = 1
            model.lengthOfReplication = 25.0
            model.simulate()
            return p
        }

        val unbatched = run(false)
        val batched = run(true)

        println()
        println("totals the model saw, unbatched: ${unbatched.totalsSeen}")
        println("totals the model saw, batched  : ${batched.totalsSeen}")

        assertTrue(unbatched.totalsSeen.any { it != 6 },
            "the unbatched arm is supposed to transit an infeasible total — if it does not, this " +
                "test is not exercising the case batchLever exists for")
        assertTrue(batched.totalsSeen.all { it == 6 },
            "a batch is one act: the model must never observe a total other than the budget")

        // Both arms end in the same place. Batching changes how the values arrive, not what they are.
        assertEquals(3, unbatched.a); assertEquals(3, unbatched.b)
        assertEquals(3, batched.a); assertEquals(3, batched.b)
    }

    /** A batch receives every member's value, including members that did not move. */
    @Test
    fun aBatchReceivesTheWholeVectorIncludingUnmovedMembers() {
        val model = Model("Partial")
        val seen = mutableListOf<List<Double>>()
        val p = Pools(model, "P")
        p.decisionElement("P:Review") {
            observe(p.load)
            val ra = lever(p, 0..6, neutral = Neutral.Current { a.toDouble() }, alias = "A") { v -> setA(v.toInt()) }
            val rb = lever(p, 0..6, neutral = Neutral.Current { this.b.toDouble() }, alias = "B") { v -> setB(v.toInt()) }
            batchLever(ra, rb) { values -> seen += values.toList(); p.setBoth(values) }
            // A stays at 4 — it does not move — while B goes 2 → 2 as well. Neither moves.
            policy = PolicyIfc { _, _ -> doubleArrayOf(4.0, 2.0) }
        }.reviewEvery(p, 10.0)
        model.numberOfReplications = 1
        model.lengthOfReplication = 25.0
        model.simulate()

        println()
        println("vectors handed to applyAll when nothing moved: $seen")
        assertTrue(seen.isNotEmpty(), "the batch was applied at all")
        assertTrue(seen.all { it == listOf(4.0, 2.0) },
            "a batch is one call and cannot be given a partial vector, so the elision that applies " +
                "to an individual setting must not apply within a group")
    }

    /** Validation is unchanged: batching changes how values arrive, not whether they are allowed. */
    @Test
    fun aBatchedLeverIsStillValidatedAndAnInfeasibleActionWritesNothing() {
        val model = Model("Infeasible")
        val p = build(model, batched = true, target = doubleArrayOf(9.0, 9.0))   // outside 0..6
        model.numberOfReplications = 1
        model.lengthOfReplication = 25.0

        assertFailsWith<ActionValidationException> { model.simulate() }
        assertEquals(4, p.a, "no lever was written — including the batched ones")
        assertEquals(2, p.b)
        assertTrue(p.totalsSeen.isEmpty(), "and the model observed nothing at all")
    }

    // ------------------------------------------------------------------ declaration validation

    @Test
    fun theDeclarationRefusesGroupsThatCannotMeanAnything() {
        fun declaring(block: DecisionElementBuilder.(Pools) -> Unit): Throwable? {
            val model = Model("Declare")
            val p = Pools(model, "P")
            return runCatching { p.decisionElement("P:Review") { block(p) } }.exceptionOrNull()
        }

        fun base(bld: DecisionElementBuilder, p: Pools): Pair<LeverRef, LeverRef> {
            bld.observe(p.load)
            val ra = bld.lever(p, 0..6, neutral = Neutral.Current { a.toDouble() }, alias = "A") { v -> setA(v.toInt()) }
            val rb = bld.lever(p, 0..6, neutral = Neutral.Current { this.b.toDouble() }, alias = "B") { v -> setB(v.toInt()) }
            bld.policy = NeutralPolicy
            return ra to rb
        }

        println()
        val cases = listOf<Pair<String, DecisionElementBuilder.(Pools) -> Unit>>(
            "a group of one" to { p -> val (ra, _) = base(this, p); batchLever(ra) { } },
            "the same lever twice" to { p -> val (ra, _) = base(this, p); batchLever(ra, ra) { } },
            "a lever in two groups" to { p ->
                val (ra, rb) = base(this, p); batchLever(ra, rb) { }; batchLever(ra, rb) { }
            }
        )
        for ((label, block) in cases) {
            val t = declaring(block)
            println("  %-22s → %s".format(label, t?.let { it::class.simpleName } ?: "ACCEPTED"))
            assertTrue(t is IllegalArgumentException, "$label must be refused at the declaration")
            assertTrue(t.message!!.isNotBlank())
        }
    }

    /** A ref from another element is refused, as everywhere else refs are consumed. */
    @Test
    fun aForeignLeverReferenceIsRefused() {
        val model = Model("Foreign")
        val p = Pools(model, "P")
        val q = Pools(model, "Q")
        val other = q.decisionElement("Q:Review") {
            observe(q.load)
            lever(q, 0..6, neutral = Neutral.Current { a.toDouble() }, alias = "A") { v -> setA(v.toInt()) }
            policy = NeutralPolicy
        }.reviewEvery(q, 10.0)
        val foreign = other.leverRef("A")

        val t = runCatching {
            p.decisionElement("P:Review") {
                observe(p.load)
                val ra = lever(p, 0..6, neutral = Neutral.Current { a.toDouble() }, alias = "A") { v -> setA(v.toInt()) }
                policy = NeutralPolicy
                batchLever(ra, foreign) { }
            }.reviewEvery(p, 10.0)
        }.exceptionOrNull()

        assertTrue(t is IllegalArgumentException, "a foreign ref must be refused")
        assertTrue(t.message!!.contains("Q:Review"), "and the message must name the element it came from")
    }
}
