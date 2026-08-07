package ksl.modeling.decision

import ksl.modeling.decision.descriptor.FeasibilityPolicy
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
            outcome is NotImplementedError -> "DECLARED, NOT BUILT"
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
            runWith { w -> observe(w.level); lever(w, 0..10, read = { level.value }) { v -> setLevel(v.toInt()) }
                every(10.0); policy = HoldCurrentPolicy }
        }
        results["CONTINUOUS lever"] = probe("lever(limits = 0.0..10.0)") {
            runWith { w -> observe(w.level); lever(w, 0.0..10.0, read = { rate }) { v -> rate = v }
                every(10.0); policy = HoldCurrentPolicy }
        }
        results["CATEGORICAL lever"] = probe("lever(levels = listOf(...))") {
            runWith { w -> observe(w.level)
                lever(w, listOf("off", "slow", "fast"), read = { mode.toDouble() }) { v -> mode = v.toInt() }
                every(10.0); policy = HoldCurrentPolicy }
        }
        results["CATEGORICAL + CLAMP_THEN_REJECT"] = probe("clamping a categorical lever") {
            runWith { w -> observe(w.level)
                feasibility = FeasibilityPolicy.CLAMP_THEN_REJECT
                lever(w, listOf("off", "slow", "fast"), read = { mode.toDouble() }) { v -> mode = v.toInt() }
                every(10.0)
                policy = PolicyIfc { _, _ -> doubleArrayOf(9.0) }   // far outside; must clamp
            }
        }
        results["batchLever"] = probe("batchLever(...) for atomic multi-writes (§4.4.5)") {
            runWith { w -> observe(w.level)
                val a = lever(w, 0..10, alias = "a", read = { level.value }) { v -> setLevel(v.toInt()) }
                val b = lever(w, 0..10, alias = "b", read = { rate }) { v -> rate = v }
                batchLever(a, b) { }
                every(10.0); policy = HoldCurrentPolicy }
        }
        results["reward + estimand"] = probe("reward(...) then read the estimand (§4.2.5)") {
            val m = runWith { w -> observe(w.level)
                lever(w, 0..10, read = { level.value }) { v -> setLevel(v.toInt()) }
                reward(w.level, rate = 1.0)
                every(10.0); policy = HoldCurrentPolicy }
            (m.getModelElement("D") as DecisionElement).estimand
        }
        results["captureTo"] = probe("captureTo(...) trajectory sink (§4.8)") {
            runWith { w -> observe(w.level)
                lever(w, 0..10, read = { level.value }) { v -> setLevel(v.toInt()) }
                captureTo { ksl.sdm.capture.NullSink }
                every(10.0); policy = HoldCurrentPolicy }
        }
        results["CALENDAR timing"] = probe("onCalendar(listOf(...))") {
            runWith { w -> observe(w.level); lever(w, 0..10, read = { level.value }) { v -> setLevel(v.toInt()) }
                onCalendar(listOf(15.0, 35.0, 60.0)); policy = HoldCurrentPolicy }
        }
        results["terminalWhen"] = probe("terminalWhen { ... } episode ending (§4.6.3)") {
            runWith { w -> observe(w.level); lever(w, 0..10, read = { level.value }) { v -> setLevel(v.toInt()) }
                every(10.0); terminalWhen { false }; policy = HoldCurrentPolicy }
        }
        results["two constraints over one lever"] = probe("budget(a,b) and atMost(b,c) sharing b") {
            runWith { w -> observe(w.level)
                val a = lever(w, 0..10, alias = "a", read = { level.value }) { }
                val b = lever(w, 0..10, alias = "b", read = { rate }) { }
                val c = lever(w, 0..10, alias = "c", read = { mode.toDouble() }) { }
                budget(a, b, total = 5.0); atMost(b, c, total = 5.0)
                every(10.0); policy = PolicyIfc { _, _ -> doubleArrayOf(5.0, 0.0, 0.0) } }
        }
        results["two decision elements"] = probe("two elements in one model (§4.1.9)") {
            val model = Model("TwoElements")
            val w1 = Widget(model, "W1"); val w2 = Widget(model, "W2")
            w1.decisionElement("D1") { observe(w1.level)
                lever(w1, 0..10, read = { level.value }) { v -> setLevel(v.toInt()) }
                every(10.0); policy = HoldCurrentPolicy }
            w2.decisionElement("D2") { observe(w2.level)
                lever(w2, 0..10, read = { level.value }) { v -> setLevel(v.toInt()) }
                every(10.0); policy = HoldCurrentPolicy }
            model.numberOfReplications = 2; model.lengthOfReplication = 100.0
            model.simulate()
        }
        results["epoch priority after warm-up"] = probe("epochPriority above DEFAULT_WARMUP_EVENT_PRIORITY") {
            runWith(priority = KSLEvent.DEFAULT_WARMUP_EVENT_PRIORITY + 1) { w -> observe(w.level)
                lever(w, 0..10, read = { level.value }) { v -> setLevel(v.toInt()) }
                every(10.0); policy = HoldCurrentPolicy }
        }

        println()
        val notBuilt = results.filterValues { it.startsWith("DECLARED") }
        val fails = results.filterValues { it.startsWith("FAILS") }
        println("  ${results.size} declarable forms probed: " +
            "${results.count { it.value == "WORKS" }} work, " +
            "${notBuilt.size} declared but not built, ${fails.size} fail")
        if (notBuilt.isNotEmpty()) println("  not built: ${notBuilt.keys}")
        if (fails.isNotEmpty()) println("  failing  : ${fails.keys}")

        assertTrue(results.isNotEmpty())
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
    fun clampingACategoricalLeverSilentlyPicksACategory() {
        val model = Model("CategoricalClamp")
        val w = Widget(model, "W")
        w.decisionElement("D") {
            observe(w.level)
            feasibility = FeasibilityPolicy.CLAMP_THEN_REJECT
            lever(w, listOf("off", "slow", "fast"), read = { mode.toDouble() }) { v -> mode = v.toInt() }
            every(10.0)
            policy = PolicyIfc { _, _ -> doubleArrayOf(9.0) }     // not a level at all
        }
        model.numberOfReplications = 1
        model.lengthOfReplication = 100.0
        model.simulate()

        println()
        println("CATEGORICAL clamp: policy asked for level 9 of {off, slow, fast}; lever ended at ${w.mode}")
        assertTrue(w.mode == 2,
            "expected the clamp to pick the highest level by numeric proximity — it chose ${w.mode}")
        println("  -> the request was nonsense and the library chose 'fast' rather than rejecting")
    }

    /**
     *  §4.6.4's whole warm-up analysis rests on the epoch running BEFORE the warm-up event,
     *  which holds because MEDIUM_LOW_PRIORITY (100 000) sorts ahead of
     *  DEFAULT_WARMUP_EVENT_PRIORITY (1 000 000). `epochPriority` is settable, and nothing
     *  checks it, so a modeler can invert a documented guarantee by declaring a number.
     */
    @Test
    fun epochPriorityCanSilentlyInvertTheWarmUpGuarantee() {
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
                observe(w.level)
                lever(w, 0..10, read = { level.value }) { v -> level.value = v.toDouble() }
                every(10.0)
                policy = HoldCurrentPolicy
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
        println("  -> §4.6.4's ordering is a consequence of a settable number that nothing validates")
    }
}
