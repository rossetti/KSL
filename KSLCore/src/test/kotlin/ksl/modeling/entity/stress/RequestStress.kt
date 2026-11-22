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

import ksl.modeling.entity.*
import ksl.modeling.queue.Queue
import ksl.simulation.Model
import ksl.simulation.ModelElement
import java.util.Random

/**
 *  A randomized model of requests for resources and pools: plain, timed and preempting seizes, waits for the
 *  first of two seizes, members of a pool released one at a time, against failures, capacity changes, a shift,
 *  requests moved between queues (also from the callback for a resource that becomes inactive) and requests
 *  removed and terminated. [features] chooses which of these appear; [variant] chooses a shared request queue
 *  and its discipline (1 to 4), a registration-order notification rule (5), or other pool selection and
 *  allocation rules (6). Times are integers so that many events tie.
 */
internal class RequestStressModel(
    parent: ModelElement,
    val seed: Long,
    val features: Set<String>,
    val nJobs: Int = 14,
    val selectionRule: Boolean = false,
    val alwaysWaitStats: Boolean = false,
    val variant: Int = 0
) : ProcessModel(parent, "stress") {

    val rnd = Random(seed)
    val rules = listOf(InterruptRule.FINISH, InterruptRule.RESUME, InterruptRule.RESTART, InterruptRule.RESUME)
    val res: List<ResourceWithQ> = (0..3).map { i ->
        ResourceWithQ(this, "R$i", capacity = 1 + (i % 3)).also {
            it.interruptRule = rules[i]
            if (i == 1) it.capacityChangeRule = CapacityChangeRule.WAIT
        }
    }
    val pools: List<ResourcePoolWithQ> = listOf(
        ResourcePoolWithQ(this, listOf(res[0], res[1]), name = "P0"),
        ResourcePoolWithQ(this, listOf(res[1], res[2]), name = "P1"),
        ResourcePoolWithQ(this, listOf(res[3]), name = "P2")
    )
    val shared: RequestQ? = when (variant) {
        1 -> RequestQ(this, "SharedQ")
        2 -> RequestQ(this, "SharedQ", discipline = Queue.Discipline.LIFO)
        3 -> RequestQ(this, "SharedQ", discipline = Queue.Discipline.RANKED)
        4 -> RequestQ(this, "SharedQ", discipline = Queue.Discipline.RANDOM)
        else -> null
    }
    val queues: List<RequestQ>
        get() = res.map { it.myWaitingQ } + pools.map { it.myWaitingQ } + listOfNotNull(shared)

    fun qFor(r: ResourceWithQ): RequestQ = shared ?: r.myWaitingQ
    fun qFor(p: ResourcePoolWithQ): RequestQ = shared ?: p.myWaitingQ
    fun selRule(p: ResourcePoolWithQ): ResourceSelectionRuleIfc =
        if (variant == 6) FirstFullyAvailableResource() else p.defaultResourceSelectionRule
    fun allocRule(p: ResourcePoolWithQ): ResourceAllocationRuleIfc =
        if (variant == 6) MostAvailableResourceAllocationRule() else p.defaultResourceAllocationRule

    init {
        if (variant == 5) for (r in res) r.initialRequestQueueNotificationRule = RegistrationOrderNotificationRule
    }

    val log = mutableListOf<String>()
    val held = mutableListOf<Allocation>()
    private fun got(a: Allocation): Allocation {
        held.add(a); return a
    }
    private fun got(a: ResourcePoolAllocation): ResourcePoolAllocation {
        held.addAll(a.allocations); return a
    }
    val jobs = mutableListOf<Job>()

    class Spec(val kind: Int, val arrive: Int, val a: Int, val b: Int, val amt: Int, val hold: Int, val timeout: Int, val pri: Int)

    val specs: List<Spec> = (0 until nJobs).map {
        val kinds = buildList {
            add(0)
            if ("pool" in features) {
                add(1); add(6)
            }
            if ("timed" in features) {
                add(2); if ("pool" in features) add(3); if ("nowff" !in features) add(7)
            }
            if ("preempt" in features) add(4)
            if ("two" in features) add(5)
        }
        Spec(
            kinds[rnd.nextInt(kinds.size)], rnd.nextInt(25), rnd.nextInt(4), rnd.nextInt(4),
            1 + rnd.nextInt(2), 1 + rnd.nextInt(6), rnd.nextInt(6), 2 + rnd.nextInt(2)
        )
    }

    class Disturb(val kind: Int, val at: Int, val r: Int, val x: Int, val d: Int)

    val disturbs: List<Disturb> = (0 until 12).mapNotNull {
        val kinds = buildList {
            if ("fail" in features) add(0)
            if ("cap" in features) add(1)
            if ("shift" in features) add(2)
            if ("move" in features) add(3)
            if ("term" in features) add(4)
        }
        if (kinds.isEmpty()) null else
            Disturb(kinds[rnd.nextInt(kinds.size)], 1 + rnd.nextInt(30), rnd.nextInt(4), rnd.nextInt(8), 1 + rnd.nextInt(6))
    }

    inner class Job(val spec: Spec, val tag: String) : Entity(tag) {
        var done = false
        var terminated = false
        var servedAt = Double.NaN

        init {
            priority = if (spec.kind == 4) 1 else spec.pri
        }

        val p: KSLProcess = process("job") {
            delay(spec.arrive.toDouble())
            val r = res[spec.a]
            val amt = minOf(spec.amt, r.initialCapacity)
            when (spec.kind) {
                0 -> {
                    val a = got(seize(r, amt, queue = qFor(r))); servedAt = time
                    delay(spec.hold.toDouble()); release(a)
                }
                1 -> {
                    val pool = pools[spec.a % pools.size]
                    val a = got(
                        seize(
                            pool, minOf(spec.amt, pool.resources.sumOf { it.initialCapacity }), queue = qFor(pool),
                            resourceSelectionRule = selRule(pool), resourceAllocationRule = allocRule(pool)
                        )
                    ); servedAt = time
                    delay(spec.hold.toDouble()); release(a)
                }
                2 -> {
                    val w = seizeOrTimeout(r, amt, timeout = spec.timeout.toDouble(), queue = qFor(r))
                    if (!w.timedOut) {
                        servedAt = time
                        got(w.value)
                        delay(spec.hold.toDouble()); release(w.value)
                    }
                }
                3 -> {
                    val pool = pools[spec.a % pools.size]
                    val w = seizeOrTimeout(
                        pool, minOf(spec.amt, 2), timeout = spec.timeout.toDouble(), queue = qFor(pool),
                        resourceSelectionRule = selRule(pool), resourceAllocationRule = allocRule(pool)
                    )
                    if (!w.timedOut) {
                        servedAt = time
                        got(w.value)
                        delay(spec.hold.toDouble()); release(w.value)
                    }
                }
                4 -> {
                    val a = got(preempt(r, amt, queue = qFor(r))); servedAt = time
                    delay(spec.hold.toDouble()); release(a)
                }
                5 -> {
                    val a = got(seize(r, amt, queue = qFor(r))); servedAt = time
                    val r2 = res[(spec.a + 1 + spec.b % 3) % 4]
                    val w = seizeOrTimeout(r2, 1, timeout = spec.timeout.toDouble(), queue = qFor(r2))
                    if (!w.timedOut) got(w.value)
                    delay(spec.hold.toDouble())
                    if (spec.b % 2 == 0) releaseAllResources() else {
                        release(a); if (!w.timedOut) release(w.value)
                    }
                }
                6 -> {
                    val pool = pools[spec.a % pools.size]
                    val a = got(
                        seize(
                            pool, minOf(2, pool.resources.sumOf { it.initialCapacity }), queue = qFor(pool),
                            resourceSelectionRule = selRule(pool), resourceAllocationRule = allocRule(pool)
                        )
                    ); servedAt = time
                    delay(spec.hold.toDouble())
                    for (member in a.allocations.toList()) {
                        release(member)
                        delay(1.0)
                    }
                }
                7 -> {
                    val t1 = r.seizeTrigger(amt, queue = qFor(r))
                    val r2 = res[(spec.a + 1 + spec.b % 3) % 4]
                    val t2 = r2.seizeTrigger(1, queue = qFor(r2))
                    val w = waitForFirst(
                        t1, t2,
                        timeout = if (spec.timeout == 0) Double.POSITIVE_INFINITY else spec.timeout.toDouble() + 3
                    )
                    if (!w.timedOut) {
                        servedAt = time
                        val a = got(w.valueOrNull(t1) ?: w.valueOf(t2))
                        delay(spec.hold.toDouble()); release(a)
                    }
                }
            }
            done = true
        }

        override fun resourceBecameInactiveWhileWaitingInQueueWithSeizeRequest(queue: RequestQ, resource: Resource, request: Request) {
            if ("inact" !in features) return
            // the documented use: wait for a different resource instead
            val x = (spec.b + 1 + res.indexOf(resource as ResourceWithQ)) % 4
            val moved = tryMove(request, if (spec.hold % 2 == 0) x else x + 4)
            log.add("$time inactive ${resource.name}: request of $name moved=$moved now for ${request.resource.name}")
        }

        /** Moves [request] to resource or pool [x] when that is allowed; [x] of 4 or more records the wait. */
        fun tryMove(request: Request, x: Int): Boolean {
            if (!isSuspended) return false
            val q = request.queue as? RequestQ ?: return false
            if (request is ResourceRequest) {
                val target = res[x % 4]
                if (target === request.resource || request.amountRequested > target.initialCapacity) return false
                if (!canMoveRequestToResource(request)) return false
                moveRequestToResource(request, q, target, RESUME_PRIORITY, waitStats = alwaysWaitStats || x >= 4)
                return true
            } else if (request is ResourcePoolRequest) {
                val target = pools[x % pools.size]
                if (target === request.resource || request.amountRequested > target.resources.sumOf { it.initialCapacity }) return false
                moveRequestToResourcePool(
                    request, q, target, target.defaultResourceSelectionRule, RESUME_PRIORITY,
                    waitStats = alwaysWaitStats || x >= 4
                )
                return true
            }
            return false
        }
    }

    /** One line per replication of when each job was served and how it ended, then the event log. */
    val perReplication = mutableListOf<String>()

    override fun replicationEnded() {
        perReplication.add(jobs.joinToString { "${it.name}:${it.servedAt}:${it.done}:${it.terminated}" } + " | " + log.joinToString())
    }

    override fun initialize() {
        log.clear()
        held.clear()
        jobs.clear()
        if (selectionRule) {
            // serves the requests for the resource, largest amount first
            for (q in queues) q.requestSelectionRule = RequestSelectionRuleIfc { resource, requestQ ->
                requestQ.filter { it.resource == resource }.sortedByDescending { it.amountRequested }
            }
        }
        specs.forEachIndexed { i, s ->
            val j = Job(s, "J$i"); jobs.add(j); activate(j.p)
        }
        for (d in disturbs) schedule(EventActionIfc<Nothing> { disturb(d) }, d.at.toDouble())
        // every capacity is put back well after the last disturbance, so that nothing is left unable to finish
        if ("cap" in features) schedule(EventActionIfc<Nothing> {
            for (r in res) if (r !== res[2] && r.capacity != r.initialCapacity) r.changeCapacity(r.CapacityChangeNotice(r.initialCapacity))
        }, 200.0)
    }

    private fun disturb(d: Disturb) {
        val r = res[d.r]
        when (d.kind) {
            0 -> if (!r.isFailed) {
                r.fail()
                log.add("$time fail ${r.name}")
                schedule(EventActionIfc<Nothing> { if (r.isFailed) r.repair() }, d.d.toDouble())
            }
            1 -> if (d.r != 2) {
                val cap = d.x % (r.initialCapacity + 2)
                log.add("$time cap ${r.name} -> $cap for ${d.d}")
                // a decrease that would end before a pending one is refused by the library; that is misuse, not a fault
                val refused = r.isPendingCapacityChange && cap < r.capacity && time + d.d <= r.pendingCapacityChangeEndTime
                if (!refused) r.changeCapacity(r.CapacityChangeNotice(cap, d.d.toDouble()))
            }
            2 -> {
                val s = res[2]
                if (!s.isOffShift) {
                    s.goOffShift(); log.add("$time offshift")
                    schedule(EventActionIfc<Nothing> { s.goOnShift() }, d.d.toDouble())
                }
            }
            3 -> {
                val all = queues.flatMap { it.toList() }
                if (all.isNotEmpty()) {
                    val request = all[d.x % all.size]
                    val moved = (request.entity as Job).tryMove(request, d.d + d.x)
                    log.add("$time move request of ${request.entity.name} moved=$moved now for ${request.resource.name}")
                }
            }
            4 -> {
                val all = queues.flatMap { it.toList() }
                if (all.isNotEmpty()) {
                    val request = all[d.x % all.size]
                    val job = request.entity as Job
                    log.add("$time terminate ${job.name}")
                    (request.queue as RequestQ).removeAndTerminate(request, waitStats = alwaysWaitStats || d.d % 2 == 0) {
                        job.terminated = true
                    }
                }
            }
        }
    }

    companion object {
        /**
         *  Runs one scenario; returns null when all is well, or a description of what went wrong: an
         *  exception, a job neither finished nor terminated, busy-unit or seize-count accounting that disagrees
         *  with the allocations made, an allocation still held, a queue whose NumInQ integral differs from its
         *  recorded waits ([checkLittle]), a pool whose busy units are not the sum of its members', or two
         *  replications ([reps] of 2) that differ.
         */
        fun runOne(
            seed: Long, features: Set<String>, selectionRule: Boolean = false, nJobs: Int = 14,
            checkLittle: Boolean = false, variant: Int = 0, warmUp: Double = 0.0, reps: Int = 1
        ): String? {
            val model = Model("Stress${System.nanoTime()}", autoCSVReports = false)
            val m = RequestStressModel(model, seed, features, nJobs, selectionRule, checkLittle, variant)
            model.numberOfReplications = reps
            model.lengthOfReplication = 2000.0
            if (warmUp > 0.0) model.lengthOfReplicationWarmUp = warmUp
            try {
                model.simulate()
            } catch (e: Throwable) {
                var c: Throwable = e
                while (c.cause != null && c.cause !== c) c = c.cause!!
                return "EXCEPTION ${c.javaClass.simpleName}: ${c.message?.take(400)}"
            }
            val stuck = m.jobs.filter { !it.done && !it.terminated }
            if (stuck.isNotEmpty()) {
                return "STUCK " + stuck.joinToString { "${it.name}(kind=${it.spec.kind},a=${it.spec.a},amt=${it.spec.amt},state=${it.isWaitingForResource})" } +
                        " | " + m.res.joinToString { "${it.name}:cap=${it.capacity},busy=${it.numBusy},avail=${it.numAvailableUnits},failed=${it.isFailed}" }
            }
            val problems = mutableListOf<String>()
            if (reps == 2 && m.perReplication[0] != m.perReplication[1]) {
                problems.add("REPS differ: " + m.perReplication[0].split(", ").zip(m.perReplication[1].split(", ")).filter { it.first != it.second }.take(4))
            }
            val horizon = 2000.0
            for (r in m.res) {
                val expected = m.held.filter { it.resource === r }.sumOf { it.totalTimeAllocated * it.amountReleased }
                val observed = r.numBusyUnits.withinReplicationStatistic.weightedAverage * horizon
                if (warmUp == 0.0 && "preempt" !in features && Math.abs(expected - observed) > 1e-6) {
                    problems.add("BUSY ${r.name}: allocations held $expected unit-time, NumBusyUnits integral $observed")
                }
                val made = m.held.count { it.resource === r }
                if (warmUp == 0.0 && "preempt" !in features && made.toDouble() != r.seizeCounter.value) {
                    problems.add("SEIZECOUNT ${r.name}: $made allocations, counter ${r.seizeCounter.value}")
                }
                if (m.held.any { it.isAllocated }) problems.add("an allocation is still held at the end")
            }
            if (checkLittle && warmUp == 0.0) for (q in m.queues) {
                val area = q.numInQ.withinReplicationStatistic.weightedAverage * horizon
                val waits = q.timeInQ.withinReplicationStatistic.weightedSum
                if (Math.abs(area - waits) > 1e-6) {
                    problems.add("LITTLE ${q.name}: NumInQ integral $area, sum of TimeInQ $waits (n=${q.timeInQ.withinReplicationStatistic.count})")
                }
            }
            for (pool in m.pools) {
                val agg = pool.numBusyUnits.withinReplicationStatistic.weightedAverage
                val sum = pool.resources.sumOf { it.numBusyUnits.withinReplicationStatistic.weightedAverage }
                if (Math.abs(agg - sum) > 1e-9) problems.add("POOLBUSY ${pool.name}: NumBusy average $agg, members sum $sum")
            }
            if (problems.isNotEmpty()) return problems.joinToString(" ; ")
            return null
        }
    }
}
