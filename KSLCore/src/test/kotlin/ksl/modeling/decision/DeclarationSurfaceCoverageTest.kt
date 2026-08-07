package ksl.modeling.decision

import ksl.modeling.decision.descriptor.FeasibilityPolicy
import ksl.modeling.decision.descriptor.WarmUpOrdering
import ksl.modeling.variable.TWResponse
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 *  D.24's query, made executable: for every form the DSL **accepts**, does the library
 *  carry it through to a working run?
 *
 *  The criterion is coverage of the declaration surface, not callers. Each probe below
 *  declares something legal and reports what happens. A probe that throws is not a broken
 *  test — it is a cell of §4.4.6.5's matrix that the declaration surface opens and the
 *  library does not fill, and the report is the point.
 */
class DeclarationSurfaceCoverageTest {

    /** A model element offering one of each settable shape a lever might target. */
    private class Widget(parent: ModelElement, name: String) : ModelElement(parent, name) {
        val level = TWResponse(this, name = "$name:Level", initialValue = 5.0)
        var rate: Double = 1.0
        var mode: Int = 0
        private val ticks = TWResponse(this, name = "$name:Ticks")
        fun setLevel(v: Int) { level.value = v.toDouble() }
        fun bump() { ticks.increment(1.0) }
    }

    private fun probe(what: String, block: () -> Unit): String {
        val outcome = runCatching(block).exceptionOrNull()
        val verdict = when {
            outcome == null -> "WORKS"
            outcome is NotDeclarableYetException -> "REFUSED AT DECLARATION (${outcome.milestone})"
            // `build()` validation. Not a gap in the library — a form the DSL can spell and
            // the design has decided against, refused where §4.2.6 says refusals belong.
            outcome is IllegalArgumentException -> "REFUSED AT CONSTRUCTION"
            // The declaration is legal; this particular action was not. Also a decision,
            // but one that can only be made once there is an action to look at (§4.4.4).
            outcome is ActionValidationException -> "REFUSED AT RUN TIME"
            // A declaration that is fine and a USE of it that is not — reading a context
            // after its epoch (§4.5.3). Refused where the misuse happens, which is neither
            // the declaration nor the action.
            outcome is StaleDecisionContextException -> "REFUSED AT USE"
            else -> "FAILS: ${outcome::class.simpleName}"
        }
        println("  %-46s %s".format(what, verdict))
        return verdict
    }

    private fun runWith(
        priority: Int = KSLEvent.MEDIUM_LOW_PRIORITY,
        declare: DecisionElementBuilder.(Widget) -> Unit
    ): Model {
        val model = Model("Coverage")
        val w = Widget(model, "W")
        w.decisionElement("D") {
            epochPriority = priority
            declare(w)
        }
        model.numberOfReplications = 2
        model.lengthOfReplication = 100.0
        model.lengthOfReplicationWarmUp = 20.0
        model.simulate()
        return model
    }

    @Test
    fun everyDeclarableFormIsProbed() {
        val results = LinkedHashMap<String, String>()
        println()
        println("=== D.24 coverage probe: what the DSL accepts vs what the library carries ===")

        results["INTEGER lever"] = probe("lever(limits = 0..10)") {
            runWith { w -> observe(w.level); lever(w, 0..10, neutral = Neutral.Current { level.value }) { v -> setLevel(v.toInt()) }
                every(10.0); policy = NeutralPolicy }
        }
        results["CONTINUOUS lever"] = probe("lever(limits = 0.0..10.0)") {
            runWith { w -> observe(w.level); lever(w, 0.0..10.0, neutral = Neutral.Current { rate }) { v -> rate = v }
                every(10.0); policy = NeutralPolicy }
        }
        results["CATEGORICAL lever"] = probe("lever(levels = listOf(...))") {
            runWith { w -> observe(w.level)
                lever(w, listOf("off", "slow", "fast"), neutral = Neutral.Current { mode.toDouble() }) { v -> mode = v.toInt() }
                every(10.0); policy = NeutralPolicy }
        }
        results["CATEGORICAL + CLAMP_THEN_REJECT"] = probe("clamping a categorical lever") {
            runWith { w -> observe(w.level)
                feasibility = FeasibilityPolicy.CLAMP_THEN_REJECT
                lever(w, listOf("off", "slow", "fast"), neutral = Neutral.Current { mode.toDouble() }) { v -> mode = v.toInt() }
                every(10.0)
                policy = PolicyIfc { _, _ -> doubleArrayOf(9.0) }   // far outside; must clamp
            }
        }
        results["batchLever"] = probe("batchLever(...) for atomic multi-writes (§4.4.5)") {
            runWith { w -> observe(w.level)
                val a = lever(w, 0..10, alias = "a", neutral = Neutral.Current { level.value }) { v -> setLevel(v.toInt()) }
                val b = lever(w, 0..10, alias = "b", neutral = Neutral.Current { rate }) { v -> rate = v }
                batchLever(a, b) { }
                every(10.0); policy = NeutralPolicy }
        }
        results["reward + estimand"] = probe("reward(...) then read the estimand (§4.2.5)") {
            val m = runWith { w -> observe(w.level)
                lever(w, 0..10, neutral = Neutral.Current { level.value }) { v -> setLevel(v.toInt()) }
                reward(w.level, rate = 1.0)
                every(10.0); policy = NeutralPolicy }
            (m.getModelElement("D") as DecisionElement).estimand
        }
        results["captureTo"] = probe("captureTo(...) trajectory sink (§4.8)") {
            runWith { w -> observe(w.level)
                lever(w, 0..10, neutral = Neutral.Current { level.value }) { v -> setLevel(v.toInt()) }
                captureTo { ksl.sdm.capture.NullSink }
                every(10.0); policy = NeutralPolicy }
        }
        results["CALENDAR timing"] = probe("onCalendar(listOf(...))") {
            runWith { w -> observe(w.level); lever(w, 0..10, neutral = Neutral.Current { level.value }) { v -> setLevel(v.toInt()) }
                onCalendar(listOf(15.0, 35.0, 60.0)); policy = NeutralPolicy }
        }
        results["terminalWhen"] = probe("terminalWhen { ... } episode ending (§4.6.3)") {
            runWith { w -> observe(w.level); lever(w, 0..10, neutral = Neutral.Current { level.value }) { v -> setLevel(v.toInt()) }
                every(10.0); terminalWhen { false }; policy = NeutralPolicy }
        }
        results["two constraints over one lever"] = probe("budget(a,b) and atMost(b,c) sharing b") {
            runWith { w -> observe(w.level)
                val a = lever(w, 0..10, alias = "a", neutral = Neutral.Current { level.value }) { }
                val b = lever(w, 0..10, alias = "b", neutral = Neutral.Current { rate }) { }
                val c = lever(w, 0..10, alias = "c", neutral = Neutral.Current { mode.toDouble() }) { }
                budget(a, b, total = 5.0); atMost(b, c, total = 5.0)
                every(10.0); policy = PolicyIfc { _, _ -> doubleArrayOf(5.0, 0.0, 0.0) } }
        }
        results["TRANSACTION lever"] = probe("lever(neutral = Neutral.Value(0.0)) (§8.2.3)") {
            runWith { w -> observe(w.level)
                lever(w, 0..10, neutral = Neutral.Value(0.0)) { v -> repeat(v.toInt()) { bump() } }
                every(10.0); policy = NeutralPolicy }
        }
        results["CATEGORICAL transaction"] = probe("a categorical lever with a neutral AMOUNT") {
            runWith { w -> observe(w.level)
                lever(w, listOf("off", "slow", "fast"), neutral = Neutral.Value(0.0)) { v -> mode = v.toInt() }
                every(10.0); policy = NeutralPolicy }
        }
        results["context read after the epoch"] = probe("retaining the DecisionContext (§4.5.3)") {
            var stashed: DecisionContext? = null
            runWith { w -> observe(w.level)
                lever(w, 0..10, neutral = Neutral.Current { level.value }) { v -> setLevel(v.toInt()) }
                every(10.0)
                policy = PolicyIfc { _, ctx -> stashed = ctx; ctx.currentAction } }
            stashed!!.simulationTime
        }
        results["two decision elements"] = probe("two elements in one model (§4.1.9)") {
            val model = Model("TwoElements")
            val w1 = Widget(model, "W1"); val w2 = Widget(model, "W2")
            w1.decisionElement("D1") { observe(w1.level)
                lever(w1, 0..10, neutral = Neutral.Current { level.value }) { v -> setLevel(v.toInt()) }
                every(10.0); policy = NeutralPolicy }
            w2.decisionElement("D2") { observe(w2.level)
                lever(w2, 0..10, neutral = Neutral.Current { level.value }) { v -> setLevel(v.toInt()) }
                every(10.0); policy = NeutralPolicy }
            model.numberOfReplications = 2; model.lengthOfReplication = 100.0
            model.simulate()
        }
        results["epoch priority after warm-up, undeclared"] =
            probe("epochPriority past warm-up without saying so") {
                runWith(priority = KSLEvent.DEFAULT_WARMUP_EVENT_PRIORITY + 1) { w -> observe(w.level)
                    lever(w, 0..10, neutral = Neutral.Current { level.value }) { v -> setLevel(v.toInt()) }
                    every(10.0); policy = NeutralPolicy }
            }
        results["epoch priority after warm-up, declared"] =
            probe("the same, with warmUpOrdering = WARM_UP_FIRST") {
                runWith(priority = KSLEvent.DEFAULT_WARMUP_EVENT_PRIORITY + 1) { w -> observe(w.level)
                    warmUpOrdering = WarmUpOrdering.WARM_UP_FIRST
                    lever(w, 0..10, neutral = Neutral.Current { level.value }) { v -> setLevel(v.toInt()) }
                    every(10.0); policy = NeutralPolicy }
            }

        println()
        val deferred = results.filterValues { it.startsWith("REFUSED AT DECLARATION") }
        val refused = results.filterValues { it == "REFUSED AT CONSTRUCTION" }
        val atRun = results.filterValues { it == "REFUSED AT RUN TIME" || it == "REFUSED AT USE" }
        val fails = results.filterValues { it.startsWith("FAILS") }
        println("  ${results.size} declarable forms probed: " +
            "${results.count { it.value == "WORKS" }} work, " +
            "${deferred.size} refused at declaration as unbuilt, " +
            "${refused.size} refused at construction by design, " +
            "${atRun.size} refused at run time by design, ${fails.size} fail")
        if (deferred.isNotEmpty()) println("  not built: ${deferred.keys}")
        if (refused.isNotEmpty()) println("  at build : ${refused.keys}")
        if (atRun.isNotEmpty()) println("  at run   : ${atRun.keys}")
        if (fails.isNotEmpty()) println("  failing  : ${fails.keys}")

        // Every cell of the matrix now has a stated outcome: it works, or it is refused
        // somewhere, with a reason. "Ran, semantics unspecified" is the state this probe
        // was written to find, and there are none left.
        assertTrue(fails.isEmpty(),
            "a declarable form neither works nor is refused with a reason: ${fails.keys}")

        // G.9 row 3: the shared lever is refused where the refusal can name both constraints.
        assertTrue(
            results["two constraints over one lever"] == "REFUSED AT CONSTRUCTION",
            "a lever in two joint constraints should be refused at build(); " +
                "saw ${results["two constraints over one lever"]}"
        )
    }

    // ---------------------------------------------------------------------------
    // "WORKS" above means only "did not throw". These probe whether the SEMANTICS are
    // specified, which is the question the coverage criterion actually asks.
    // ---------------------------------------------------------------------------

    /**
     *  Clamping is defined as `coerceIn(bounds)` — numeric proximity. For a CATEGORICAL
     *  lever the levels have no order, so clamping 9 to "fast" is not repair, it is the
     *  library silently choosing a category the rule never asked for.
     */
    @Test
    fun clampingACategoricalLeverIsRefusedRatherThanGuessed() {
        val model = Model("CategoricalClamp")
        val w = Widget(model, "W")
        w.decisionElement("D") {
            observe(w.level)
            feasibility = FeasibilityPolicy.CLAMP_THEN_REJECT
            lever(w, listOf("off", "slow", "fast"), neutral = Neutral.Current { mode.toDouble() }) { v -> mode = v.toInt() }
            every(10.0)
            policy = PolicyIfc { _, _ -> doubleArrayOf(9.0) }     // not a level at all
        }
        model.numberOfReplications = 1
        model.lengthOfReplication = 100.0
        val failure = runCatching { model.simulate() }.exceptionOrNull()

        println()
        println("CATEGORICAL clamp: policy asked for level 9 of {off, slow, fast}")
        println("  outcome  : ${failure?.let { it::class.simpleName } ?: "ran, lever ended at ${w.mode}"}")
        println("  mode     : ${w.mode}   (0 = off; untouched means nothing was guessed)")
        (failure as? ActionValidationException)?.violations?.forEach { println("  violation: $it") }

        assertTrue(failure != null,
            "clamping an unordered domain should reject; it ran and left mode = ${w.mode}")
        assertTrue(w.mode == 0, "no category should have been written; mode = ${w.mode}")
    }

    /**
     *  G.9 row 1. A lever declares what doing nothing means, and the two answers are not
     *  interchangeable (§8.2.3).
     *
     *  The probe above only shows that both spellings run. What matters is that they behave
     *  differently in the one respect the machinery cares about: a setting elides a write that
     *  changes nothing, a transaction never does. Here the same request is issued twice at
     *  every epoch, and the count of writes that actually reached the model is the evidence.
     */
    @Test
    fun aSettingElidesARedundantWriteAndATransactionDoesNot() {
        fun writesUnder(transaction: Boolean): Int {
            val model = Model("Kind-$transaction")
            var writes = 0
            val w = Widget(model, "W")
            w.decisionElement("D") {
                observe(w.level)
                if (transaction) {
                    lever(w, 0..10, neutral = Neutral.Value(0.0)) { _ -> writes++ }
                } else {
                    lever(w, 0..10, neutral = Neutral.Current { rate }) { v -> writes++; rate = v }
                }
                every(10.0)
                // The same value every epoch. For a setting the first write lands and the
                // rest are redundant; for a transaction every one of them is an act.
                policy = PolicyIfc { _, _ -> doubleArrayOf(3.0) }
            }
            model.numberOfReplications = 1
            model.lengthOfReplication = 100.0
            model.simulate()
            return writes
        }

        val setting = writesUnder(transaction = false)
        val transaction = writesUnder(transaction = true)

        println()
        println("The same request, 3.0, issued at each of 10 epochs:")
        println("  declared a SETTING     : $setting write(s) reached the model")
        println("  declared a TRANSACTION : $transaction write(s) reached the model")

        assertTrue(setting == 1,
            "a setting should write once and elide the redundant repeats (§6.2); saw $setting")
        assertTrue(transaction == 10,
            "a transaction must never be elided — each repeat is a separate act (§8.2.2); " +
                "saw $transaction")
    }

    /**
     *  G.9 row 6. §4.5.3 said retaining a context is a bug and nothing prevented it. The
     *  element reuses one instance, so a retained context answers about whatever epoch has
     *  happened since — a well-formed number belonging to a different decision, with nothing
     *  to notice.
     *
     *  The split is the design decision worth testing, not just the throw: the **declared
     *  shape** is constant for the life of the element, so keeping it is legitimate and stays
     *  legal. Only what means something different at the next epoch is refused.
     */
    @Test
    fun aRetainedContextRefusesEpochScopedReadsAndStillAnswersAboutTheDeclaredShape() {
        val model = Model("StaleContext")
        val w = Widget(model, "W")
        var stashed: DecisionContext? = null
        var stashedActions: ActionSet? = null
        w.decisionElement("D") {
            observe(w.level)
            lever(w, 0..10, neutral = Neutral.Current { level.value }) { v -> setLevel(v.toInt()) }
            every(10.0)
            policy = PolicyIfc { _, ctx ->
                if (stashed == null) { stashed = ctx; stashedActions = ctx.actions }
                ctx.currentAction
            }
        }
        model.numberOfReplications = 1
        model.lengthOfReplication = 100.0
        model.simulate()

        val ctx = stashed!!
        val refusals = listOf<Pair<String, () -> Any?>>(
            "simulationTime" to { ctx.simulationTime },
            "epochIndex" to { ctx.epochIndex },
            "remainingRunLength" to { ctx.remainingRunLength },
            "replicationId" to { ctx.replicationId },
            "budgetTotal" to { ctx.budgetTotal(0) },
            "actions" to { ctx.actions },
            "currentAction" to { ctx.currentAction },
            "neutralAction" to { ctx.neutralAction },
            "a retained ActionSet" to { stashedActions!!.bounds(0) }
        )

        println()
        println("Reading a context that was stashed during epoch 1, after the run:")
        var refused = 0
        for ((name, read) in refusals) {
            val e = runCatching { read() }.exceptionOrNull()
            if (e is StaleDecisionContextException) refused++
            println("  %-22s %s".format(name, e?.let { it::class.simpleName } ?: "ANSWERED"))
        }
        println("  message: ${runCatching { ctx.simulationTime }.exceptionOrNull()?.message}")

        assertTrue(refused == refusals.size,
            "$refused of ${refusals.size} epoch-scoped members refused; all of them should")

        // The declared shape is not epoch-scoped and must stay readable — a rule that keeps
        // the lever names is doing nothing wrong.
        println("  leverNames             ${ctx.leverNames}   (declared shape: still legal)")
        assertTrue(ctx.leverNames == listOf("W") && ctx.elementName == "D" && ctx.constraints.isEmpty(),
            "the declared shape is constant and should still be readable from a retained context")
    }

    /**
     *  The half of G.9 row 6 that a simpler guard would have missed, and the reason the
     *  counter is stamped on the view rather than kept on the element.
     *
     *  An "is an epoch open?" flag catches a context read after the run. It does **not** catch
     *  a context stashed at epoch 1 and read during epoch 5 — an epoch is open, so the flag is
     *  satisfied, and the read returns epoch 5's numbers to a rule that believes it is looking
     *  at epoch 1's. That is the silent wrong answer the row is about, in its most plausible
     *  form: a rule comparing "then" against "now".
     */
    @Test
    fun aContextFromAnEarlierEpochIsRefusedEvenWhileALaterEpochIsOpen() {
        val model = Model("StaleAcrossEpochs")
        val w = Widget(model, "W")
        var first: DecisionContext? = null
        var readInsideALaterEpoch: Throwable? = null
        var timeItWouldHaveReported: Double? = null

        w.decisionElement("D") {
            observe(w.level)
            lever(w, 0..10, neutral = Neutral.Current { level.value }) { v -> setLevel(v.toInt()) }
            every(10.0)
            policy = PolicyIfc { _, ctx ->
                if (first == null) {
                    first = ctx
                } else if (readInsideALaterEpoch == null) {
                    // An epoch IS open here — just not the one `first` was minted for.
                    timeItWouldHaveReported = ctx.simulationTime
                    readInsideALaterEpoch = runCatching { first!!.simulationTime }.exceptionOrNull()
                }
                ctx.currentAction
            }
        }
        model.numberOfReplications = 1
        model.lengthOfReplication = 100.0
        model.simulate()

        println()
        println("Epoch 1's context, read during epoch 2 while epoch 2 is open:")
        println("  outcome                ${readInsideALaterEpoch?.let { it::class.simpleName } ?: "ANSWERED"}")
        println("  what it would have said: ${timeItWouldHaveReported} — epoch 2's time, for a")
        println("                           rule that asked epoch 1's context")
        println("  message: ${readInsideALaterEpoch?.message}")

        assertTrue(readInsideALaterEpoch is StaleDecisionContextException,
            "a context from an earlier epoch must be refused even while a later epoch is open; " +
                "a liveness flag alone would have answered ${timeItWouldHaveReported}")
    }

    /**
     *  G.9 row 3. Two joint constraints over one lever used to run: `budgetTotal` returned
     *  whichever was declared first, so a rule allocating the shared lever was told one of
     *  its two budgets and was given no way to learn there was another. That is the failure
     *  §4.2.6 exists to prevent, so it is refused at construction — and the refusal names
     *  both constraints, because knowing which lever is over-claimed is not enough to fix it.
     */
    @Test
    fun aLeverInTwoJointConstraintsIsRefusedAndBothConstraintsAreNamed() {
        fun declare(second: (DecisionElementBuilder.(LeverRef, LeverRef, LeverRef) -> Unit)?): Throwable? {
            val model = Model("SharedLever")
            val w = Widget(model, "W")
            return runCatching {
                w.decisionElement("D") {
                    observe(w.level)
                    val a = lever(w, 0..10, alias = "a", neutral = Neutral.Current { level.value }) { }
                    val b = lever(w, 0..10, alias = "b", neutral = Neutral.Current { rate }) { }
                    val c = lever(w, 0..10, alias = "c", neutral = Neutral.Current { mode.toDouble() }) { }
                    budget(a, b, total = 5.0)
                    second?.invoke(this, a, b, c)
                    every(10.0); policy = PolicyIfc { _, _ -> doubleArrayOf(5.0, 0.0, 0.0) }
                }
            }.exceptionOrNull()
        }

        val shared = declare { _, b, c -> atMost(b, c, total = 7.0) }
        val disjoint = declare { _, _, c -> atMost(c, total = 7.0) }

        println()
        println("budget(a, b) == 5 declared, then:")
        println("  atMost(b, c) <= 7  (shares b)   : ${shared?.let { it::class.simpleName } ?: "ACCEPTED"}")
        println("    ${shared?.message}")
        println("  atMost(c)    <= 7  (disjoint)   : ${disjoint?.let { it::class.simpleName } ?: "ACCEPTED"}")

        assertTrue(shared is IllegalArgumentException,
            "a lever named by two joint constraints should be refused at build()")
        val msg = shared.message ?: ""
        assertTrue("'b'" in msg, "the refusal should name the over-claimed lever; said: $msg")
        assertTrue("sum(a, b) == 5.0" in msg && "sum(b, c) <= 7.0" in msg,
            "the refusal should name BOTH constraints, not just the lever; said: $msg")

        // The check is about a shared lever, not about having two constraints. Two
        // constraints over disjoint levers is the ordinary multi-resource declaration.
        assertTrue(disjoint == null, "disjoint constraints should still be legal: $disjoint")
    }

    /**
     *  §4.6.4's whole warm-up analysis rests on the epoch running BEFORE the warm-up event,
     *  which holds because MEDIUM_LOW_PRIORITY (100 000) sorts ahead of
     *  DEFAULT_WARMUP_EVENT_PRIORITY (1 000 000). `epochPriority` is settable, and nothing
     *  checks it, so a modeler can invert a documented guarantee by declaring a number.
     */
    @Test
    fun theWarmUpOrderingIsDeclaredAndChecked() {
        fun epochsSeenAtWarmUp(priority: Int): Int {
            val model = Model("WarmUpOrder-$priority")
            var seen = -1
            val w = object : ModelElement(model, "W") {
                val level = TWResponse(this, name = "W:Level", initialValue = 5.0)
                var element: DecisionElement? = null
                override fun warmUp() { seen = element?.epochCount ?: -1 }
            }
            w.element = w.decisionElement("D") {
                epochPriority = priority
                warmUpOrdering = if (priority < KSLEvent.DEFAULT_WARMUP_EVENT_PRIORITY) WarmUpOrdering.EPOCH_FIRST
                                 else WarmUpOrdering.WARM_UP_FIRST
                observe(w.level)
                lever(w, 0..10, neutral = Neutral.Current { level.value }) { v -> level.value = v.toDouble() }
                every(10.0)
                policy = NeutralPolicy
            }
            model.numberOfReplications = 1
            model.lengthOfReplication = 100.0
            model.lengthOfReplicationWarmUp = 20.0    // exactly on an epoch boundary
            model.simulate()
            return seen
        }

        val before = epochsSeenAtWarmUp(KSLEvent.MEDIUM_LOW_PRIORITY)
        val after = epochsSeenAtWarmUp(KSLEvent.DEFAULT_WARMUP_EVENT_PRIORITY + 1)

        println()
        println("Epochs completed when warmUp() runs, warm-up at t=20 with epochs every 10:")
        println("  epochPriority = MEDIUM_LOW (the default) : $before")
        println("  epochPriority = warm-up + 1              : $after")
        assertTrue(before == 2, "the default should put the t=20 epoch BEFORE warm-up; saw $before")
        assertTrue(after == 1, "a higher priority number should put it AFTER; saw $after")
        println("  -> both orderings are reachable, and build() now rejects a priority that")
        println("     contradicts the declared warmUpOrdering (G.9 row 10)")

        // The contradiction is refused rather than silently obeyed.
        val contradiction = runCatching {
            val model = Model("Contradiction")
            val w = Widget(model, "W")
            w.decisionElement("D") {
                epochPriority = KSLEvent.DEFAULT_WARMUP_EVENT_PRIORITY + 1
                warmUpOrdering = WarmUpOrdering.EPOCH_FIRST      // the numbers say otherwise
                observe(w.level)
                lever(w, 0..10, neutral = Neutral.Current { level.value }) { v -> setLevel(v.toInt()) }
                every(10.0); policy = NeutralPolicy
            }
        }.exceptionOrNull()
        println("  declaring EPOCH_FIRST with a priority that sorts after warm-up: " +
            "${contradiction?.let { it::class.simpleName } ?: "ACCEPTED"}")
        assertTrue(contradiction is IllegalArgumentException,
            "a priority contradicting the declared ordering should be rejected at build()")
    }

    /**
     *  G.9 row 12. Two elements whose epochs coincide at equal priority run in declaration
     *  order — deterministic, and now stated and tested rather than left to event id by
     *  accident. A different priority is the mechanism for controlling it.
     */
    @Test
    fun coincidingElementsRunInDeclarationOrderAndPriorityOverridesIt() {
        fun order(secondPriority: Int): List<String> {
            val model = Model("Ordering-$secondPriority")
            val fired = mutableListOf<String>()
            val w1 = Widget(model, "W1"); val w2 = Widget(model, "W2")
            fun declare(w: Widget, tag: String, priority: Int) {
                w.decisionElement("D-$tag") {
                    epochPriority = priority
                    observe(w.level)
                    lever(w, 0..10, neutral = Neutral.Current { level.value }) { v -> setLevel(v.toInt()) }
                    every(10.0)
                    policy = PolicyIfc { _, ctx -> fired += tag; ctx.currentAction }
                }
            }
            declare(w1, "first", KSLEvent.MEDIUM_LOW_PRIORITY)
            declare(w2, "second", secondPriority)
            model.numberOfReplications = 1
            model.lengthOfReplication = 30.0
            model.simulate()
            return fired.take(2)
        }

        val equal = order(KSLEvent.MEDIUM_LOW_PRIORITY)
        val raised = order(KSLEvent.MEDIUM_LOW_PRIORITY - 1)     // lower number sorts earlier
        println()
        println("Two elements, epochs coinciding every 10:")
        println("  equal priority                  -> $equal")
        println("  second declared higher priority -> $raised")
        assertTrue(equal == listOf("first", "second"), "declaration order should decide; saw $equal")
        assertTrue(raised == listOf("second", "first"), "priority should override it; saw $raised")
    }
}
