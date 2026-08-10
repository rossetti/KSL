package ksl.modeling.decision

import ksl.modeling.decision.descriptor.DecisionSurfaceDescriptor
import ksl.modeling.decision.descriptor.FeasibilityPolicy
import ksl.modeling.decision.descriptor.RewardSense
import ksl.modeling.variable.TWResponse
import ksl.simulation.Model
import ksl.simulation.ModelElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 *  §4.1.3.1 — a parameterization call either takes full effect or none of it.
 *
 *  The property is per **call**, and it is achieved by ordering rather than by rollback: everything
 *  that can fail is done before anything is committed, and the one irreversible act — closing a
 *  superseded policy — happens after every fallible step has succeeded.
 *
 *  This matters more than the usual tidiness argument because §4.9's paired comparison is only
 *  meaningful if two runs differ in exactly one parameter (§4.1.3). A half-applied parameterization
 *  produces a run nobody specified, and the comparison it feeds reads as though they had.
 */
class ParameterizationAtomicityTest {

    private class Tank(parent: ModelElement, name: String) : ModelElement(parent, name) {
        val level = TWResponse(this, name = "$name:Level", initialValue = 2.0)
        var setting: Double = 0.0
    }

    private class Resourceful(private val label: String) : ManagedPolicyIfc {
        var closes = 0; private set
        override fun close() { closes++ }
        override fun action(observation: DoubleArray, ctx: DecisionContext) = doubleArrayOf(1.0)
        override fun toString() = label
    }

    /** Refuses the surface, exactly as `FixedPolicy` does on an arity mismatch. */
    private class RefusesTheSurface : ShapeAwarePolicyIfc {
        override fun configure(surface: DecisionSurfaceDescriptor) {
            throw IllegalArgumentException("this rule does not fit that declaration")
        }
        override fun action(observation: DoubleArray, ctx: DecisionContext) = doubleArrayOf(1.0)
    }

    private fun element(model: Model, p: PolicyIfc): DecisionElement {
        val tank = Tank(model, "T")
        return tank.decisionElement("D") {
            observe(tank.level)
            lever(tank, 0..10, neutral = Neutral.Current { setting }, alias = "L") { v -> setting = v }
            reward(tank.level, rate = 1.0, sense = RewardSense.COST, alias = "R")
            every(10.0)
            policy = p
        }
    }

    // ---------------------------------------------------------------- the policy setter

    /**
     *  The defect this test exists for.
     *
     *  Measured before the fix: assigning a policy that refuses the surface closed the incumbent,
     *  put the refusing policy in its place, and *then* threw — so the caller held an exception
     *  reading "nothing happened" while the element held a rule that had just said it did not fit,
     *  and the previous rule's resources were released beyond recovery.
     */
    @Test
    fun aPolicyThatRefusesTheSurfaceChangesNothing() {
        val model = Model("Refused")
        val incumbent = Resourceful("incumbent")
        val e = element(model, incumbent)

        assertFailsWith<IllegalArgumentException> { e.policy = RefusesTheSurface() }

        println()
        println("after a refused assignment: holds=${e.policy}, incumbent.closes=${incumbent.closes}")

        assertSame(incumbent, e.policy,
            "a refused assignment must leave the incumbent in place — it is the only rule that " +
                "has been shown to fit this declaration")
        assertEquals(0, incumbent.closes,
            "and must not have closed it. Closing is irreversible, so it has to happen after " +
                "everything that can fail, not before (§4.1.3.1)")
    }

    /** The same, through the ordinary documented path rather than a bespoke policy. */
    @Test
    fun anArityMismatchOnFixedPolicyChangesNothing() {
        val model = Model("Arity")
        val incumbent = Resourceful("incumbent")
        val e = element(model, incumbent)

        // One lever is declared; three values are offered.
        assertFailsWith<IllegalArgumentException> { e.policy = FixedPolicy(doubleArrayOf(1.0, 2.0, 3.0)) }

        assertSame(incumbent, e.policy)
        assertEquals(0, incumbent.closes)

        // And a well-shaped assignment still works afterwards, so the refusal left nothing latched.
        e.policy = FixedPolicy(doubleArrayOf(4.0))
        assertEquals(1, incumbent.closes, "the incumbent is closed by the assignment that succeeds")
    }

    // ---------------------------------------------------------------- narrowing

    /** Narrowing validates both bounds before writing either, so a rejection leaves the pair intact. */
    @Test
    fun aRejectedNarrowingLeavesBothBoundsAsTheyWere() {
        val model = Model("Narrow")
        val e = element(model, NeutralPolicy)
        val ref = e.leverRef("L")

        e.narrow(ref, 2..8)
        assertEquals(2..8, e.limitsOf(ref))

        // Non-integral bounds on an INTEGER domain.
        assertFailsWith<NarrowingException> { e.narrow(ref, 1.5..7.5) }
        assertEquals(2..8, e.limitsOf(ref), "the lower bound must not have been written alone")

        // Widening past the model's own limits.
        assertFailsWith<NarrowingException> { e.narrow(ref, -1.0..20.0) }
        assertEquals(2..8, e.limitsOf(ref))

        // A partial overlap: the lower bound is legal, the upper is not. This is the case that
        // would expose a setter writing as it validates.
        assertFailsWith<NarrowingException> { e.narrow(ref, 3.0..40.0) }
        assertEquals(2..8, e.limitsOf(ref),
            "the legal half of an illegal narrowing must not survive it")
    }

    // ---------------------------------------------------------------- reward rates

    /** A rate rejected for naming a foreign term leaves the declared rates untouched. */
    @Test
    fun aRejectedRewardRateLeavesTheRatesAsTheyWere() {
        val model = Model("Rates")
        val a = element(model, NeutralPolicy)
        val tank2 = Tank(model, "U")
        val b = tank2.decisionElement("E") {
            observe(tank2.level)
            lever(tank2, 0..10, neutral = Neutral.Current { setting }, alias = "L") { v -> setting = v }
            reward(tank2.level, rate = 1.0, sense = RewardSense.COST, alias = "R")
            every(10.0)
            policy = NeutralPolicy
        }

        val mine = a.rewardRef("R")
        val foreign = b.rewardRef("R")

        a.rewardRate(mine, 5.0)
        val rate = { a.descriptor().rewards.first { r -> r.name == "R" }.rate }
        assertEquals(5.0, rate(), 1e-9)

        // A reference issued by the other element. It resolves perfectly well — just not here,
        // which is the case D.15 found and §4.1.2.2 closed.
        assertFailsWith<BindingException> { a.rewardRate(foreign, 99.0) }
        assertEquals(5.0, rate(), "a reference from another element must change nothing here")

        // And the element is still usable afterwards: the rejection latched nothing.
        a.rewardRate(mine, 6.0)
        assertEquals(6.0, rate(), 1e-9)
    }

    // ---------------------------------------------------------------- the guard itself

    /**
     *  Every parameterization entry point refuses while the model is running (§4.1.3), which is the
     *  other half of the property: a call that cannot happen mid-run cannot be half-applied by a
     *  replication starting underneath it.
     */
    @Test
    fun everySetterRefusesWhileTheModelIsRunning() {
        val model = Model("Running")
        val tank = Tank(model, "T")
        val attempts = mutableListOf<String>()
        lateinit var e: DecisionElement

        e = tank.decisionElement("D") {
            observe(tank.level)
            lever(tank, 0..10, neutral = Neutral.Current { setting }, alias = "L") { v -> setting = v }
            reward(tank.level, rate = 1.0, sense = RewardSense.COST, alias = "R")
            every(10.0)
            policy = object : PolicyIfc {
                override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
                    // Each of these must throw, from inside a running replication.
                    fun attempt(name: String, block: () -> Unit) {
                        val t = runCatching(block).exceptionOrNull()
                        if (t is IllegalStateException) attempts += name
                    }
                    attempt("policy") { e.policy = NeutralPolicy }
                    attempt("epochInterval") { e.epochInterval = 20.0 }
                    attempt("feasibilityPolicy") { e.feasibilityPolicy = FeasibilityPolicy.CLAMP_THEN_REJECT }
                    attempt("maxEpochs") { e.maxEpochs = 3 }
                    attempt("policyLabel") { e.policyLabel = "x" }
                    attempt("narrow") { e.narrow(e.leverRef("L"), 1..9) }
                    attempt("rewardRate") { e.rewardRate(e.rewardRef("R"), 2.0) }
                    return doubleArrayOf(1.0)
                }
            }
        }
        model.numberOfReplications = 1
        model.lengthOfReplication = 25.0
        model.simulate()

        println()
        println("setters that refused from inside a replication: $attempts")
        assertEquals(
            listOf("policy", "epochInterval", "feasibilityPolicy", "maxEpochs", "policyLabel",
                "narrow", "rewardRate"),
            attempts.distinct(),
            "every parameterization entry point must refuse while running (§4.1.3)"
        )
        assertTrue(e.epochInterval == 10.0, "and none of them took effect")
    }
}
