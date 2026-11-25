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
import ksl.modeling.entity.Allocation
import ksl.modeling.entity.AllocationListenerIfc
import ksl.modeling.entity.BlockingQueue
import ksl.modeling.entity.CapacityChangeRule
import ksl.modeling.entity.DelayResult
import ksl.modeling.entity.InterruptRule
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.entity.RequestQ
import ksl.modeling.entity.RequestSelectionRuleIfc
import ksl.modeling.entity.ResourcePoolAllocation
import ksl.modeling.entity.ResourcePoolWithQ
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.entity.Signal
import ksl.modeling.entity.WaitTrigger
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.observers.ObserverIfc

/**
 *  A small model for exploring same-instant orderings: a resource R (its queue with or without a selection
 *  rule), a resource R2 to move requests to, a resource R3 that forms a pool with R for the bases that use one,
 *  a signal, a blocking queue and a mailbox. A base scenario ([SameInstant.Base]) places its actors so that
 *  several decisions fall at one instant; a disruption ([SameInstant.Disruption]) is then applied at one point of
 *  the run: after a given event, or inside a given allocation-listener notice of R or mailbox-observer notice.
 *  The model records what it can check: who never finished, units still busy, requests still queued, waits that
 *  ended late, a request or waiter left beside what it waits for once an instant is over, and items or messages
 *  lost.
 */
internal class SameInstantModel(
    parent: ModelElement,
    val base: SameInstant.Base,
    val variant: SameInstant.Variant,
    val disruption: SameInstant.Disruption?,
    val position: SameInstant.Position?
) : AgentModel(parent, "same") {

    val rq = RequestQ(this, "RQ").also { q ->
        if (variant.rule) {
            // serves, in queue order, every request the free units can cover
            q.requestSelectionRule = RequestSelectionRuleIfc { resource, requestQ ->
                var left = resource.numAvailableUnits
                requestQ.filter { r -> r.resource === resource && (r.amountRequested <= left).also { if (it) left -= r.amountRequested } }
            }
        }
    }
    val r = ResourceWithQ(this, "R", capacity = base.capacity, queue = rq)
    val r2 = ResourceWithQ(this, "R2", capacity = 2)
    val r3 = ResourceWithQ(this, "R3", capacity = 1)
    val pool: ResourcePoolWithQ? = if (base.pool) ResourcePoolWithQ(this, listOf(r, r3), name = "P") else null
    val signal = Signal(this, "S")
    val bin = BlockingQueue<Item>(this, name = "Bin")

    inner class Hub : Agent("hub")

    val hub = Hub()

    inner class Item(val tag: String) : QObject()

    val problems = mutableListOf<String>()
    val log = ArrayDeque<String>()
    val actors = LinkedHashMap<String, Actor>()
    val done = mutableSetOf<String>()
    val terminated = mutableSetOf<String>()
    var sent = 0
    var received = 0
    var delivered = 0
    var consumed = 0

    /** Labels of the events executed, in order; and of R's listener notices. */
    val events = mutableListOf<Pair<Double, String>>()
    val notices = mutableListOf<Pair<Double, String>>()

    /** Whether the disruption was applied, and whether it applied at all where it was asked for. */
    var applied = false
    var notApplicable = false

    init {
        r.capacityChangeRule = variant.changeRule
        r.interruptRule = variant.interruptRule
        r.addAllocationListener(object : AllocationListenerIfc {
            override fun allocate(allocation: Allocation) = notice("allocate ${allocation.myEntity.name}")
            override fun deallocate(allocation: Allocation) = notice("deallocate ${allocation.myEntity.name}")
            override fun failureStateChanged(resource: ksl.modeling.entity.Resource) = notice("failure ${resource.isFailed}")
            override fun unitsLent(lender: Allocation, borrower: Allocation, amount: Int) =
                notice("lent ${lender.myEntity.name}->${borrower.myEntity.name}")
            override fun unitsReturned(lender: Allocation, amount: Int) = notice("returned ${lender.myEntity.name}")
        })
        hub.mailbox.attachObserver(object : MailboxObserver<AgentMessage> {
            override fun onMessageDelivered(message: AgentMessage, currentSize: Int) = notice("delivered $currentSize")
            override fun onMessageConsumed(message: AgentMessage, currentSize: Int) = notice("consumed $currentSize")
        })
        executive.attachObserver(object : ObserverIfc<KSLEvent<*>?> {
            var lastTime = -1.0
            override fun onChange(newValue: KSLEvent<*>?) {
                val e = newValue ?: return
                when (executive.status) {
                    ksl.simulation.Executive.Status.BEFORE_EVENT -> {
                        if (e.time > lastTime) {
                            if (lastTime >= 0.0) instantEnded(lastTime)
                            lastTime = e.time
                        }
                    }
                    ksl.simulation.Executive.Status.AFTER_EVENT -> {
                        events.add(e.time to "${e.name ?: "event"} p=${e.priority}${e.entity?.let { " ${it.name}" } ?: ""}")
                        val p = position
                        if (p is SameInstant.Position.AfterEvent && p.index == events.size - 1) inject("after event ${events.last()}")
                    }
                    else -> {}
                }
            }
        })
    }

    private fun notice(what: String) {
        notices.add(time to what)
        val p = position
        if (p is SameInstant.Position.InNotice && p.index == notices.size - 1) inject("in notice $what")
    }

    private fun inject(where: String) {
        val d = disruption ?: return
        if (!d.applicable(this)) {
            notApplicable = true
            return
        }
        note("DISRUPT ${d.name} $where")
        applied = true
        try {
            d.apply(this)
        } catch (e: RuntimeException) {
            problems.add("the disruption ${d.name}, applicable by its pre-checks, threw: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun note(s: String) {
        log.addLast("$time $s")
        if (log.size > 80) log.removeFirst()
    }

    /** Checks made once every event of an instant has run, when nothing transient is left. */
    private fun instantEnded(t: Double) {
        val next = bin.selectNextRequest()
        if (next != null) problems.add("$t: a request of ${next.receiver.name} at Bin is fillable and selected, yet waits")
        if (hub.mailbox.size > 0) {
            val waiting = actors.values.filter { it.waitsOnMail && it.isSuspended }
            if (waiting.isNotEmpty()) problems.add("$t: ${waiting.map { it.name }} wait on the mailbox, which holds ${hub.mailbox.size}")
        }
        for (a in actors.values) {
            if (a.isSuspended && t > a.deadline + 1e-9) problems.add("$t: ${a.name} still waits past its deadline ${a.deadline}")
        }
    }

    fun at(dt: Double, action: () -> Unit) {
        schedule(EventActionIfc<Nothing> { action() }, dt)
    }

    inner class Actor(tag: String, prio: Int, private val body: suspend KSLProcessBuilder.(Actor) -> Unit) : Entity(tag) {
        var waitsOnMail = false
        var deadline = Double.POSITIVE_INFINITY

        init {
            priority = prio
        }

        val p: KSLProcess = process("p-$tag") {
            body(this@Actor)
            done.add(tag)
            note("$tag done")
        }

        /** This actor's request in R's queue, if it has one. */
        val queuedRequest: ResourceRequest?
            get() = rq.firstOrNull { it.entity === this } as? ResourceRequest

        fun canMove(): Boolean = queuedRequest?.let { it.resource === r && canMoveRequestToResource(it) } ?: false

        fun moveToR2() {
            val q = checkNotNull(queuedRequest)
            moveRequestToResource(q, rq, r2, RESUME_PRIORITY)
        }

        /** Seizes [amount] of [res], works for [work], and releases. */
        suspend fun KSLProcessBuilder.use(amount: Int, work: Double) {
            val a = seize(r, amount)
            note("$name got R")
            delay(work)
            release(a)
        }

        /** Seizes one unit of the pool, works for [work], and releases. */
        suspend fun KSLProcessBuilder.usePool(work: Double) {
            val a = seize(checkNotNull(pool), 1)
            note("$name got the pool")
            delay(work)
            release(a)
        }

        suspend fun KSLProcessBuilder.preemptAndWork(rule: InterruptRule, work: Double) {
            val a = preempt(r, 1, rule = rule)
            note("$name preempted")
            delay(work)
            release(a)
        }

        /** Works for [work] on an allocation of R, reacting to every interruption by waiting for the units back. */
        suspend fun KSLProcessBuilder.reactingWork(work: Double) {
            val a = seize(r)
            var left = work
            var turns = 0
            while (true) {
                val result = delayOrInterrupt(left)
                if (result is DelayResult.Completed) break
                result as DelayResult.Interrupted
                left = if (result.rule == InterruptRule.RESTART) work else result.timeRemaining
                timed(listOf(result.by.restoredTrigger()), 50.0)
                if (++turns > 50) {
                    problems.add("$name: 50 interruptions"); break
                }
            }
            release(a)
        }

        /** A wait for the first of [triggers], checked against its deadline, with each kind of value used. */
        suspend fun KSLProcessBuilder.timed(triggers: List<WaitTrigger<*>>, timeout: Double) {
            deadline = time + timeout
            waitsOnMail = mailTriggers.any { m -> triggers.any { it === m } }
            val result = waitForFirst(triggers, timeout = timeout)
            waitsOnMail = false
            val end = deadline
            deadline = Double.POSITIVE_INFINITY
            note("$name wait ended by ${result.trigger ?: "timeout"}")
            if (time > end + 1e-9) problems.add("$name: the wait ended at $time, after its deadline $end")
            if (result.timedOut && Math.abs(time - end) > 1e-9) problems.add("$name: timed out at $time, not at its deadline $end")
            val v = result.trigger?.let { result.valueOrNull(it) }
            when (v) {
                is Allocation -> {
                    delay(1.0); release(v)
                }
                is ResourcePoolAllocation -> {
                    delay(1.0); release(v)
                }
                is List<*> -> received += v.size
                is AgentMessage -> consumed++
                else -> {}
            }
        }
    }

    /** The mailbox triggers made, so a wait can tell it includes one. */
    val mailTriggers = mutableListOf<WaitTrigger<*>>()

    fun mailTrigger(): WaitTrigger<AgentMessage> = hub.mailbox.trigger().also { mailTriggers.add(it) }

    fun actor(tag: String, priority: Int = 5, start: Double = 0.0, body: suspend KSLProcessBuilder.(Actor) -> Unit): Actor {
        val a = Actor(tag, priority) { me ->
            if (start > 0.0) delay(start)
            body(me)
        }
        actors[tag] = a
        activate(a.p)
        return a
    }

    fun deliver() {
        delivered++
        hub.mailbox.deliver(AgentMessage.Inform(hub, "m$delivered"))
    }

    override fun initialize() {
        super.initialize()
        base.setup(this)
        actor("cleanup", 0, 20.0) {
            if (r.isFailed) r.repair()
            if (r.isOffShift) r.goOnShift()
            // a notice's end does not restore the capacity; a decrease held up by the repair may still be pending
            var waited = 0
            do {
                delay(1.0)
            } while (r.isPendingCapacityChange && ++waited < 5)
            if (r.capacity != base.capacity && !r.isPendingCapacityChange) r.changeCapacity(r.CapacityChangeNotice(base.capacity))
        }
    }

    override fun replicationEnded() {
        super.replicationEnded()
        for ((name, a) in actors) {
            if (name !in done && name !in terminated) problems.add("$name never finished (suspended=${a.isSuspended}, ${a.currentSuspendType})")
        }
        if (r.numBusy != 0) problems.add("R has ${r.numBusy} busy units at the end")
        if (r2.numBusy != 0) problems.add("R2 has ${r2.numBusy} busy units at the end")
        if (r3.numBusy != 0) problems.add("R3 has ${r3.numBusy} busy units at the end")
        pool?.let { if (it.waitingQ.size != 0) problems.add("the pool's queue holds ${it.waitingQ.size} requests at the end") }
        if (rq.size != 0) problems.add("R's queue holds ${rq.size} requests at the end")
        if (r2.waitingQ.size != 0) problems.add("R2's queue holds ${r2.waitingQ.size} requests at the end")
        if (r.capacity != base.capacity) problems.add("R's capacity is ${r.capacity} at the end, not ${base.capacity}")
        if (sent != received + bin.channelQ.size) problems.add("items: sent $sent, received $received, in the bin ${bin.channelQ.size}")
        if (delivered != consumed + hub.mailbox.size) problems.add("messages: delivered $delivered, consumed $consumed, in the mailbox ${hub.mailbox.size}")
    }

    companion object {
        val CHANGE_RULES = listOf(CapacityChangeRule.IGNORE, CapacityChangeRule.WAIT)
    }
}
