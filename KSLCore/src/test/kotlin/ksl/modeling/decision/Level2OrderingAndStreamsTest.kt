package ksl.modeling.decision

import ksl.examples.general.decision.PeriodicReview
import ksl.modeling.decision.descriptor.RewardSense
import ksl.modeling.station.SResource
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 *  §6.2 Level 2 — the two assertions that section requires and that nothing made until now:
 *  **same-time event ordering** and a **stream-position trace**.
 *
 *  `Level1CompatibilityTest` compares the two arms' *reported* and *fine-grained* statistics.
 *  Neither catches a decision element that reordered coinciding events or advanced a stream,
 *  because both are statements about accumulated values rather than about the sequence that
 *  produced them. §6.2 says Level 2 "must include same-time event ordering and a stream-position
 *  trace"; this is that.
 *
 *  **Streams are pinned, and there is an identical-models control.** The control currently passes
 *  trivially: each `Model` owns its stream provider (`Model.kt:207`), and a random variable built on
 *  another provider is re-instantiated against the model's own (`RandomVariable.kt:94`), so two
 *  identical models in one JVM draw the same streams whether or not they are pinned. It is kept as a
 *  **canary** rather than a live guard — it would catch KSL returning to a shared provider, or this
 *  element acquiring randomness of its own, either of which would invalidate everything below.
 *
 *  An earlier version of this comment said the opposite, repeating P§6.2's claim that two models in
 *  one JVM never draw the same streams. That was true of the KSL the proposal was written against
 *  and is no longer; see OOD §10.4.
 *
 *  The stream position is read *indirectly and exactly*: after each run, one further draw is taken
 *  from every random variable. Two runs whose streams are at the same position produce the same
 *  draws, and the stream's own state is private (`Cg`/`Bg`/`Ig` on the MRG32k3a stream), so this
 *  is both the available check and a sufficient one.
 *
 *  **That reading was inert until the between-replication sub-stream advance was turned off**, and
 *  the check silently asserted nothing for as long as it existed. `ConcurrentModelsLevel2Test`'s
 *  mutation pass found it: with `advanceNextSubStreamOption` at its default of `true`, every stream
 *  lands on a sub-stream boundary fixed by the replication count at the end of each replication,
 *  so the post-run draw is blind to draws taken during it — and this test passed unchanged while
 *  the element drew from a stream at every epoch. See the note in `build`. It is recorded here
 *  rather than quietly fixed because it is the second time a green assertion in this suite turned
 *  out to be measuring nothing, which is a fact about the suite a reviewer should have.
 */
class Level2OrderingAndStreamsTest {

    /** A stochastic model with same-time events, so ordering and streams both matter. */
    private class Shop(parent: ModelElement, val decorated: Boolean, name: String) :
        ModelElement(parent, name) {

        private val arrivals = RandomVariable(this, ExponentialRV(2.0, streamNum = 11), "${this.name}:IAT")
        private val service = RandomVariable(this, ExponentialRV(1.5, streamNum = 12), "${this.name}:ST")
        val staff = SResource(this, capacity = 3, name = "${this.name}:Staff")
        val nInQ = TWResponse(this, name = "${this.name}:NInQ")
        val nServed = Response(this, name = "${this.name}:NServed")
        var setting: Double = 3.0

        /** Every event the model fires, in order, for the ordering trace. */
        val trace = mutableListOf<String>()

        private inner class Arrive : EventActionIfc<Nothing> {
            override fun action(event: KSLEvent<Nothing>) {
                trace += "%.6f:A".format(time)
                nInQ.increment()
                schedule(Depart(), service.value, priority = KSLEvent.MEDIUM_PRIORITY)
                schedule(this, arrivals.value, priority = KSLEvent.MEDIUM_PRIORITY)
            }
        }

        private inner class Depart : EventActionIfc<Nothing> {
            override fun action(event: KSLEvent<Nothing>) {
                trace += "%.6f:D".format(time)
                nInQ.decrement()
                nServed.value = nInQ.value
            }
        }

        init {
            if (decorated) {
                val e = decisionElement("${this.name}:Review") {
                    observe(nInQ)
                    lever(this@Shop, 0..10, neutral = Neutral.Current { setting },
                        alias = "L") { v -> setting = v }
                    reward(nInQ, rate = 1.0, sense = RewardSense.COST, alias = "R")
                    policy = NeutralPolicy
                }
                // The element no longer schedules its own reviews; a caller does. This is the
                // canary for the migration: if moving the review out of the element perturbs the
                // model's own same-time event ordering, or its end-of-run stream position, this
                // test is the one that says so.
                PeriodicReview(this, interval = 5.0, name = "${this.name}:Reviewer").element = e
            }
        }

        override fun initialize() {
            schedule(Arrive(), arrivals.value, priority = KSLEvent.MEDIUM_PRIORITY)
        }
    }

    private class Arm(val model: Model, val shop: Shop)

    private fun build(decorated: Boolean, warmUp: Double, reps: Int, chunks: Int): Arm {
        val m = Model("Arm")
        val shop = Shop(m, decorated, "S")
        m.numberOfReplications = reps
        m.lengthOfReplication = 120.0
        m.lengthOfReplicationWarmUp = warmUp
        // chunked runs are set through run parameters, not on Model — left out; see report
        //
        // Without this line the stream assertion below cannot fail. KSL advances every stream to
        // the start of its next sub-stream at the end of each replication
        // (`Model.kt:1339`, `advanceNextSubStreamOption` defaults to `true`), which lands both arms
        // on a sub-stream boundary determined only by the replication count. A post-run draw then
        // agrees no matter how many draws were taken inside the replication, so an element that
        // consumed randomness would pass unnoticed. Measured: with the advance left on, this test
        // passed while the element drew from a stream at every epoch.
        //
        // Turning it off makes the end-of-run position a function of the draws actually taken,
        // which is what the fingerprint is supposed to read. Both arms are configured identically,
        // so the comparison is unaffected in every other respect.
        m.advanceNextSubStreamOption = false
        return Arm(m, shop)
    }

    /**
     *  After the run, the next draw from every RV reveals whether streams are at the same place.
     *
     *  This is only informative because [build] turns off the between-replication sub-stream
     *  advance; see the note there.
     */
    private fun streamFingerprint(m: Model): List<String> =
        m.randomVariables().sortedBy { it.name }.map { "${it.name}=${"%.12f".format(it.value)}" }

    private fun runArm(decorated: Boolean, warmUp: Double = 0.0, reps: Int = 1, chunks: Int = 1): Pair<List<String>, List<String>> {
        val a = build(decorated, warmUp, reps, chunks)
        a.model.simulate()
        return a.shop.trace.toList() to streamFingerprint(a.model)
    }

    /** Returns (event order identical, stream positions identical). */
    private fun compare(
        label: String,
        x: Pair<List<String>, List<String>>,
        y: Pair<List<String>, List<String>>
    ): Pair<Boolean, Boolean> {
        val (tx, sx) = x; val (ty, sy) = y
        val orderSame = tx == ty
        val firstDiff = tx.zip(ty).indexOfFirst { it.first != it.second }
        println("PROBE-L2 $label")
        println("    events: ${tx.size} vs ${ty.size}; identical order = $orderSame" +
            if (!orderSame) "  first difference at index $firstDiff" else "")
        println("    streams after run identical = ${sx == sy}")
        if (sx != sy) sx.zip(sy).filter { it.first != it.second }.forEach { println("      ${it.first}  vs  ${it.second}") }
        return orderSame to (sx == sy)
    }

    @Test
    fun aNeutralDecisionElementReordersNoEventAndAdvancesNoStream() {
        println()
        // The control first. Two undecorated arms must agree, or everything below is vacuous.
        val control = compare("CONTROL undecorated vs undecorated", runArm(false), runArm(false))
        assertTrue(control.first && control.second,
            "two identical models disagreed, so this test is measuring the stream provider " +
                "rather than the subsystem (§10.4). Pin the streams before reading anything else")

        val cases = listOf(
            "1 rep, no warm-up" to (runArm(false) to runArm(true)),
            "1 rep, warm-up 40" to (runArm(false, warmUp = 40.0) to runArm(true, warmUp = 40.0)),
            "10 reps, no warm-up" to (runArm(false, reps = 10) to runArm(true, reps = 10)),
            "10 reps, warm-up 40" to (runArm(false, warmUp = 40.0, reps = 10) to runArm(true, warmUp = 40.0, reps = 10)),
            // A warm-up landing exactly on an epoch — epochs every 5, so 40 is a multiple — and
            // one landing between epochs. §4.6.4 treats these differently and both must be silent.
            "warm-up ON an epoch (40)" to (runArm(false, warmUp = 40.0) to runArm(true, warmUp = 40.0)),
            "warm-up OFF an epoch (42)" to (runArm(false, warmUp = 42.0) to runArm(true, warmUp = 42.0))
        )
        for ((label, arms) in cases) {
            val (order, streams) = compare(label, arms.first, arms.second)
            assertTrue(order,
                "$label: the decision element changed the sequence of events. A NeutralPolicy " +
                    "must not reorder anything, including events coinciding with an epoch (§6.2)")
            assertTrue(streams,
                "$label: the decision element left the streams somewhere else. It consumes no " +
                    "randomness of its own (§10.4), so any advance is a defect")
        }
    }
}
