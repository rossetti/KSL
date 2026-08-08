package ksl.modeling.decision

import ksl.modeling.decision.descriptor.FeasibilityPolicy
import ksl.modeling.variable.TWResponse
import ksl.simulation.Model
import ksl.simulation.ModelElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  §4.2.6's promise, defended.
 *
 *  The design says every structural mistake surfaces at **element construction** and every bad
 *  parameter at **the setter**, and that both happen before any replication runs — so nothing can
 *  fail at epoch 200 of replication 17. Step 3 of §7.3.1 makes each of those checks an acceptance
 *  criterion.
 *
 *  Every one of them was written and none was asserted. §7.3.0 called this the largest untested
 *  surface in the tree, and the reason it is worth closing before the remaining features is that
 *  these checks are what the rest of §4 leans on: §4.4's action machinery assumes the declaration
 *  is coherent, and if a `require` silently stopped firing, everything downstream would keep
 *  passing while accepting declarations the design forbids.
 *
 *  Two of the checks §7.3.1 names turned out **not to exist**. They are asserted here as they
 *  actually behave, with the gap stated in the test rather than left for the next reader to find —
 *  the convention of `theLearningHooksAreDeclaredButNeverCalled` (G.8.2).
 */
class ConstructionValidationTest {

    private class Widget(parent: ModelElement, name: String) : ModelElement(parent, name) {
        val level = TWResponse(this, name = "$name:Level", initialValue = 5.0)
        var rate: Double = 1.0
        fun setLevel(v: Int) { level.value = v.toDouble() }
    }

    /** Declare an element and return whatever the declaration threw, or null. */
    private fun declaring(block: DecisionElementBuilder.(Widget) -> Unit): Throwable? {
        val model = Model("Declare")
        val w = Widget(model, "W")
        return runCatching { w.decisionElement("D") { block(w) } }.exceptionOrNull()
    }

    private fun Throwable?.named(): String = this?.let { it::class.simpleName } ?: "ACCEPTED"

    // -------------------------------------------------------------------- element construction

    /**
     *  §4.2.6 stage one, in full. Each row is a declaration the DSL will parse and `build()` must
     *  refuse, and each refusal has to say enough to act on — E.3 makes the message content part
     *  of the contract, not a matter of taste.
     */
    @Test
    fun everyStructuralRequireRefusesAndSaysWhy() {
        data class Case(
            val what: String,
            val mustSay: List<String>,
            val declare: DecisionElementBuilder.(Widget) -> Unit
        )

        val cases = listOf(
            Case("no observations at all", listOf("observation")) { w ->
                lever(w, 0..10, neutral = Neutral.Current { level.value }) { v -> setLevel(v.toInt()) }
                every(10.0); policy = NeutralPolicy
            },
            Case("no levers at all", listOf("lever")) { w ->
                observe(w.level)
                every(10.0); policy = NeutralPolicy
            },
            Case("no policy assigned", listOf("policy")) { w ->
                observe(w.level)
                lever(w, 0..10, neutral = Neutral.Current { level.value }) { v -> setLevel(v.toInt()) }
                every(10.0)
            },
            Case("INTEGER limits the wrong way round", listOf("unordered", "10", "0")) { w ->
                observe(w.level)
                lever(w, 10..0, neutral = Neutral.Current { level.value }) { v -> setLevel(v.toInt()) }
                every(10.0); policy = NeutralPolicy
            },
            Case("CONTINUOUS limits the wrong way round", listOf("unordered")) { w ->
                observe(w.level)
                lever(w, 10.0..0.0, neutral = Neutral.Current { rate }) { v -> rate = v }
                every(10.0); policy = NeutralPolicy
            },
            Case("the same lever declared twice", listOf("twice", "W")) { w ->
                observe(w.level)
                lever(w, 0..10, neutral = Neutral.Current { level.value }) { v -> setLevel(v.toInt()) }
                lever(w, 0..10, neutral = Neutral.Current { rate }) { v -> rate = v }
                every(10.0); policy = NeutralPolicy
            },
            Case("a budget naming a lever nobody declared", listOf("ghost", "real")) { w ->
                observe(w.level)
                lever(w, 0..10, alias = "real", neutral = Neutral.Current { level.value }) { }
                budget(LeverRef("D", "ghost"), total = 5.0)
                every(10.0); policy = NeutralPolicy
            }
        )

        println()
        println("§4.2.6 stage one — declarations build() must refuse:")
        val accepted = mutableListOf<String>()
        val mute = mutableListOf<String>()
        for (c in cases) {
            val e = declaring(c.declare)
            println("  %-42s %s".format(c.what, e.named()))
            if (e == null) { accepted += c.what; continue }
            println("      ${e.message}")
            val msg = e.message.orEmpty().lowercase()
            val absent = c.mustSay.filterNot { it.lowercase() in msg }
            if (absent.isNotEmpty()) mute += "${c.what} (message omits $absent)"
            assertTrue(e is IllegalArgumentException,
                "${c.what}: refused with ${e.named()}; §4.2.6 and E.3 say construction failures " +
                    "are IllegalArgumentException")
        }

        assertTrue(accepted.isEmpty(),
            "these declarations were accepted and §4.2.6 says they must not be: $accepted")
        assertTrue(mute.isEmpty(),
            "E.3 makes the message content part of the contract — a refusal that does not name " +
                "the offender cannot be acted on: $mute")
    }

    /**
     *  The two levers of a budget must be *declared*, and the check reads the whole constraint
     *  rather than stopping at the first name — a message naming one of two unknown participants
     *  sends the reader back for a second round.
     */
    @Test
    fun aConstraintNamingAnUndeclaredLeverListsWhatWasAvailable() {
        val e = declaring { w ->
            observe(w.level)
            val a = lever(w, 0..10, alias = "a", neutral = Neutral.Current { level.value }) { }
            atMost(a, LeverRef("D", "nope"), total = 5.0)
            every(10.0); policy = NeutralPolicy
        }
        println()
        println("atMost(a, nope): ${e.named()}")
        println("  ${e?.message}")
        assertTrue(e is IllegalArgumentException)
        val m = e.message.orEmpty()
        assertTrue("nope" in m, "the message should name the unresolved participant: $m")
        assertTrue("a" in m, "the message should list what was declared: $m")
    }

    // -------------------------------------------------------------------- parameterization

    private fun built(
        declare: DecisionElementBuilder.(Widget) -> Unit = { w ->
            observe(w.level)
            lever(w, 0..10, neutral = Neutral.Current { level.value }) { v -> setLevel(v.toInt()) }
            every(10.0); policy = NeutralPolicy
        }
    ): Triple<Model, Widget, DecisionElement> {
        val model = Model("Params")
        val w = Widget(model, "W")
        val e = w.decisionElement("D") { declare(w) }
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        return Triple(model, w, e)
    }

    /**
     *  §4.3.3: narrowing may only shrink, and a narrowing outside the model's limits is **rejected,
     *  never clamped**. §4.1.6.1 turns that into a decision about `@KSLControl` — the controls carry
     *  no annotated bounds precisely so this rule wins over `limitToRange` — so the rule needs a
     *  test before anything is built on it.
     */
    @Test
    fun narrowingOutsideTheModelsLimitsIsRejectedAndNotClamped() {
        val (_, _, e) = built()
        val ref = e.leverRef("W")

        val tooWide = runCatching { e.narrow(ref, 0..12) }.exceptionOrNull()
        val tooLow = runCatching { e.narrow(ref, -1.0..5.0) }.exceptionOrNull()

        println()
        println("model limits are 0..10:")
        println("  narrow to 0..12   : ${tooWide.named()} — ${tooWide?.message}")
        println("  narrow to -1.0..5 : ${tooLow.named()}")
        println("  limits after both : ${e.limitsOf(ref)}")

        assertTrue(tooWide is NarrowingException, "widening should throw NarrowingException")
        assertTrue(tooLow is NarrowingException, "narrowing below the model's floor should throw")
        assertTrue("0.0, 10.0" in tooWide.message.orEmpty() || "10" in tooWide.message.orEmpty(),
            "the message must name the model's limits: ${tooWide.message}")
        assertEquals(0..10, e.limitsOf(ref),
            "a rejected narrowing must leave the limits untouched — clamping is what §4.3.3 forbids")

        // A legitimate narrowing still works, so the guard is not simply refusing everything.
        e.narrow(ref, 2..7)
        assertEquals(2..7, e.limitsOf(ref))
    }

    /**
     *  §4.1.3: every parameter is replication-initial, so every setter refuses while the model is
     *  running. The guarantee is what lets §4.1.3 say every replication of one experiment used the
     *  values in force when it began.
     *
     *  Each setter is exercised **from inside a running replication**, which is the only place the
     *  guard can actually be observed.
     */
    @Test
    fun everyParameterSetterRefusesWhileTheModelIsRunning() {
        val model = Model("WhileRunning")
        val w = Widget(model, "W")
        val attempts = LinkedHashMap<String, String>()
        lateinit var element: DecisionElement

        element = w.decisionElement("D") {
            observe(w.level)
            lever(w, 0..10, neutral = Neutral.Current { level.value }) { v -> setLevel(v.toInt()) }
            every(10.0)
            policy = PolicyIfc { _, ctx ->
                if (attempts.isEmpty()) {
                    fun attempt(name: String, block: () -> Unit) {
                        attempts[name] = runCatching(block).exceptionOrNull()
                            ?.let { it::class.simpleName } ?: "ACCEPTED"
                    }
                    attempt("policy") { element.policy = NeutralPolicy }
                    attempt("epochInterval") { element.epochInterval = 20.0 }
                    attempt("feasibilityPolicy") {
                        element.feasibilityPolicy = FeasibilityPolicy.CLAMP_THEN_REJECT
                    }
                    attempt("maxEpochs") { element.maxEpochs = 3 }
                    attempt("policyLabel") { element.policyLabel = "changed" }
                    attempt("narrow") { element.narrow(element.leverRef("W"), 1..5) }
                }
                ctx.currentAction
            }
        }
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        model.simulate()

        println()
        println("Setting a parameter from inside a running replication:")
        attempts.forEach { (k, v) -> println("  %-18s %s".format(k, v)) }

        assertTrue(attempts.size == 6, "not every setter was attempted; got ${attempts.keys}")
        val accepted = attempts.filterValues { it == "ACCEPTED" }
        assertTrue(accepted.isEmpty(),
            "these setters ran during a replication and §4.1.3 says none may: ${accepted.keys}")
        val wrongType = attempts.filterValues { it != "IllegalStateException" }
        assertTrue(wrongType.isEmpty(),
            "E.3 says a setter called while running throws IllegalStateException: $wrongType")

        // And the parameter really is unchanged afterwards, not merely refused loudly.
        assertEquals(0..10, element.limitsOf(element.leverRef("W")))
    }

    /** `epochInterval` is a positive duration, and zero is the mistake worth catching. */
    @Test
    fun aNonPositiveEpochIntervalIsRefused() {
        val (_, _, e) = built()
        val zero = runCatching { e.epochInterval = 0.0 }.exceptionOrNull()
        val negative = runCatching { e.epochInterval = -5.0 }.exceptionOrNull()
        println()
        println("epochInterval = 0.0  : ${zero.named()} — ${zero?.message}")
        println("epochInterval = -5.0 : ${negative.named()}")
        assertTrue(zero is IllegalArgumentException)
        assertTrue(negative is IllegalArgumentException)
        e.epochInterval = 25.0
        assertEquals(25.0, e.epochInterval)
    }

    /**
     *  §4.1.2.2's resolution rules. `leverFor` keys on the element a lever *writes*, which is a
     *  lookup key and not an identity — so it must refuse both ways it can be wrong.
     */
    @Test
    fun resolvingALeverRefusesWhenTheOwnerBacksNoneOrSeveral() {
        val model = Model("Resolve")
        val backer = Widget(model, "Backer")
        val bystander = Widget(model, "Bystander")
        val e = backer.decisionElement("D") {
            observe(backer.level)
            lever(backer, 0..10, alias = "first", neutral = Neutral.Current { level.value }) { }
            lever(backer, 0..10, alias = "second", neutral = Neutral.Current { rate }) { }
            every(10.0); policy = PolicyIfc { _, _ -> doubleArrayOf(0.0, 0.0) }
        }

        val none = runCatching { e.leverFor(bystander) }.exceptionOrNull()
        val several = runCatching { e.leverFor(backer) }.exceptionOrNull()
        val unknownName = runCatching { e.leverRef("nonesuch") }.exceptionOrNull()

        println()
        println("leverFor(an element backing no lever) : ${none.named()} — ${none?.message}")
        println("leverFor(an element backing two)      : ${several.named()} — ${several?.message}")
        println("leverRef(\"nonesuch\")                  : ${unknownName.named()}")

        assertTrue(none is BindingException, "expected BindingException, got ${none.named()}")
        assertTrue(none.available == listOf("first", "second"),
            "E.3 requires the list of available names: ${none.available}")
        assertTrue(several is AmbiguousLeverException, "expected AmbiguousLeverException")
        assertTrue(several.candidates == listOf("first", "second"),
            "the refusal must name both candidates: ${several.candidates}")
        assertTrue("leverRef" in several.message.orEmpty(),
            "E.3 requires the message to say how to disambiguate: ${several.message}")
        assertTrue(unknownName is BindingException)

        // Resolution by alias is the way out, and it works.
        assertEquals("second", e.leverRef("second").declaredName)
    }

    // -------------------------------------------------------------------- the two gaps

    /**
     *  **The last of step 3's criteria, and the only one that needed building.** It lists
     *  "non-integral limits on an `INTEGER` domain". The declaration path cannot produce them —
     *  `lever(owner, IntRange)` takes integers — but `narrow` could, because it accepts a
     *  `ClosedFloatingPointRange` and only checked it against the model's limits.
     *
     *  The result was not harmless. `limitsOf` truncates toward zero, so it reported a floor of 1
     *  while `prepare` computed feasibility against 1.5 and rejected an action of 1. **Two
     *  accessors of the same lever disagreed about what was allowed** — the disagreement §4.4.6.2
     *  goes out of its way to prevent between `contains` and `prepare`, arriving by another door.
     *
     *  Rejected rather than rounded inward, for §4.3.3's reason: a narrowing that quietly becomes a
     *  different narrowing is what this call refuses to do.
     */
    @Test
    fun narrowingAnIntegerLeverToNonIntegralBoundsIsRefused() {
        val (model, _, e) = built()
        val ref = e.leverRef("W")

        val refused = runCatching { e.narrow(ref, 1.5..7.5) }.exceptionOrNull()
        val halfWrong = runCatching { e.narrow(ref, 2.0..7.5) }.exceptionOrNull()

        println()
        println("narrow(INTEGER lever, 1.5..7.5): ${refused.named()}")
        println("  ${refused?.message}")
        println("narrow(INTEGER lever, 2.0..7.5): ${halfWrong.named()}   (one bound is enough)")
        println("  limits after both            : ${e.limitsOf(ref)}")

        assertTrue(refused is NarrowingException,
            "non-integral bounds on an INTEGER lever should be refused; got ${refused.named()}")
        assertTrue(halfWrong is NarrowingException,
            "one non-integral bound is enough to make limitsOf disagree with prepare")
        assertEquals(0..10, e.limitsOf(ref), "a rejected narrowing leaves the limits untouched")

        // The equivalent integral narrowing is accepted, and then the two accessors agree —
        // which is the property the refusal exists to protect.
        e.narrow(ref, 2.0..7.0)
        var lowestFeasible = -1.0
        e.policy = PolicyIfc { _, ctx ->
            if (lowestFeasible < 0) lowestFeasible = ctx.actions.bounds(0).start
            doubleArrayOf(2.0)
        }
        model.simulate()
        println("  after narrow(2.0..7.0): limitsOf ${e.limitsOf(ref)}, feasible floor $lowestFeasible")
        assertEquals(2..7, e.limitsOf(ref))
        assertEquals(2.0, lowestFeasible, 1e-9,
            "limitsOf and the feasible set must report the same floor")
    }

    /**
     *  §7.3.1 step 8: **a `LeverRef` from one element cannot be used to narrow another.** Nothing
     *  enforced it. A ref was a bare declared name, so when two elements declared the same alias —
     *  which is what two instances of one subsystem produce, and §4.1.9 is explicitly about that
     *  case — one element's reference silently narrowed the other's lever. The call did something
     *  other than what it appeared to say, and nothing anywhere reported it.
     *
     *  A ref now names its element too. The check runs at both moments a ref is consumed: at
     *  parameterization, where it would have narrowed the wrong lever, and at declaration, where a
     *  constraint would otherwise have been built over the right name for the wrong reason.
     */
    @Test
    fun aLeverRefFromOneElementCannotBeUsedOnAnother() {
        val model = Model("CrossElement")
        val w1 = Widget(model, "W1")
        val w2 = Widget(model, "W2")
        fun declare(w: Widget, tag: String) = w.decisionElement("D-$tag") {
            observe(w.level)
            // The same alias in both elements — what two instances of one subsystem produce.
            lever(w, 0..10, alias = "staff", neutral = Neutral.Current { level.value }) { }
            every(10.0); policy = PolicyIfc { _, _ -> doubleArrayOf(0.0) }
        }
        val first = declare(w1, "first")
        val second = declare(w2, "second")

        val refFromFirst = first.leverRef("staff")
        val crossed = runCatching { second.narrow(refFromFirst, 3..4) }.exceptionOrNull()
        val alsoCrossed = runCatching { second.limitsOf(refFromFirst) }.exceptionOrNull()

        println()
        println("Two elements, each with a lever aliased \"staff\":")
        println("  the ref reads                    : $refFromFirst")
        println("  second.narrow(first's ref, 3..4) : ${crossed.named()}")
        println("      ${crossed?.message}")
        println("  second.limitsOf(first's ref)     : ${alsoCrossed.named()}")
        println("  first's limits                   : ${first.limitsOf(first.leverRef("staff"))}")
        println("  second's limits                  : ${second.limitsOf(second.leverRef("staff"))}")

        assertTrue(crossed is BindingException,
            "a foreign LeverRef should be refused; got ${crossed.named()}")
        val m = crossed.message.orEmpty()
        assertTrue("D-first" in m && "D-second" in m,
            "the refusal must name both elements — with a shared alias, naming only the lever " +
                "reads as a contradiction: $m")
        assertTrue("leverRef" in m, "and it must say how to get the right one: $m")
        assertTrue(alsoCrossed is BindingException, "every consumer of a ref checks it, not just narrow")

        assertEquals(0..10, second.limitsOf(second.leverRef("staff")),
            "the wrong lever must be untouched")
        assertEquals(0..10, first.limitsOf(first.leverRef("staff")))

        // Each element's own ref still works, so the check distinguishes rather than forbids.
        second.narrow(second.leverRef("staff"), 3..4)
        assertEquals(3..4, second.limitsOf(second.leverRef("staff")))
        assertEquals(0..10, first.limitsOf(first.leverRef("staff")))
    }

    /**
     *  The same ref, consumed at the other moment. A constraint joins levers of the element that
     *  declares it; a ref from a sibling would have passed `build()`'s name check whenever the two
     *  elements shared an alias, and produced a constraint over this element's lever that the
     *  modeler believed was over the other's.
     */
    @Test
    fun aConstraintCannotJoinALeverOfAnotherElement() {
        val model = Model("CrossConstraint")
        val w1 = Widget(model, "W1")
        val w2 = Widget(model, "W2")
        val first = w1.decisionElement("D-first") {
            observe(w1.level)
            lever(w1, 0..10, alias = "staff", neutral = Neutral.Current { level.value }) { }
            every(10.0); policy = PolicyIfc { _, _ -> doubleArrayOf(0.0) }
        }
        val foreign = first.leverRef("staff")

        val e = runCatching {
            w2.decisionElement("D-second") {
                observe(w2.level)
                val own = lever(w2, 0..10, alias = "staff",
                    neutral = Neutral.Current { level.value }) { }
                budget(own, foreign, total = 8.0)
                every(10.0); policy = PolicyIfc { _, _ -> doubleArrayOf(0.0) }
            }
        }.exceptionOrNull()

        println()
        println("budget(own \"staff\", another element's \"staff\"): ${e.named()}")
        println("  ${e?.message}")
        assertTrue(e is IllegalArgumentException,
            "a constraint over a sibling's lever should be refused at construction")
        assertTrue("D-first" in e.message.orEmpty() && "D-second" in e.message.orEmpty(),
            "the refusal must name both elements: ${e.message}")
    }

    /**
     *  E.3 lists `RewardKindException`, and nothing throws it. That is correct today — reward
     *  declaration is refused wholesale by `NotDeclarableYetException` (G.9 row 11), so the kind
     *  mismatch it guards cannot yet be reached. Recorded so the inventory's claim and the tree's
     *  behaviour are not silently different, and so step 5b has a test to invert.
     *
     *  The milestone the refusal names is part of the contract (E.3), and it moved: §7.1.1 brought
     *  reward accrual into M1, so a modeler who declares a reward today is told "M1 step 5b" rather
     *  than "M2". A message that points at the wrong milestone is worse than one that points at
     *  none, because it is actionable and wrong.
     */
    @Test
    fun theRewardKindCheckIsDeclaredAndUnreachableUntilRewardsExist() {
        val e = declaring { w ->
            observe(w.level)
            lever(w, 0..10, neutral = Neutral.Current { level.value }) { }
            reward(w.level, rate = 1.0)
            every(10.0); policy = NeutralPolicy
        }
        println()
        println("declaring a reward: ${e.named()} — ${e?.message}")
        assertTrue(e is NotDeclarableYetException,
            "rewards are refused at the declaration until M2; RewardKindException guards a path " +
                "that cannot be reached before then")
        assertEquals("M1 step 5b", e.milestone)
    }
}
