package ksl.modeling.decision

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import ksl.controls.experiments.ConcurrentSimulationRunner
import ksl.modeling.decision.descriptor.RewardSense
import ksl.modeling.station.SResource
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.sdm.capture.MemorySink
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.simulation.SimulationDispatcher
import ksl.utilities.random.rvariable.ExponentialRV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  §18.2 item 6 — Level 2 when whole models run **concurrently**.
 *
 *  An earlier draft asserted this over *chunked replications*. That criterion was dropped on
 *  evidence (§16.2): `chunkReplications` has one non-test caller in the whole repository, and KSL's
 *  actual parallel machinery — `ParallelDesignedExperiment`, `ConcurrentScenarioRunner` — launches
 *  **whole models** as coroutines on `SimulationDispatcher.default` and mentions chunking nowhere.
 *  This class is the replacement, and it exists so that dropping a criterion is not the same thing
 *  as deleting one.
 *
 *  It is the shape the subsystem actually meets: a *k*-rule comparison runs *k* experiments, and
 *  policy search runs one model per parameter point. So the unit of parallelism is a `Model`, each
 *  with its own executive, event counter and stream provider (§10.3).
 *
 *  Two claims are checked, and the second is the one worth having:
 *
 *  1. **Concurrency changes no result.** *k* models run concurrently produce, model for model,
 *     exactly what the same *k* produce run one at a time — same event sequence, same transition
 *     rows, same reported statistics, same stream positions at the end.
 *  2. **The Level-2 guarantee survives concurrency.** A decorated model and its undecorated twin
 *     still fire the same events in the same order and leave the streams in the same place when
 *     they and six others are running at once.
 *
 *  Neither *should* be able to fail: the element is single-threaded, confined to its replication's
 *  thread, and consumes no randomness (§10.3, §10.4). "Should" is the reason to check. What would
 *  actually break these is shared mutable state reached from an element — a `companion object`
 *  counter, a shared sink, a policy instance handed to two models — and none of those is visible
 *  from a single-model test.
 *
 *  **Both arms run through `ConcurrentSimulationRunner`**, the sequential one awaiting each model
 *  before starting the next. Only the concurrency differs between the arms, so a difference cannot
 *  be blamed on the runner's replication loop differing from `Model.simulate()`.
 */
class ConcurrentModelsLevel2Test {

    /** A stochastic model whose trajectory depends on [target], so arms are visibly different. */
    private class Shop(
        parent: ModelElement,
        name: String,
        val decorated: Boolean,
        val target: Double,
        val sink: MemorySink?,
        private val threads: MutableSet<String>
    ) : ModelElement(parent, name) {

        private val arrivals = RandomVariable(this, ExponentialRV(2.0, streamNum = 11), "${this.name}:IAT")
        private val service = RandomVariable(this, ExponentialRV(1.5, streamNum = 12), "${this.name}:ST")

        @Suppress("unused")
        val staff = SResource(this, capacity = 3, name = "${this.name}:Staff")
        val nInQ = TWResponse(this, name = "${this.name}:NInQ")
        val nServed = Response(this, name = "${this.name}:NServed")
        var setting: Double = 3.0

        /** Every event this model fires, in order. */
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
                val t = target
                decisionElement("${this.name}:Review") {
                    observe(nInQ)
                    lever(this@Shop, 0.0..10.0, neutral = Neutral.Current { setting },
                        alias = "L") { v -> setting = v }
                    reward(nInQ, rate = 1.0, sense = RewardSense.COST, alias = "R")
                    if (sink != null) captureTo { sink }
                    every(5.0)
                    // Recording the thread is what makes "concurrently" a measured fact rather
                    // than an intention; the rule is called on the replication's own thread.
                    policy = PolicyIfc { _, _ ->
                        threads += Thread.currentThread().name
                        doubleArrayOf(t)
                    }
                }
            }
        }

        override fun initialize() {
            schedule(Arrive(), arrivals.value, priority = KSLEvent.MEDIUM_PRIORITY)
        }
    }

    /** One model plus everything about it that a run is allowed to determine. */
    private class Arm(
        val model: Model,
        val shop: Shop,
        val sink: MemorySink?,
        val threads: MutableSet<String>
    ) {
        fun fingerprint(): List<String> =
            model.randomVariables().sortedBy { it.name }.map { "${it.name}=${"%.12f".format(it.value)}" }

        fun rows(): List<String> = sink?.records.orEmpty().map {
            "${it.epochIndex}|${"%.6f".format(it.time)}|${"%.6f".format(it.tau)}|" +
                "${it.action.joinToString(",")}|${"%.9f".format(it.reward)}|${it.terminated}|${it.truncated}"
        }

        fun statistics(): List<String> = model.responses.sortedBy { it.name }.map {
            "${it.name}=${"%.9f".format(it.acrossReplicationStatistic.average)}" +
                "/${"%.9f".format(it.acrossReplicationStatistic.variance)}"
        }
    }

    private fun arm(index: Int, decorated: Boolean, label: String): Arm {
        val model = Model("$label-$index")
        val threads = mutableSetOf<String>()
        val sink = if (decorated) MemorySink() else null
        // Each arm targets a different lever value, so a result crossed between two models shows
        // up as a mismatch rather than hiding behind arms that were identical anyway.
        val shop = Shop(model, "S", decorated, target = 1.0 + index, sink = sink, threads = threads)
        model.numberOfReplications = 3
        model.lengthOfReplication = 120.0
        model.lengthOfReplicationWarmUp = 20.0
        // Required for [Arm.fingerprint] to read anything. KSL advances every stream to its next
        // sub-stream at the end of each replication (`Model.kt:1339`), which puts both arms on a
        // boundary fixed by the replication count and makes a post-run draw blind to the draws
        // taken within the run. Measured: with the advance left on, this test passed while the
        // element drew a stream at every epoch — as did `Level2OrderingAndStreamsTest`, which is
        // where the same defect was then found and fixed.
        model.advanceNextSubStreamOption = false
        return Arm(model, shop, sink, threads)
    }

    private fun runSequentially(arms: List<Arm>) = runBlocking {
        for (a in arms) ConcurrentSimulationRunner(a.model).simulate()
    }

    private fun runConcurrently(arms: List<Arm>) = runBlocking {
        coroutineScope {
            arms.map { a ->
                async(SimulationDispatcher.default) { ConcurrentSimulationRunner(a.model).simulate() }
            }.awaitAll()
        }
    }

    /** Asserts the concurrent arm really overlapped, so a pass is not vacuous. */
    private fun reportConcurrency(arms: List<Arm>) {
        val threads = arms.flatMap { it.threads }.toSet()
        println("    ran on ${threads.size} distinct thread(s) across ${arms.size} models " +
            "(${SimulationDispatcher.availableProcessors} processors available)")
        if (SimulationDispatcher.availableProcessors > 1) {
            assertTrue(threads.size > 1,
                "every model ran on one thread, so nothing was actually concurrent and this test " +
                    "proves nothing. ${SimulationDispatcher.availableProcessors} processors were " +
                    "available, so the dispatcher should have used more than one")
        }
    }

    /**
     *  Claim 1: running *k* models at once changes nothing about any of them.
     *
     *  The comparison is per model and covers the four things a run determines: the event sequence,
     *  the transition rows, the reported statistics, and where the streams ended up.
     */
    @Test
    fun kModelsRunConcurrentlyReproduceWhatTheySayRunOneAtATime() {
        val k = 8
        println()
        println("PROBE-CONC $k decorated models, sequential vs concurrent")

        val sequential = (0 until k).map { arm(it, decorated = true, label = "Seq") }
        runSequentially(sequential)

        val concurrent = (0 until k).map { arm(it, decorated = true, label = "Con") }
        runConcurrently(concurrent)
        reportConcurrency(concurrent)

        // The control: the arms must differ from each other, or agreeing pairwise is meaningless.
        val distinct = sequential.map { it.rows() }.toSet()
        println("    distinct transition-row sets across the $k arms: ${distinct.size}")
        assertEquals(k, distinct.size,
            "the $k arms produced fewer than $k distinct row sets, so they are not actually " +
                "different models and a crossed result between two of them would be invisible")

        for (i in 0 until k) {
            val s = sequential[i]
            val c = concurrent[i]
            assertEquals(s.shop.trace, c.shop.trace,
                "arm $i fired a different sequence of events when run concurrently")
            assertEquals(s.rows(), c.rows(),
                "arm $i recorded different transitions when run concurrently — same epochs, same " +
                    "actions, same rewards is the whole claim")
            assertEquals(s.statistics(), c.statistics(),
                "arm $i reported different statistics when run concurrently")
            assertEquals(s.fingerprint(), c.fingerprint(),
                "arm $i left its streams somewhere else when run concurrently; the element " +
                    "consumes no randomness (§10.4) and its neighbours cannot reach its provider")
        }
        println("    all $k arms identical in events, rows, statistics and stream position")
    }

    /**
     *  Claim 2: the Level-2 guarantee itself, asserted while everything is running at once.
     *
     *  Decorated and undecorated twins are interleaved in the same launch, so the decorated models
     *  are contending with each other and with the controls when their epochs fire.
     */
    @Test
    fun aNeutralElementStillReordersNothingWhenEightModelsRunAtOnce() {
        val k = 4
        println()
        println("PROBE-CONC $k decorated + $k undecorated models, all launched together")

        val decorated = (0 until k).map { arm(it, decorated = true, label = "Dec") }
        val plain = (0 until k).map { arm(it, decorated = false, label = "Plain") }

        // Interleaved so the two members of a pair are not launched back to back.
        val all = (0 until k).flatMap { listOf(decorated[it], plain[it]) }
        runConcurrently(all)
        reportConcurrency(decorated)

        for (i in 0 until k) {
            val d = decorated[i]
            val p = plain[i]
            val same = d.shop.trace == p.shop.trace
            println("    pair $i: ${d.shop.trace.size} vs ${p.shop.trace.size} events, identical order = $same")
            assertTrue(d.shop.trace.isNotEmpty(), "pair $i fired no events, so it asserts nothing")
            assertTrue(same,
                "pair $i: the decision element changed the sequence of events under concurrency. " +
                    "A NeutralPolicy must not reorder anything (§6.2), and running alongside other " +
                    "models must not change that")
            assertEquals(p.fingerprint(), d.fingerprint(),
                "pair $i: the decorated model's streams ended somewhere else than its twin's, so " +
                    "either the element drew or a neighbouring model reached its provider")
        }

        // And the decorated models did decide — otherwise the guarantee holds trivially.
        val epochs = decorated.map { it.sink!!.records.size }
        println("    transitions recorded per decorated model: $epochs")
        assertTrue(epochs.all { it > 0 },
            "a decorated model recorded no transitions, so this compared two undecorated runs")
    }
}
