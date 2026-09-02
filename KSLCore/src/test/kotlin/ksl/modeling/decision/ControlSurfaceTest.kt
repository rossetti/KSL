package ksl.modeling.decision

import ksl.examples.general.decision.reviewEvery
import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.modeling.variable.TWResponse
import ksl.simulation.Model
import ksl.simulation.ModelElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  §18.2 item 2 — which parameterization properties become `@KSLControl`s, and how that squares
 *  with the design's answer to clamp-versus-reject.
 *
 *  **The obstacle the issue named was real and KSL had already settled it.** A numeric control
 *  *clamps*: `Control.setPropertyFromDouble` calls `limitToRange` and reports nothing. The design's
 *  parameterization rule is the opposite — a refused narrowing changes nothing and says why
 *  (§4.1.3.1) — and ADR-12 says an action outside a feasible set is rejected rather than quietly
 *  repaired. Two opposite answers to what looks like one question.
 *
 *  They are not one question, and KSL's own inventory policies show the resolution in three parts,
 *  quoted from where it is written down:
 *
 *  1. *"The control-set path stores the value without the strict positivity check so a **clamped
 *     write can never throw**"* (`InventoryPolicyReorderPointOrderUpToLevelPeriodic.kt:92`).
 *  2. Cross-field constraints are removed by **reparameterization**, not checked at control-set
 *     time: *"every clamped (r, SDelta >= 1) combination satisfies r < S by construction, so no
 *     cross-field validation is needed at control-set time and optimizers see a box-constrained
 *     space"* (`InventoryPolicyReorderPointOrderUpToLevel.kt:47`).
 *  3. Whatever check survives is **deferred to replication start**, where it fails fast.
 *
 *  This subsystem adopts (1) without weakening any setter, by a rule that makes the two answers
 *  agree instead of choosing between them: **a control's declared bounds are the exact domain of
 *  the property it writes.** The clamp then lands only on values the programmatic path would also
 *  have accepted, so "the control path clamps" and "the programmatic path rejects" never disagree
 *  about the same value. That is R16, and this class is where it is checked.
 *
 *  Two negative results are recorded here as well, because both were candidates in the issue and
 *  both are impossible rather than merely undesirable — see
 *  [perLeverBoundsAndRewardRatesCannotBeControlsAndThisIsWhy].
 */
class ControlSurfaceTest {

    private class Station(parent: ModelElement, name: String, levers: Int = 1) :
        ModelElement(parent, name) {
        val level = TWResponse(this, name = "${this.name}:Level", initialValue = 2.0)
        var setting: Double = 0.0
        val review: DecisionElement = decisionElement("${this.name}:Review") {
            observe(level)
            repeat(levers) { i ->
                lever(this@Station, 0.0..10.0, neutral = Neutral.Value(0.0), alias = "L$i") { v -> setting = v }
            }
            reward(level, rate = 1.0, sense = ksl.modeling.decision.descriptor.RewardSense.COST, alias = "R")
            policy = NeutralPolicy
        }.reviewEvery(this, 10.0)
    }

    private fun keysOf(m: Model, element: String) =
        m.controls().controlKeys().filter { it.startsWith("$element.") }.sorted()

    // ------------------------------------------------------------------ what the element exposes

    /**
     *  The element's control surface, named. It is deliberately two properties, not a policy
     *  parameter among them: what a *rule* is parameterized by belongs to the rule
     *  ([aRuleThatIsAModelElementIsSearchableWithNoNewApi]).
     */
    @Test
    fun theElementExposesExactlyTheTwoScalarsThatAreGenuineDecisionVariables() {
        val m = Model("Surface")
        Station(m, "A")
        val keys = keysOf(m, "A:Review")
        println()
        println("controls on a decision element: $keys")

        assertEquals(listOf("A:Review.maxEpochs"), keys,
            "the epoch interval is a review period, which KSL already treats as a decision " +
                "variable, and the episode cap is the other box-constrained scalar the element " +
                "owns. Anything else appearing here should be argued for, not acquired")
    }

    /**
     *  **R16, measured.** For every control the element exposes, a write far outside its bounds
     *  must clamp onto a value the property's own setter accepts — never onto one it refuses.
     *
     *  This is the assertion that reconciles the control path with §4.1.3.1. If a declared bound
     *  were outside the setter's domain, the clamp would hand the setter a value it rejects and a
     *  numeric control write would throw — which KSL documents that numeric controls do not do.
     *
     *  It already caught one, on a control that no longer exists: the default upper bound of `+∞`
     *  clamped onto an infinite epoch interval, which the declaration refused and the property
     *  setter then accepted. Both were fixed. Epoch timing has since left the element entirely
     *  (S§C.0), so the rule is now carried by `maxEpochs` — and by the review period, which is a
     *  control of the caller that schedules reviews. This assertion is what will find the next one.
     */
    @Test
    fun everyControlBoundIsAValueItsOwnSetterAccepts() {
        val m = Model("Bounds")
        val s = Station(m, "A")
        val controls = m.controls()

        println()
        for (key in keysOf(m, "A:Review")) {
            val c = controls.control(key)!!
            // The extremes are what the clamp maps onto the declared bounds.
            for (probe in listOf(-1e300, Double.NEGATIVE_INFINITY, 1e300, Double.POSITIVE_INFINITY)) {
                val outcome = runCatching { c.value = probe; c.value }
                println("  %-26s <- %-10s => %s".format(
                    key, probe, outcome.getOrNull() ?: "THREW ${outcome.exceptionOrNull()!!::class.simpleName}"))
                assertTrue(outcome.isSuccess,
                    "$key threw on a clamped write of $probe. A numeric control clamps, so the " +
                        "declared bound was handed to a setter that refuses it — the bound and the " +
                        "setter's domain have come apart (R16)")
            }
        }

        // And the clamp landed on a value the setter would also accept, not merely one the control
        // path tolerates. R16's rule is unchanged; only its subject is. It used to be carried by
        // epochInterval, whose domain was "finite and > 0.0" and whose bounds were therefore
        // Double.MIN_VALUE and Double.MAX_VALUE. Timing left the element (S§C.0), so the claim now
        // rides on the one scalar that remains a genuine decision variable.
        assertTrue(s.review.maxEpochs > 0, "a clamped episode cap is still a legal one")

        // The bounds themselves are the domain, stated so a change to either is a visible change.
        val cap = controls.control("A:Review.maxEpochs")!!
        assertEquals(1.0, cap.lowerBound, "the smallest value a cap of `> 0` admits")
    }

    /**
     *  The one input the clamp does not cover, measured rather than assumed.
     *
     *  `limitToRange` compares with `<=` and `>=`, and every comparison against `NaN` is false, so
     *  a `NaN` passes through unclamped and reaches the setter raw. The setter refuses it, which
     *  means this is the single case where a numeric control write on this subsystem can throw.
     *
     *  It is left that way deliberately. `simopt` cannot produce it — `InputDefinition` requires
     *  finite bounds and `ProblemDefinition` clamps to them — so it can only come from a
     *  hand-built map, and for that caller a refusal naming the property is better than a run
     *  scheduling its next epoch at `NaN`. The deviation is narrow, and stating it is the point.
     *
     *  **The refusal arrives wrapped**, and that is measured rather than assumed: a control writes
     *  through `property.setter.call(...)`, so anything the setter throws reaches the caller as an
     *  `InvocationTargetException` with the real exception as its `cause`. A caller catching
     *  around a control write must look at the cause; the message is not lost, but it is not on the
     *  top-level exception either.
     */
    @Test
    fun naNIsTheOneValueTheClampDoesNotCoverAndItIsRefusedRatherThanAccepted() {
        val m = Model("Nan")
        val s = Station(m, "A")
        val c = m.controls().control("A:Review.maxEpochs")!!
        val before = s.review.maxEpochs

        // The corner is CLOSED EARLIER than it used to be, and that is the finding worth keeping.
        // Against the old DOUBLE control, NaN slipped past `limitToRange` -- every comparison with
        // NaN is false -- reached the setter, and surfaced wrapped in an InvocationTargetException
        // with the real message on the cause. An INTEGER control has to round before it can write,
        // and rounding NaN is refused outright, so the value never reaches the setter at all.
        val t = assertFailsWith<IllegalArgumentException> { c.value = Double.NaN }
        println()
        println("NaN through the control path: ${t::class.simpleName} — ${t.message}")

        assertTrue(t.message!!.contains("NaN"),
            "and it must still say what was wrong, and with what value: ${t.message}")
        assertEquals(before, s.review.maxEpochs,
            "the refused write changed nothing, which is §4.1.3.1's rule and does not stop " +
                "applying because the caller came through a control")
    }

    /** Controls are replication-initial here as everywhere else in KSL: not settable mid-run. */
    @Test
    fun aControlCannotBeMovedWhileTheModelIsRunning() {
        val m = Model("Running")
        val s = Station(m, "A")
        var caught: Throwable? = null
        s.review.let { }
        // Reach in from inside a decision, which is the one place user code runs mid-replication.
        val probe = Station(m, "B")
        probe.review.policy = PolicyIfc { _, _ ->
            if (caught == null) caught = runCatching { s.review.maxEpochs = 3 }.exceptionOrNull()
            doubleArrayOf(0.0)
        }
        m.numberOfReplications = 1
        m.lengthOfReplication = 25.0
        m.simulate()

        println()
        println("setting a control mid-run: ${caught?.let { it::class.simpleName }} — ${caught?.message}")
        assertTrue(caught is IllegalStateException,
            "decision parameters are replication-initial; changing one mid-run must be refused")
    }

    // ------------------------------------------------------------------ the negative results

    /**
     *  Why the narrowed lever bounds and the reward rates — the other two candidates the open issue
     *  named — are not controls, and could not be made ones by deciding differently.
     *
     *  Controls are extracted by reflecting over a model element's **class** member properties
     *  (`Controls.extractControls`, `cls.memberProperties`). The set of controls a class offers is
     *  therefore fixed by the class. Levers and reward terms are declared **per element**, so their
     *  number and names vary from one element to the next, and no annotation can produce a property
     *  per lever. Measured below: two elements, one with two levers and one with six, expose
     *  identical control keys.
     *
     *  This is a structural fact about KSL's control mechanism, not a preference, and it is what
     *  makes the answer to the open issue "these two and no others" rather than a matter of taste.
     */
    @Test
    fun perLeverBoundsAndRewardRatesCannotBeControlsAndThisIsWhy() {
        val m = Model("Shapes")
        val small = Station(m, "Small", levers = 2)
        val large = Station(m, "Large", levers = 6)

        val ks = keysOf(m, "Small:Review").map { it.substringAfter('.') }
        val kl = keysOf(m, "Large:Review").map { it.substringAfter('.') }
        println()
        println("2-lever element: $ks")
        println("6-lever element: $kl")

        assertEquals(2, small.review.descriptor().levers.size)
        assertEquals(6, large.review.descriptor().levers.size)
        assertEquals(ks, kl,
            "an element with six levers exposes the same controls as one with two, because " +
                "controls come from class properties. A per-lever bound cannot be a control, so " +
                "the open issue's first candidate is impossible rather than declined")
        assertTrue(ks.none { it.contains("ound") || it.contains("rate", ignoreCase = true) },
            "and nothing lever- or reward-shaped is in there pretending otherwise")
    }

    // ------------------------------------------------------------------ the actual seam

    /** A rule with its own parameters — the (s, S) shape, reparameterized as KSL reparameterizes. */
    class SsRule(parent: ModelElement, name: String) : ModelElement(parent, name), PolicyIfc {

        /**
         *  `S = s + sDelta` with `sDelta >= 0`, which is KSL's `S = r + SDelta` verbatim: every
         *  clamped combination satisfies `S >= s` by construction, so an optimizer sees a box and
         *  no cross-field check is needed at control-set time.
         */
        @set:KSLControl(controlType = ControlType.DOUBLE, lowerBound = 0.0, upperBound = 100.0)
        var reorderPoint: Double = 2.0

        @set:KSLControl(controlType = ControlType.DOUBLE, lowerBound = 0.0, upperBound = 100.0)
        var orderUpToDelta: Double = 5.0

        val asked = mutableListOf<Double>()
        override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
            val v = reorderPoint + orderUpToDelta
            asked += v
            return doubleArrayOf(v)
        }
    }

    class Shop(parent: ModelElement, name: String) : ModelElement(parent, name) {
        val level = TWResponse(this, name = "${this.name}:Level", initialValue = 2.0)
        var setting: Double = 0.0
        val rule = SsRule(this, "${this.name}:Rule")
        init {
            decisionElement("${this.name}:Review") {
                observe(level)
                lever(this@Shop, 0.0..200.0, neutral = Neutral.Value(0.0), alias = "L") { v -> setting = v }
                policy = rule
            }.reviewEvery(this, 10.0)
        }
    }

    /**
     *  **The answer to §8, and it needs nothing from this subsystem.**
     *
     *  What policy search actually searches is a *rule's* parameters, not the element's. A rule is
     *  ordinary user code, so a rule author who wants theirs searchable writes the rule as a
     *  `ModelElement` with annotated scalar properties — which is exactly what KSL's own inventory
     *  policies are. The model's control walk then finds them, and `simopt` drives them through the
     *  flat inputs map it already uses.
     *
     *  Asserting it here is what turns "policy search reuses simulation optimization without an
     *  adapter" from a claim into a demonstration, and it is why no adapter was built.
     */
    @Test
    fun aRuleThatIsAModelElementIsSearchableWithNoNewApi() {
        val m = Model("Seam")
        val shop = Shop(m, "S")
        val controls = m.controls()
        val keys = controls.controlKeys().filter { it.startsWith("S:Rule.") }.sorted()
        println()
        println("controls contributed by the RULE: $keys")
        assertEquals(listOf("S:Rule.orderUpToDelta", "S:Rule.reorderPoint"), keys,
            "a policy that is a ModelElement contributes its parameters to the model's controls " +
                "with no involvement from the decision element at all")

        // Drive them the way a study does: a flat map of key to value.
        val set = controls.setControlsFromMap(mapOf("S:Rule.reorderPoint" to 20.0, "S:Rule.orderUpToDelta" to 3.0))
        assertEquals(2, set)

        m.numberOfReplications = 1
        m.lengthOfReplication = 35.0
        m.simulate()

        println("the rule asked for ${shop.rule.asked}; the model holds ${shop.setting}")
        assertTrue(shop.rule.asked.isNotEmpty(), "the rule was consulted at all")
        assertTrue(shop.rule.asked.all { it == 23.0 },
            "the search point (s = 20, sDelta = 3) must reach the decisions as S = 23; if it did " +
                "not, the control path does not actually drive the rule and §8's claim is empty")
        assertEquals(23.0, shop.setting, 1e-9, "and the model ends holding what the rule asked for")

        // The reparameterization does its job: no clamped combination is infeasible.
        controls.setControlsFromMap(mapOf("S:Rule.reorderPoint" to 1e9, "S:Rule.orderUpToDelta" to -1e9))
        assertTrue(shop.rule.reorderPoint <= shop.rule.reorderPoint + shop.rule.orderUpToDelta,
            "S >= s must hold by construction for every clamped pair, which is the whole reason " +
                "the rule is parameterized by a gap rather than by S directly")
    }
}
