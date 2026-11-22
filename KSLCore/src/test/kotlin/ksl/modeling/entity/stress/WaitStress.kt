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

package ksl.modeling.entity.stress

import ksl.modeling.agent.AgentMessage
import ksl.modeling.agent.AgentModel
import ksl.modeling.agent.AgentSignal
import ksl.modeling.entity.*
import ksl.modeling.queue.Queue
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import java.util.Random

/**
 *  A randomized model of waits for the first of several things: seizes of resources, a pool and a shared
 *  request queue, items from blocking queues, signals, an agent's mailbox and an agent signal, raced against
 *  timeouts, terminations, capacity cuts, failures, and holders that watch their allocation's interrupted and
 *  restored triggers. [features] chooses which appear. Every tick it records in [problems] a fillable request
 *  left waiting, a wait past its deadline, a mailbox wait beside a message, items not conserved, or a job
 *  that still reports waiting after its wait ended. Times are integers so that many events tie.
 */
internal class WaitStressModel(
    parent: ModelElement,
    val seed: Long,
    val features: Set<String>,
    val nJobs: Int = 10,
    val steps: Int = 8
) : AgentModel(parent, "wstress") {

    val rnd = Random(seed)
    val res: List<ResourceWithQ> = listOf(
        ResourceWithQ(this, "R0", capacity = 1),
        ResourceWithQ(this, "R1", capacity = 2),
        ResourceWithQ(this, "R2", capacity = 1)
    )
    val pool = ResourcePoolWithQ(this, listOf(res[1], res[2]), name = "P")
    val sharedQ = RequestQ(this, "SharedQ")
    val bins: List<BlockingQueue<Item>> = listOf(
        BlockingQueue<Item>(this, if ("smallbin" in features) 2 else Int.MAX_VALUE, "Bin0"),
        BlockingQueue<Item>(this, Int.MAX_VALUE, "Bin1")
    )
    val signals = listOf(Signal(this, "S0"), Signal(this, "S1", Queue.Discipline.LIFO))
    val agentSignal = AgentSignal("AS")

    inner class Hub : Agent("hub")

    val hub = Hub()

    inner class Item(val tag: String) : QObject() {
        override fun toString(): String = tag
    }

    var sent = IntArray(2)
    var received = IntArray(2)
    val log = ArrayDeque<String>()
    val jobs = mutableListOf<Job>()
    val problems = mutableListOf<String>()

    init {
        if ("ff" in features) bins.forEach { it.receiverRequestSelector = it.FirstFillableRequest() }
        if ("ranked" in features) {
            res[0].myWaitingQ.initialDiscipline = Queue.Discipline.RANKED
            res[1].myWaitingQ.initialDiscipline = Queue.Discipline.LIFO
        }
        if ("rule" in features) {
            // serves only the head request of the queue for the resource
            res[0].myWaitingQ.requestSelectionRule = RequestSelectionRuleIfc { resource, q ->
                val first = q.firstOrNull { it.resource == resource }
                if (first == null) emptyList() else listOf(first)
            }
        }
        if ("resume" in features) {
            res[1].interruptRule = InterruptRule.RESUME
            res[2].interruptRule = InterruptRule.RESTART
        }
    }

    fun note(s: String) {
        log.addLast("$time $s")
        if (log.size > 60) log.removeFirst()
    }

    inner class Job(val tag: String, val script: List<IntArray>) : Entity(tag) {
        var terminated = false
        var waitMask = 0
        var waitDeadline = Double.POSITIVE_INFINITY

        val p: KSLProcess = process("job-$tag") {
            for (step in script) {
                delay(step[1].toDouble())
                when (step[0]) {
                    0 -> waitStep(step)
                    1 -> {
                        val b = step[2] % 2
                        note("$tag send bin$b")
                        send(Item("${if (step[3] % 2 == 0) "r" else "b"}-$tag"), bins[b])
                        sent[b]++
                    }
                    2 -> {
                        val s = signals[step[2] % 2]
                        note("$tag signal ${s.name} kind=${step[3] % 4}")
                        when (step[3] % 4) {
                            0 -> s.signalAll()
                            1 -> s.signalFirst()
                            2 -> s.signal(0..1)
                            else -> s.signalLast()
                        }
                    }
                    3 -> {
                        note("$tag deliver"); hub.mailbox.deliver(AgentMessage.Inform(hub, "m-$tag"))
                    }
                    4 -> {
                        note("$tag fire"); agentSignal.fire()
                    }
                    5 -> {
                        val r = res[step[2] % 3]
                        val amt = if (r === res[1]) 1 + step[3] % 2 else 1
                        note("$tag seize ${r.name} $amt")
                        val a = seize(r, amt)
                        delay((step[4] % 3).toDouble())
                        release(a)
                    }
                    6 -> {
                        val b = step[2] % 2
                        note("$tag waitForItems bin$b")
                        val got = if (step[3] % 2 == 0) waitForItems(bins[b], 1 + step[4] % 2) else
                            waitForAnyItems(bins[b], { it.tag.startsWith("r") })
                        received[b] += got.size
                    }
                    7 -> {
                        val victim = jobs[step[2] % jobs.size]
                        if (victim !== this@Job && victim.isSuspended && "term" in features) {
                            note("$tag terminates ${victim.tag} (type ${victim.currentSuspendType})")
                            victim.terminated = true
                            victim.terminateProcess()
                        }
                    }
                    8 -> holdAndWatch(step)
                }
            }
        }

        private suspend fun KSLProcessBuilder.holdAndWatch(step: IntArray) {
            val r = res[1 + step[2] % 2]
            note("$tag seize-watch ${r.name}")
            val a = seize(r)
            val it = a.interruptedTrigger()
            val sg = signals[0].trigger()
            val w = waitForFirst(it, sg, timeout = (1 + step[3] % 3).toDouble())
            note("$tag watch ended ${w.trigger ?: "timeout"}")
            if (w.firedBy(it)) {
                val rt = a.restoredTrigger()
                val bt = bins[1].itemsTrigger(1)
                val back = waitForFirst(rt, bt, timeout = 2.0)
                back.valueOrNull(bt)?.let { got -> received[1] += got.size }
                note("$tag restore wait ended ${back.trigger ?: "timeout"}")
            }
            val d = delayOrInterrupt((step[4] % 3).toDouble(), uses = listOf(a))
            note("$tag delayOrInterrupt $d")
            release(a)
        }

        private suspend fun KSLProcessBuilder.waitStep(step: IntArray) {
            val triggers = mutableListOf<WaitTrigger<*>>()
            val seizes = mutableListOf<WaitTrigger<Allocation>>()
            val poolSeizes = mutableListOf<WaitTrigger<ResourcePoolAllocation>>()
            val items = mutableListOf<Pair<Int, WaitTrigger<List<Item>>>>()
            val mask = step[2]
            val order = step[5]
            if (mask and 1 != 0) res[0].seizeTrigger(recordRenegedWait = step[3] % 2 == 0).also { seizes.add(it); triggers.add(it) }
            if (mask and 2 != 0) res[1].seizeTrigger(1 + step[3] % 2).also { seizes.add(it); triggers.add(it) }
            if (mask and 4 != 0) pool.seizeTrigger(1 + step[4] % 2).also { poolSeizes.add(it); triggers.add(it) }
            if (mask and 8 != 0) bins[0].itemsTrigger(1 + step[3] % 2, { it.tag.startsWith("r") }).also { items.add(0 to it); triggers.add(it) }
            if (mask and 16 != 0) bins[0].anyItemsTrigger({ it.tag.startsWith("b") }).also { items.add(0 to it); triggers.add(it) }
            if (mask and 32 != 0) bins[1].itemsTrigger(1).also { items.add(1 to it); triggers.add(it) }
            if (mask and 64 != 0) signals[step[3] % 2].trigger(waitPriority = if (step[4] % 2 == 0) null else 1 + step[4] % 3).also { triggers.add(it) }
            if (mask and 128 != 0) hub.mailbox.trigger().also { triggers.add(it) }
            if (mask and 256 != 0) agentSignal.trigger().also { triggers.add(it) }
            if (mask and 512 != 0) res[2].seizeTrigger(queue = sharedQ).also { seizes.add(it); triggers.add(it) }
            if (mask and 1024 != 0) res[0].seizeTrigger(queue = sharedQ).also { seizes.add(it); triggers.add(it) }
            if (triggers.isEmpty()) return
            // a rotation of the argument order
            val rotated = triggers.indices.map { triggers[(it + order) % triggers.size] }
            val timeout = when (step[4] % 5) {
                0 -> 0.0; 1 -> 1.0; 2 -> 2.0; 3 -> 3.0; else -> Double.POSITIVE_INFINITY
            }
            val tp = if (step[3] % 3 == 0) 1 else TIMEOUT_PRIORITY
            note("$tag waitForFirst $rotated timeout=$timeout tp=$tp")
            waitMask = mask
            waitDeadline = time + timeout
            val r = waitForFirst(rotated, timeout = timeout, timeoutPriority = tp)
            waitMask = 0
            waitDeadline = Double.POSITIVE_INFINITY
            note("$tag wait ended by ${r.trigger ?: "timeout"} waited=${r.timeWaited}")
            if (isWaitingForFirst) problems.add("$time $tag reports isWaitingForFirst after its wait")
            for ((b, t) in items) r.valueOrNull(t)?.let { received[b] += it.size }
            val held = mutableListOf<Allocation>()
            for (t in seizes) r.valueOrNull(t)?.let { held.add(it) }
            var pa: ResourcePoolAllocation? = null
            for (t in poolSeizes) r.valueOrNull(t)?.let { pa = it }
            if (held.isNotEmpty() || pa != null) {
                delay((step[3] % 3).toDouble())
                held.forEach { release(it) }
                pa?.let { release(it) }
            }
        }
    }

    private fun at(t: Double, priority: Int = KSLEvent.DEFAULT_PRIORITY, action: () -> Unit) {
        schedule(EventActionIfc<Nothing> { action() }, t, priority = priority)
    }

    fun checkStranded(where: String) {
        for (b in 0..1) {
            val next = bins[b].selectNextRequest()
            if (next != null) problems.add("$time $where: bin$b request of ${next.receiver.name} is fillable and selected, yet still waits")
        }
        for (job in jobs) {
            if (job.terminated || !job.isSuspended || job.waitMask == 0) continue
            if (job.currentSuspendType == SuspendType.DELAY) continue
            if (time > job.waitDeadline) problems.add("$time $where: ${job.tag} still waits past its deadline ${job.waitDeadline}")
            if (job.waitMask and 128 != 0 && hub.mailbox.size > 0) problems.add("$time $where: ${job.tag} waits on the mailbox, which holds ${hub.mailbox.size} messages")
        }
    }

    fun checkConservation(where: String) {
        checkStranded(where)
        for (b in 0..1) {
            if (sent[b] != received[b] + bins[b].channelQ.size) {
                problems.add("$time $where: bin$b sent=${sent[b]} received=${received[b]} channel=${bins[b].channelQ.size}")
            }
        }
    }

    override fun initialize() {
        super.initialize()
        sent = IntArray(2); received = IntArray(2)
        log.clear(); jobs.clear()
        val kinds = buildList {
            repeat(5) { add(0) }
            add(1); add(1); add(2); add(3); add(4); add(5); add(6); add(7)
            if ("watch" in features) add(8)
        }
        repeat(nJobs) { j ->
            val script = (0 until steps).map {
                intArrayOf(kinds[rnd.nextInt(kinds.size)], rnd.nextInt(3), rnd.nextInt(2048), rnd.nextInt(12), rnd.nextInt(15), rnd.nextInt(6))
            }
            val job = Job("J$j", script)
            job.priority = 1 + rnd.nextInt(3)
            jobs.add(job)
            activate(job.p)
        }
        if ("cap" in features) {
            repeat(6) {
                val t = 1.0 + rnd.nextInt(18)
                val r = res[1]
                val pr = if (rnd.nextBoolean()) KSLEvent.DEFAULT_PRIORITY else 1
                at(t, pr) {
                    note("cap cut ${r.name}")
                    r.changeCapacity(r.CapacityChangeNotice(capacity = 1, duration = 1.0 + rnd.nextInt(2)))
                }
            }
        }
        if ("fail" in features) {
            repeat(6) {
                val t = 1.0 + rnd.nextInt(18)
                val r = res[1 + rnd.nextInt(2)]
                val d = rnd.nextInt(3).toDouble()
                val pr = if (rnd.nextBoolean()) KSLEvent.DEFAULT_PRIORITY else 1
                at(t, pr) {
                    if (!r.isFailed) {
                        note("fail ${r.name}")
                        r.fail()
                        at(d) { if (r.isFailed) { note("repair ${r.name}"); r.repair() } }
                    }
                }
            }
        }
        for (t in 1..25) at(t.toDouble(), KSLEvent.VERY_LOW_PRIORITY) { checkConservation("tick") }
    }
}
