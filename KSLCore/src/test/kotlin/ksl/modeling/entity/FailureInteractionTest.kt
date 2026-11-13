/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.modeling.entity

import ksl.animation.AnimationEvent
import ksl.animation.AnimationSink
import ksl.animation.AnimationTraceHeader
import ksl.animation.TraceFileReader
import ksl.modeling.agent.AgentMessage
import ksl.modeling.agent.AgentModel
import ksl.modeling.agent.AgentResource
import ksl.modeling.agent.sendMessage
import ksl.modeling.spatial.DistancesModel
import ksl.modeling.spatial.MovableResource
import ksl.modeling.spatial.MovableResourcePoolWithQ
import ksl.modeling.spatial.MovableResourceWithQ
import ksl.modeling.variable.Counter
import ksl.modeling.variable.Response
import ksl.animation.emitters.ResourceAnimationEmitter
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Failures meeting the constructs that already exist: pools and movable resources, capacity changes,
 *  the timed seizes and `waitForFirst`, the routes a request can take to a resource, an agent resource's
 *  statechart, warm-up and replications, and the animation trace.
 */
class FailureInteractionTest {

    private class IModel(parent: ModelElement) : ProcessModel(parent, "failureInteractions") {
        private val speed = ConstantRV(10.0)
        private val dm = DistancesModel()
        val locA = dm.Location("A")
        val locB = dm.Location("B")

        init {
            dm.addDistance(locA, locB, 100.0, symmetric = true)
            dm.defaultVelocity = speed
            spatialModel = dm
        }

        val r = ResourceWithQ(this, "R")
        val r2 = ResourceWithQ(this, "R2", capacity = 2)
        val q2 = RequestQ(this, "Q2")
        val p1 = ResourceWithQ(this, "P1")
        val p2 = ResourceWithQ(this, "P2")
        val pool = ResourcePoolWithQ(this, listOf(p1, p2), name = "Pool")
        val truck = MovableResourceWithQ(this, locA, speed, name = "Truck")
        val t1 = MovableResource(this, locA, speed, "T1")
        val t2 = MovableResource(this, locA, speed, "T2")
        val fleet = MovableResourcePoolWithQ(this, listOf(t1, t2), speed, RequestQ(this, "FleetQ"), "Fleet")

        val log = mutableListOf<String>()
        val setups = mutableListOf<IModel.() -> Unit>()

        inner class Actor(val tag: String, val body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            val p: KSLProcess = process("p-$tag") { body() }

            /** Moves this actor's waiting request from [from]'s queue to [target]. */
            fun moveRequest(from: ResourceWithQ, target: ResourceWithQ) {
                val request = from.myWaitingQ.toList().first { it.entity === this }
                moveRequestToResource(request as ResourceRequest, from.myWaitingQ, target, RESUME_PRIORITY, false)
            }
        }

        fun actor(tag: String, body: suspend KSLProcessBuilder.() -> Unit) = Actor(tag, body).also { activate(it.p) }

        fun at(t: Double, action: () -> Unit) = actor("at$t") { delay(t); action() }

        fun job(tag: String, res: ResourceWithQ, from: Double, hold: Double) = actor(tag) {
            delay(from)
            val a = seize(res)
            log.add("$tag got@$time")
            delay(hold)
            release(a)
            log.add("$tag done@$time")
        }

        override fun initialize() {
            log.clear()
            for (s in setups) s()
        }

        override fun replicationEnded() {
            LoansAndFreezesCheck.check(r, r2, p1, p2, truck, t1, t2)
        }
    }

    private fun run(
        reps: Int = 1,
        length: Double = 100.0,
        warmUp: Double = 0.0,
        sink: AnimationSink? = null,
        configure: IModel.() -> Unit = {},
        setup: IModel.() -> Unit
    ): Pair<Model, IModel> {
        val model = Model("FailureInteractions${System.nanoTime()}", autoCSVReports = false)
        if (sink != null) model.animationSink = sink
        val m = IModel(model)
        m.configure()
        m.setups.add(setup)
        model.numberOfReplications = reps
        model.lengthOfReplication = length
        model.lengthOfReplicationWarmUp = warmUp
        model.simulate()
        return model to m
    }

    private class CollectingSink : AnimationSink {
        val events = mutableListOf<AnimationEvent>()
        override val isActive: Boolean = true
        override fun emit(event: AnimationEvent) { events.add(event) }
    }

    // ---------- pools and movable resources ----------

    @Test
    @DisplayName("a holder through a ResourcePool whose member fails under RESUME: it freezes until the repair")
    fun pooledHolderFreezes() {
        val (_, m) = run(configure = { p1.interruptRule = InterruptRule.RESUME }) {
            actor("H") { val pa = seize(pool, 2); delay(10.0); release(pa); log.add("H done@$time") }
            at(2.0) { p1.fail() }
            at(5.0) { p1.repair() }
        }
        assertEquals(listOf("H done@13.0"), m.log)
    }

    @Test
    @DisplayName("a holder through a ResourcePool whose member fails under FINISH: its release returns every member, the failed one kept from the queue")
    fun pooledReleaseUnderFinish() {
        val (_, m) = run {
            actor("H") { val pa = seize(pool, 2); delay(10.0); release(pa); log.add("H done@$time") }
            actor("W1") { delay(1.0); val pa = seize(pool); log.add("W1 got ${pa.allocations.single().myResource.name}@$time"); delay(50.0); release(pa) }
            actor("W2") { delay(1.5); val pa = seize(pool); log.add("W2 got ${pa.allocations.single().myResource.name}@$time"); release(pa) }
            at(2.0) { p1.fail() }
            at(20.0) { p1.repair() }
        }
        assertEquals(listOf("H done@10.0", "W1 got P2@10.0", "W2 got P1@20.0"), m.log)
    }

    @Test
    @DisplayName("a holder through a MovableResourcePool whose vehicle fails under RESUME: it freezes until the repair")
    fun movablePoolHolderFreezes() {
        val (_, m) = run(configure = { t1.interruptRule = InterruptRule.RESUME; t2.interruptRule = InterruptRule.RESUME }) {
            actor("H") {
                entity.currentLocation = locA
                val a = seize(fleet)
                log.add("H got@$time")
                delay(10.0); release(a); log.add("H done@$time")
            }
            at(2.0) { (if (t1.isBusy) t1 else t2).fail() }
            at(5.0) { (if (t1.isFailed) t1 else t2).repair() }
        }
        assertEquals(listOf("H got@0.0", "H done@13.0"), m.log)
    }

    @Test
    @DisplayName("a MovableResource failing under RESUME while its holder moves it: the trip finishes, the next delay begins frozen")
    fun movableFailsWhileMoving() {
        val (_, m) = run(configure = { truck.interruptRule = InterruptRule.RESUME }) {
            actor("H") {
                entity.currentLocation = locA
                val a = seize(truck)
                moveWith(truck, locB)
                log.add("H arrived@$time")
                delay(5.0); release(a); log.add("H done@$time")
            }
            at(2.0) { truck.fail() }
            at(12.0) { truck.repair() }
        }
        assertEquals(listOf("H arrived@10.0", "H done@17.0"), m.log)
    }

    // ---------- capacity changes ----------

    @Test
    @DisplayName("a WAIT decrease pending a release completes while failed; a capacity change while failed applies underneath")
    fun capacityChangesWhileFailed() {
        val (_, m) = run(configure = { r2.capacityChangeRule = CapacityChangeRule.WAIT }) {
            job("A", r2, 0.0, 10.0)
            job("B", r2, 0.0, 6.0)
            at(1.0) { r2.changeCapacity(r2.CapacityChangeNotice(capacity = 1)) }
            at(2.0) { r2.fail() }
            at(6.5) { log.add("6.5 cap=${r2.capacity} busy=${r2.numBusy} ${r2.state.name}") }
            job("N", r2, 6.8, 1.0)
            at(7.0) { r2.changeCapacity(r2.CapacityChangeNotice(capacity = 2)); log.add("7 cap=${r2.capacity} ${r2.state.name}") }
            at(8.0) { r2.repair() }
        }
        assertEquals(
            listOf(
                "A got@0.0", "B got@0.0", "B done@6.0", "6.5 cap=1 busy=1 R2_Failed", "7 cap=2 R2_Failed",
                "N got@8.0", "N done@9.0", "A done@10.0"
            ),
            m.log
        )
    }

    // ---------- the timed seizes ----------

    @Test
    @DisplayName("a seizeOrTimeout waiting through a failure: it times out before the repair, or is served at it")
    fun timedSeizeThroughAFailure() {
        for (timeout in listOf(3.0, 20.0)) {
            val (_, m) = run {
                job("W", r, 0.0, 10.0)
                actor("T") {
                    delay(1.0)
                    val res = seizeOrTimeout(r, timeout = timeout)
                    log.add("T ${if (res is WaitResult.TimedOut) "timed out" else "got"}@$time")
                    res.valueOrNull()?.let { release(it) }
                }
                at(2.0) { r.fail() }
                at(12.0) { r.repair() }
            }
            val expected = if (timeout == 3.0) "T timed out@4.0" else "T got@12.0"
            assertTrue(expected in m.log, "timeout $timeout: ${m.log}")
        }
    }

    @Test
    @DisplayName("a seize trigger in waitForFirst on a failed resource is claimed at the repair")
    fun seizeTriggerOnAFailedResource() {
        val (_, m) = run {
            job("H", p1, 0.0, 10.0)
            at(0.5) { r.fail() }
            actor("F") {
                delay(1.0)
                val rt = r.seizeTrigger()
                val pt = p1.seizeTrigger()
                val res = waitForFirst(rt, pt)
                log.add("F won ${if (res.trigger === rt) "R" else "P1"}@$time")
                if (res.firedBy(rt)) release(res.valueOf(rt))
                if (res.firedBy(pt)) release(res.valueOf(pt))
            }
            at(6.0) { r.repair() }
        }
        assertTrue("F won R@6.0" in m.log, m.log.toString())
    }

    @Test
    @DisplayName("a timed seize woken with a promise whose resource fails before it resumes re-waits to its original deadline")
    fun wokenTimedSeizeWhoseUnitFails() {
        for (repairAt in listOf(8.0, 20.0)) {
            val (_, m) = run {
                actor("W") { val a = seize(r); delay(5.0); release(a); r.fail() }
                actor("T") {
                    delay(1.0)
                    val res = seizeOrTimeout(r, timeout = 10.0)
                    log.add("T ${if (res is WaitResult.TimedOut) "timed out" else "got"}@$time")
                    res.valueOrNull()?.let { delay(1.0); release(it) }
                }
                at(repairAt) { r.repair(); log.add("repaired busy=${r.numBusy}") }
            }
            val expected = if (repairAt == 8.0) listOf("repaired busy=0", "T got@8.0") else listOf("T timed out@11.0", "repaired busy=0")
            assertEquals(expected, m.log, "repair at $repairAt")
        }
    }

    @Test
    @DisplayName("a timed seize timing out while its resource is failed offers nothing; the repair serves the waiter once")
    fun timeoutWhileFailedThenRepair() {
        val (_, m) = run {
            job("W", r, 0.0, 3.0)
            actor("T") {
                delay(1.0)
                val res = seizeOrTimeout(r, timeout = 4.0)
                log.add("T ${if (res is WaitResult.TimedOut) "timed out" else "got"}@$time busy=${r.numBusy}")
            }
            job("N", r, 1.5, 2.0)
            at(2.0) { r.fail() }
            at(8.0) { r.repair() }
        }
        assertEquals(listOf("W got@0.0", "W done@3.0", "T timed out@5.0 busy=0", "N got@8.0", "N done@10.0"), m.log)
        assertEquals(1, m.r.numTimesSeized - 1, "only W and N were allocated")
    }

    // ---------- the routes a request takes ----------

    @Test
    @DisplayName("requests in several queues, under both notification rules, wait through a failure and are served at the repair")
    fun severalQueuesBothRules() {
        for (rule in listOf(DefaultRequestQueueNotificationRule, RegistrationOrderNotificationRule)) {
            val (_, m) = run(configure = { r2.initialRequestQueueNotificationRule = rule }) {
                at(0.5) { r2.fail() }
                actor("A") { delay(1.0); val a = seize(r2, queue = q2); log.add("A got@$time"); delay(5.0); release(a) }
                job("B", r2, 2.0, 5.0)
                at(4.0) { r2.repair() }
            }
            // the default rule offers by time of entry (A first); registration order offers R2's own queue first
            val first = if (rule === DefaultRequestQueueNotificationRule) listOf("A got@4.0", "B got@4.0") else listOf("B got@4.0", "A got@4.0")
            assertEquals(first + "B done@9.0", m.log, "rule $rule")
        }
    }

    @Test
    @DisplayName("a request moved to a failed resource waits there and is served at the repair")
    fun requestMovedToAFailedResource() {
        val (_, m) = run {
            job("H", r2, 0.0, 50.0)
            job("H2", r2, 0.0, 50.0)
            at(0.5) { r.fail() }
            val mover = actor("M") { delay(1.0); val a = seize(r2); log.add("M got ${a.myResource.name}@$time"); release(a) }
            at(2.0) { mover.moveRequest(r2, r) }
            at(6.0) { r.repair() }
        }
        assertTrue("M got R@6.0" in m.log, m.log.toString())
    }

    // ---------- an agent resource's statechart ----------

    private class AgentScenario(parent: ModelElement) : AgentModel(parent, "agentFailure") {
        var seen = 0
        val log = mutableListOf<String>()
        val resource: AgentResource = AgentResource(this, "watch", capacity = 1).apply {
            statechart {
                initial("running")
                state("running") {
                    onMessage<AgentMessage.Inform<String>> { seen += 1; log.add("seen@${agent.currentTime} failed=${this@apply.isFailed}") }
                }
            }
        }

        inner class Notifier : Agent("notifier") {
            val script: KSLProcess = process(isDefaultProcess = true) {
                delay(1.0); resource.fail()
                delay(0.5); sendMessage(AgentMessage.Inform(this@Notifier, "tick"), resource.mailbox)
                delay(1.0); resource.repair()
                delay(1.0); sendMessage(AgentMessage.Inform(this@Notifier, "tick"), resource.mailbox)
            }
        }

        override fun initialize() {
            super.initialize()
            seen = 0
            log.clear()
            activate(Notifier().script)
        }
    }

    @Test
    @DisplayName("an AgentResource with a statechart keeps reacting while it is failed")
    fun agentResourceStatechartWhileFailed() {
        val model = Model("AgentFailure${System.nanoTime()}", autoCSVReports = false)
        val s = AgentScenario(model)
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()
        assertEquals(listOf("seen@1.5 failed=true", "seen@3.5 failed=false"), s.log)
    }

    // ---------- warm-up and replications ----------

    @Test
    @DisplayName("declared failures across warm-up and replications: restarted each replication, statistics reset at warm-up, nothing carried over")
    fun failuresAcrossWarmUpAndReplications() {
        val (model, m) = run(reps = 2, length = 99.0, warmUp = 49.0, configure = {
            r.failures(ConstantRV(8.0), ConstantRV(2.0))
        }) {
            at(1.0) { log.add("start failed=${r.isFailed}") }
            at(98.5) { log.add("end failed=${r.isFailed}") }
        }
        // the last replication: not failed at its start though the one before ended failed
        assertEquals(listOf("start failed=false", "end failed=true"), m.log)
        // after the warm-up at 49: failed 49 to 50 (a failure from 48), 58 to 60, ..., 88 to 90, and 98 to 99:
        // 10 of 50 time units; 5 failures begin after it
        val p = (model.getModelElement("R:PTimeFailed") as Response).acrossReplicationStatistic
        val n = (model.getModelElement("R:NumFailures") as Counter).acrossReplicationStatistic
        assertEquals(0.2, p.average, 1e-12)
        assertEquals(0.0, p.variance, 1e-12)
        assertEquals(5.0, n.average, 1e-12)
    }

    // ---------- the animation trace ----------

    @Test
    @DisplayName("a failure and a repair reach the trace as state changes, and the trace reader loads them")
    fun failureInTheTrace() {
        val sink = CollectingSink()
        run(sink = sink, configure = { r.addAllocationListener(ResourceAnimationEmitter(r)) }) {
            at(3.0) { r.fail() }
            at(5.0) { r.repair() }
        }
        val states = sink.events.filterIsInstance<AnimationEvent.ResourceStateChanged>().filter { it.resourceName == "R" }
        assertEquals(listOf("3.0 R_Failed", "5.0 R_Idle"), states.map { "${it.simTime} ${it.state}" })
        val trace = Files.createTempFile("failure", ".atf")
        try {
            Files.writeString(
                trace,
                AnimationTraceHeader().encodeToLine() + "\n" + states.joinToString("\n") { AnimationEvent.encodeToLine(it) } + "\n"
            )
            val (_, events) = TraceFileReader.readAll(trace)
            assertEquals(states, events)
        } finally {
            Files.deleteIfExists(trace)
        }
    }
}
