package ksl.modeling.decision

import ksl.examples.general.decision.reviewEvery
import ksl.modeling.decision.descriptor.FeasibilityPolicy
import ksl.modeling.decision.descriptor.WarmUpOrdering
import ksl.modeling.variable.TWResponse
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /** Every probe, in declaration order, as it will appear in the generated matrix. */
    private val matrix = mutableListOf<Pair<String, String>>()

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
        println("  %-56s %s".format(what, verdict))
        matrix += what to verdict
        return verdict
    }

    /**
     *  Write the matrix as markdown, so §4.4.6.5 can carry this table rather than a
     *  transcription of it.
     *
     *  §4.4.6.5's table was maintained by hand and drifted: it reported "8 working, 3 refused at
     *  declaration" against a tree that worked 10 and refused 1, because two of the three had been
     *  built and nobody re-counted. Appendix E.2 drifted the same way and for the same reason,
     *  while Appendix G — copied from the tree and checked against it — did not. The difference is
     *  mechanical rather than moral, so this emits what the table should say.
     *
     *  It writes a file rather than only printing because a table assembled by eye from console
     *  output is a transcription again, one step removed.
     */
    private fun emitMatrix() {
        val out = StringBuilder()
        out.appendLine("| Form the DSL accepts | Verdict |")
        out.appendLine("|---|---|")
        for ((form, verdict) in matrix) out.appendLine("| $form | $verdict |")
        val n = matrix.size
        fun count(prefix: String) = matrix.count { it.second.startsWith(prefix) }
        out.appendLine()
        out.appendLine(
            "$n declarable forms probed: ${count("WORKS")} work, " +
                "${count("REFUSED AT DECLARATION")} refused at declaration as unbuilt, " +
                "${count("REFUSED AT CONSTRUCTION")} refused at construction by design, " +
                "${count("REFUSED AT RUN TIME")} refused at run time by design, " +
                "${count("REFUSED AT USE")} refused at use by design, " +
                "${count("FAILS")} unspecified."
        )
        val file = File("build/reports/declaration_surface_matrix.md")
        file.parentFile?.mkdirs()
        file.writeText(out.toString())
        println()
        println("--- generated matrix, written to ${file.absolutePath} ---")
        print(out)
        println("--- end ---")
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
        }.reviewEvery(w, 10.0)
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

        results["INTEGER lever"] = probe("`lever(limits = 0..10)`") {
            runWith { w -> observe(w.level); lever(w, 0..10, neutral = Neutral.Current { level.value }) { v -> setLevel(v.toInt()) }
                policy = NeutralPolicy }
        }
        results["CONTINUOUS lever"] = probe("`lever(limits = 0.0..10.0)`") {
            runWith { w -> observe(w.level); lever(w, 0.0..10.0, neutral = Neutral.Current { rate }) { v -> rate = v }
                policy = NeutralPolicy }
        }
        results["CATEGORICAL lever"] = probe("`lever(levels = listOf(...))`") {
            runWith { w -> observe(w.level)
                lever(w, listOf("off", "slow", "fast"), neutral = Neutral.Current { mode.toDouble() }) { v -> mode = v.toInt() }
                policy = NeutralPolicy }
        }
        results["CATEGORICAL + CLAMP_THEN_REJECT"] = probe("clamping a `CATEGORICAL` lever") {
            runWith { w -> observe(w.level)
                feasibility = FeasibilityPolicy.CLAMP_THEN_REJECT
                lever(w, listOf("off", "slow", "fast"), neutral = Neutral.Current { mode.toDouble() }) { v -> mode = v.toInt() }
                policy = PolicyIfc { _, _ -> doubleArrayOf(9.0) }   // far outside; must clamp
            }
        }
        results["batchLever"] = probe("`batchLever(...)` for atomic multi-writes (§4.4.5)") {
            runWith { w -> observe(w.level)
                val a = lever(w, 0..10, alias = "a", neutral = Neutral.Current { level.value }) { v -> setLevel(v.toInt()) }
                val b = lever(w, 0..10, alias = "b", neutral = Neutral.Current { rate }) { v -> rate = v }
                batchLever(a, b) { }
                policy = NeutralPolicy }
        }
        results["reward + estimand"] = probe("`reward(...)` then read the estimand (§4.2.5)") {
            val m = runWith { w -> observe(w.level)
                lever(w, 0..10, neutral = Neutral.Current { level.value }) { v -> setLevel(v.toInt()) }
                reward(w.level, rate = 1.0)
                policy = NeutralPolicy }
            (m.getModelElement("D") as DecisionElement).estimand
        }
        results["captureTo"] = probe("`captureTo(...)` trajectory sink (§4.8)") {
            runWith { w -> observe(w.level)
                lever(w, 0..10, neutral = Neutral.Current { level.value }) { v -> setLevel(v.toInt()) }
                captureTo { ksl.sdm.capture.NullSink }
                policy = NeutralPolicy }
        }
        results["terminalWhen"] = probe("`terminalWhen { ... }` episode ending (§4.6.3)") {
            runWith { w -> observe(w.level); lever(w, 0..10, neutral = Neutral.Current { level.value }) { v -> setLevel(v.toInt()) }
                terminalWhen { false }; policy = NeutralPolicy }
        }
        results["two constraints over one lever"] = probe("`budget(a,b)` and `atMost(b,c)` sharing `b`") {
            runWith { w -> observe(w.level)
                val a = lever(w, 0..10, alias = "a", neutral = Neutral.Current { level.value }) { }
                val b = lever(w, 0..10, alias = "b", neutral = Neutral.Current { rate }) { }
                val c = lever(w, 0..10, alias = "c", neutral = Neutral.Current { mode.toDouble() }) { }
                budget(a, b, total = 5.0); atMost(b, c, total = 5.0)
                policy = PolicyIfc { _, _ -> doubleArrayOf(5.0, 0.0, 0.0) } }
        }
        results["TRANSACTION lever"] = probe("`lever(neutral = Neutral.Value(0.0))` (§8.2.3)") {
            runWith { w -> observe(w.level)
                lever(w, 0..10, neutral = Neutral.Value(0.0)) { v -> repeat(v.toInt()) { bump() } }
                policy = NeutralPolicy }
        }
        results["CATEGORICAL transaction"] = probe("a `CATEGORICAL` lever with a neutral AMOUNT") {
            runWith { w -> observe(w.level)
                lever(w, listOf("off", "slow", "fast"), neutral = Neutral.Value(0.0)) { v -> mode = v.toInt() }
                policy = NeutralPolicy }
        }
        results["budget over mismatched units"] = probe("`budget(a, b)` where `a` and `b` differ in unit") {
            runWith { w -> observe(w.level)
                val a = lever(w, 0..10, alias = "a", unit = "staff",
                    neutral = Neutral.Current { level.value }) { }
                val b = lever(w, 0..10, alias = "b", unit = "dollars",
                    neutral = Neutral.Current { rate }) { }
                budget(a, b, total = 5.0)
                policy = PolicyIfc { _, _ -> doubleArrayOf(5.0, 0.0) } }
        }
        results["context read after the epoch"] = probe("retaining the `DecisionContext` (§4.5.3)") {
            var stashed: DecisionContext? = null
            runWith { w -> observe(w.level)
                lever(w, 0..10, neutral = Neutral.Current { level.value }) { v -> setLevel(v.toInt()) }
                policy = PolicyIfc { _, ctx -> stashed = ctx; ctx.currentAction } }
            stashed!!.simulationTime
        }
        results["two decision elements"] = probe("two elements in one model (§4.1.9)") {
            val model = Model("TwoElements")
            val w1 = Widget(model, "W1"); val w2 = Widget(model, "W2")
            w1.decisionElement("D1") { observe(w1.level)
                lever(w1, 0..10, neutral = Neutral.Current { level.value }) { v -> setLevel(v.toInt()) }
                policy = NeutralPolicy }
            w2.decisionElement("D2") { observe(w2.level)
                lever(w2, 0..10, neutral = Neutral.Current { level.value }) { v -> setLevel(v.toInt()) }
                policy = NeutralPolicy }
            model.numberOfReplications = 2; model.lengthOfReplication = 100.0
            model.simulate()
        }
        results["epoch priority after warm-up, undeclared"] =
            probe("`epochPriority` past warm-up without saying so") {
                runWith(priority = KSLEvent.DEFAULT_WARMUP_EVENT_PRIORITY + 1) { w -> observe(w.level)
                    lever(w, 0..10, neutral = Neutral.Current { level.value }) { v -> setLevel(v.toInt()) }
                    policy = NeutralPolicy }
            }
        results["epoch priority after warm-up, declared"] =
            probe("the same, with `warmUpOrdering = WARM_UP_FIRST`") {
                runWith(priority = KSLEvent.DEFAULT_WARMUP_EVENT_PRIORITY + 1) { w -> observe(w.level)
                    warmUpOrdering = WarmUpOrdering.WARM_UP_FIRST
                    lever(w, 0..10, neutral = Neutral.Current { level.value }) { v -> setLevel(v.toInt()) }
                    policy = NeutralPolicy }
            }

        println()
        val deferred = results.filterValues { it.startsWith("REFUSED AT DECLARATION") }
        val refused = results.filterValues { it == "REFUSED AT CONSTRUCTION" }
        val atRun = results.filterValues { it == "REFUSED AT RUN TIME" }
        val atUse = results.filterValues { it == "REFUSED AT USE" }
        val fails = results.filterValues { it.startsWith("FAILS") }
        println("  ${results.size} declarable forms probed: " +
            "${results.count { it.value == "WORKS" }} work, " +
            "${deferred.size} refused at declaration as unbuilt, " +
            "${refused.size} refused at construction by design, " +
            "${atRun.size} refused at run time by design, " +
            "${atUse.size} refused at use by design, ${fails.size} fail")
        if (deferred.isNotEmpty()) println("  not built: ${deferred.keys}")
        if (refused.isNotEmpty()) println("  at build : ${refused.keys}")
        if (atRun.isNotEmpty()) println("  at run   : ${atRun.keys}")
        if (atUse.isNotEmpty()) println("  at use   : ${atUse.keys}")
        if (fails.isNotEmpty()) println("  failing  : ${fails.keys}")

        emitMatrix()

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

        // How complete the DSL is, as one number the library states about itself: **every** form
        // the DSL accepts is now carried. `batchLever` was the last deferral and it landed
        // (§4.4.5, `BatchLeverTest`), so this set is empty — and an entry appearing in it is
        // either a regression or a deliberate deferral, both of which should be decided rather
        // than noticed.
        assertEquals(emptySet(), deferred.keys,
            "a form the DSL accepts can no longer be carried. Every declarable form is supposed " +
                "to work: if this is a deliberate deferral, say so here and in §4.4.6.5's matrix; " +
                "if it is not, it is a regression. Deferred: ${deferred.keys}")

        assertEquals("WORKS", results["batchLever"],
            "batchLever is the atomic multi-write of §4.4.5 and ADR-6's stated escape hatch. It " +
                "refused at the declaration until a pre-port audit closed it")
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
            policy = PolicyIfc { _, _ -> doubleArrayOf(9.0) }     // not a level at all
        }.reviewEvery(w, 10.0)
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
     *  G.9 row 7. `unit` was a field nothing read, which is the fault D.10 names. It is now
     *  read in the one place where units actually combine — a joint constraint sums its
     *  levers — and printed wherever a value is reported.
     *
     *  The scope is the interesting part, and it is deliberately narrow. `unit` is optional,
     *  so every check built on it is conditional on someone having declared one, and a
     *  surface that declares nothing passes trivially. `unitCoverage()` exists so that
     *  "passed" and "was never checked" are distinguishable, which is the difference between
     *  an enforced invariant and a decorative one.
     */
    @Test
    fun aJointConstraintOverMismatchedUnitsIsRefusedAndPartialCoverageIsReported() {
        fun declare(unitB: String?, unitC: String?): Pair<Throwable?, UnitCoverage?> {
            val model = Model("Units-$unitB-$unitC")
            val w = Widget(model, "W")
            var cov: UnitCoverage? = null
            val e = runCatching {
                val el = w.decisionElement("D") {
                    observe(w.level, "Level", unit = "widgets")
                    val a = lever(w, 0..10, alias = "a", unit = "staff",
                        neutral = Neutral.Current { level.value }) { }
                    val b = lever(w, 0..10, alias = "b", unit = unitB,
                        neutral = Neutral.Current { rate }) { }
                    val c = lever(w, 0..10, alias = "c", unit = unitC,
                        neutral = Neutral.Current { mode.toDouble() }) { }
                    budget(a, b, c, total = 5.0)
                    policy = PolicyIfc { _, _ -> doubleArrayOf(5.0, 0.0, 0.0) }
                }
                cov = el.unitCoverage()
            }.exceptionOrNull()
            return e to cov
        }

        val (agreeing, fullCov) = declare("staff", "staff")
        val (mismatched, _) = declare("staff", "dollars")
        val (partial, partCov) = declare("staff", null)

        println()
        println("budget(a, b, c) == 5, a always in staff:")
        println("  b, c in staff        : ${agreeing?.let { it::class.simpleName } ?: "ACCEPTED"}   $fullCov")
        println("  c in dollars         : ${mismatched?.let { it::class.simpleName } ?: "ACCEPTED"}")
        println("    ${mismatched?.message}")
        println("  c undeclared         : ${partial?.let { it::class.simpleName } ?: "ACCEPTED"}   $partCov")

        assertTrue(agreeing == null, "levers agreeing on a unit should be legal: $agreeing")
        assertTrue(mismatched is IllegalArgumentException,
            "summing staff and dollars should be refused; it was accepted")
        val msg = mismatched.message ?: ""
        assertTrue("staff" in msg && "dollars" in msg,
            "the refusal should name both units; said: $msg")

        // An undeclared unit is not an error — `unit` is optional and a partial declaration
        // is a legitimate half-step — but the surface must not claim it was fully checked.
        assertTrue(partial == null, "an undeclared unit should not be an error: $partial")
        assertTrue(fullCov!!.fullyChecked, "the agreeing surface should report a full check")
        assertTrue(!partCov!!.fullyChecked,
            "a partly-declared constraint must not report as fully checked; got $partCov")
        assertTrue(partCov.constraintsPartlyChecked == 1, "expected one partly-checked constraint")
    }

    /**
     *  The other half of row 7: a unit appears wherever a number about a lever is reported,
     *  because a violation message is the one place a units mistake reliably surfaces at all.
     */
    @Test
    fun violationMessagesNameTheDeclaredUnit() {
        val model = Model("UnitsInMessages")
        val w = Widget(model, "W")
        var violations: List<String> = emptyList()
        w.decisionElement("D") {
            observe(w.level)
            val a = lever(w, 0..10, alias = "a", unit = "staff",
                neutral = Neutral.Current { level.value }) { v -> setLevel(v.toInt()) }
            val b = lever(w, 0..10, alias = "b", unit = "staff",
                neutral = Neutral.Current { rate }) { v -> rate = v }
            budget(a, b, total = 8.0)
            policy = PolicyIfc { _, ctx ->
                violations = ctx.actions.violations(doubleArrayOf(40.0, 0.0))
                doubleArrayOf(4.0, 4.0)
            }
        }.reviewEvery(w, 10.0)
        model.numberOfReplications = 1
        model.lengthOfReplication = 30.0
        model.simulate()

        println()
        println("Violations for the action (40, 0) against two levers in staff, budget 8:")
        violations.forEach { println("  $it") }

        assertTrue(violations.isNotEmpty(), "the probe action should have violated something")
        assertTrue(violations.all { "staff" in it },
            "every violation about a united lever should name the unit; got $violations")
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
                // The same value every epoch. For a setting the first write lands and the
                // rest are redundant; for a transaction every one of them is an act.
                policy = PolicyIfc { _, _ -> doubleArrayOf(3.0) }
            }.reviewEvery(w, 10.0)
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
     *  G.9 row 6. §4.5.3 said retaining a context is a bug and nothing prevented it. Before the
     *  repair the element reused one instance, so a retained context answered about whatever epoch
     *  had happened since — a well-formed number belonging to a different decision, with nothing
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
            policy = PolicyIfc { _, ctx ->
                if (stashed == null) { stashed = ctx; stashedActions = ctx.actions }
                ctx.currentAction
            }
        }.reviewEvery(w, 10.0)
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
        }.reviewEvery(w, 10.0)
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
                    policy = PolicyIfc { _, _ -> doubleArrayOf(5.0, 0.0, 0.0) }
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
     *  §4.6.4's whole warm-up analysis rests on the review running BEFORE the warm-up event, which
     *  holds because MEDIUM_LOW_PRIORITY (100 000) sorts ahead of DEFAULT_WARMUP_EVENT_PRIORITY
     *  (1 000 000).
     *
     *  **The knob moved with the timing (S§C.0).** A review taken through `decide` happens inside
     *  the caller's event, so it is the *caller's* priority that decides which side of the warm-up
     *  it lands on -- not the element's `epochPriority`, which now orders only deferred epochs. The
     *  guarantee is the same and the thing a modeler sets to invert it is different, so this varies
     *  what a modeler would actually vary.
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
                observe(w.level)
                lever(w, 0..10, neutral = Neutral.Current { level.value }) { v -> level.value = v.toDouble() }
                policy = NeutralPolicy
            }.reviewEvery(w, 10.0, priority = priority)
            model.numberOfReplications = 1
            model.lengthOfReplication = 100.0
            model.lengthOfReplicationWarmUp = 20.0    // exactly on a review boundary
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
                policy = NeutralPolicy
            }
        }.exceptionOrNull()
        println("  declaring EPOCH_FIRST with a priority that sorts after warm-up: " +
            "${contradiction?.let { it::class.simpleName } ?: "ACCEPTED"}")
        assertTrue(contradiction is IllegalArgumentException,
            "a priority contradicting the declared ordering should be rejected at build()")
    }

}
