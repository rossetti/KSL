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

import ksl.modeling.entity.CapacityChangeRule
import ksl.modeling.entity.InterruptRule
import ksl.modeling.entity.ResourceInvariants
import ksl.simulation.Model

/**
 *  An exhaustive exploration of same-instant orderings. Each base scenario places a few actors so that
 *  several decisions fall at one instant, chiefly the decision to give units, or end a wait, and the resume
 *  that acts on it. Each disruption (a failure, a repair, a shift, a capacity change, a termination, a
 *  removal, a move, an arrival, a signal, a message, an item) is applied once per run, at one point: after
 *  each event of an instant at which two or more events run, or inside each allocation-listener notice of the
 *  resource and each mailbox-observer notice. A point after an event lies between the decision that event made and the event that acts on it;
 *  a point inside a notice is model code run in the middle of the library's own step.
 *
 *  Every run is checked by the invariant mode after every event, by the reference allocator at every offer
 *  in its scope, and at its end for liveness and conservation (see [SameInstantModel]). The runs are
 *  deterministic: a disruption at the k-th event of a run sees the same k events as the baseline.
 */
internal object SameInstant {

    data class Variant(val changeRule: CapacityChangeRule, val interruptRule: InterruptRule, val rule: Boolean) {
        override fun toString() = "$changeRule/$interruptRule${if (rule) "/rule" else ""}"
    }

    val VARIANTS: List<Variant> = buildList {
        for (c in CapacityChangeRule.entries) for (i in listOf(InterruptRule.RESUME, InterruptRule.RESTART)) for (rule in listOf(false, true)) {
            add(Variant(c, i, rule))
        }
    }

    class Base(val name: String, val capacity: Int, val pool: Boolean = false, val setup: SameInstantModel.() -> Unit) {
        override fun toString() = name
    }

    sealed class Position {
        data class AfterEvent(val index: Int) : Position()
        data class InNotice(val index: Int) : Position()
    }

    class Disruption(
        val name: String,
        val kind: String,
        val applicable: (SameInstantModel) -> Boolean,
        val apply: (SameInstantModel) -> Unit
    ) {
        override fun toString() = name
    }

    /** [first], then [second] at the same point if it is still applicable. */
    fun pair(first: Disruption, second: Disruption): Disruption =
        Disruption("${first.name} + ${second.name}", "${first.kind} + ${second.kind}", first.applicable) { m ->
            first.apply(m)
            if (second.applicable(m)) second.apply(m)
        }

    /** The kinds of disruption paired with every disruption, in the pairs' exploration. */
    val PAIRED_KINDS = setOf("fail RESUME", "fail FINISH", "repair", "off shift", "on shift", "cut", "raise",
        "seize arrives", "preempt arrives", "message", "item", "signal")

    /** A capacity decrease the library accepts now: under `IGNORE`, one that ends after a pending decrease. */
    fun canDecrease(m: SameInstantModel, capacity: Int, duration: Double): Boolean {
        val r = m.r
        if (capacity < 0 || capacity >= r.capacity) return false
        val end = r.pendingCapacityChangeEndTime
        return !(r.capacityChangeRule == CapacityChangeRule.IGNORE && r.isPendingCapacityChange &&
                end.isFinite() && m.time + duration <= end)
    }

    fun disruptions(victims: List<String>): List<Disruption> = buildList {
        for (rule in InterruptRule.entries) {
            add(Disruption("fail $rule", "fail $rule", { !it.r.isFailed }) { m ->
                m.r.fail(rule)
                m.at(1.0) { if (m.r.isFailed) m.r.repair() }
            })
        }
        add(Disruption("repair", "repair", { it.r.isFailed }) { it.r.repair() })
        add(Disruption("off shift", "off shift", { !it.r.isOffShift }) { m ->
            m.r.goOffShift()
            m.at(1.0) { if (m.r.isOffShift) m.r.goOnShift() }
        })
        add(Disruption("on shift", "on shift", { it.r.isOffShift }) { it.r.goOnShift() })
        add(Disruption("cut by 1 for 1", "cut", { canDecrease(it, it.r.capacity - 1, 1.0) }) { m ->
            m.r.changeCapacity(m.r.CapacityChangeNotice(m.r.capacity - 1, 1.0))
        })
        add(Disruption("raise by 1 for 1", "raise", { true }) { m ->
            m.r.changeCapacity(m.r.CapacityChangeNotice(m.r.capacity + 1, 1.0))
        })
        for (v in victims) {
            add(Disruption("terminate $v", "terminate", { m ->
                val a = m.actors.getValue(v)
                v !in m.terminated && a.currentProcess != null && a.isSuspended
            }) { m ->
                m.terminated.add(v)
                m.actors.getValue(v).terminateProcess()
            })
            add(Disruption("removeAndTerminate $v", "removeAndTerminate", { m ->
                val a = m.actors.getValue(v)
                v !in m.terminated && a.queuedRequest != null && a.currentProcess?.isRunning != true
            }) { m ->
                m.terminated.add(v)
                m.rq.removeAndTerminate(m.actors.getValue(v).queuedRequest!!)
            })
            add(Disruption("move $v to R2", "move", { m -> m.actors.getValue(v).canMove() }) { m ->
                m.actors.getValue(v).moveToR2()
            })
        }
        add(Disruption("a seize arrives", "seize arrives", { true }) { m ->
            m.actor("Xs") { me -> with(me) { use(1, 1.0) } }
        })
        add(Disruption("a preempt arrives", "preempt arrives", { true }) { m ->
            m.actor("Xp", 0) { me -> with(me) { preemptAndWork(InterruptRule.RESUME, 1.0) } }
        })
        add(Disruption("signal all", "signal", { true }) { it.signal.signalAll() })
        add(Disruption("deliver a message", "message", { true }) { it.deliver() })
        add(Disruption("send an item", "item", { true }) { m ->
            m.actor("Xi") {
                m.sent++
                send(m.Item("x"), m.bin)
            }
        })
    }

    val BASES: List<Base> = listOf(
        Base("a release wakes the queue", 2) {
            actor("H1") { me -> with(me) { use(1, 2.0) } }
            actor("H2") { me -> with(me) { use(1, 4.0) } }
            actor("W1", 5, 0.5) { me -> with(me) { use(1, 1.0) } }
            actor("W2", 5, 0.6) { me -> with(me) { use(2, 1.0) } }
            actor("W3", 1, 0.7) { me -> with(me) { use(1, 1.0) } }
        },
        Base("a seize trigger among others", 1) {
            actor("H") { me -> with(me) { use(1, 2.0) } }
            actor("T", 5, 0.5) { me ->
                with(me) { timed(listOf(r.seizeTrigger(), signal.trigger(), mailTrigger(), bin.itemsTrigger(1)), 1.5) }
            }
            actor("W", 5, 0.6) { me -> with(me) { use(1, 1.0) } }
            actor("Sig", 5, 2.0) { signal.signalAll() }
            actor("Msg", 5, 2.0) { deliver() }
            actor("Prod", 5, 2.0) {
                sent++
                send(Item("i"), bin)
            }
        },
        Base("a ready trigger behind a queued seize", 1) {
            actor("H") { me -> with(me) { use(1, 2.0) } }
            actor("Msg", 5, 0.4) { deliver() }
            actor("T", 5, 0.5) { me -> with(me) { timed(listOf(r.seizeTrigger(), mailTrigger()), 1.5) } }
            actor("W", 5, 1.0) { me -> with(me) { use(1, 1.0) } }
        },
        Base("a pool seize is woken", 1, pool = true) {
            actor("H1") { me -> with(me) { use(1, 2.0) } }
            actor("H3") { me -> with(me) { usePool(3.0) } }
            actor("W1", 5, 0.5) { me -> with(me) { usePool(1.0) } }
            actor("W2", 5, 0.6) { me -> with(me) { use(1, 1.0) } }
            actor("T", 5, 0.7) { me -> with(me) { timed(listOf(pool!!.seizeTrigger(1), mailTrigger()), 1.3) } }
            actor("Msg", 5, 2.0) { deliver() }
        },
        Base("a loan ends", 1) {
            actor("H", 5) { me -> with(me) { use(1, 3.0) } }
            actor("P", 1, 1.0) { me -> with(me) { preemptAndWork(variant.interruptRule, 1.0) } }
            actor("W", 5, 1.5) { me -> with(me) { use(1, 1.0) } }
            actor("P2", 2, 1.5) { me -> with(me) { preemptAndWork(variant.interruptRule, 1.0) } }
        },
        Base("a FINISH reservation completes", 1) {
            actor("H", 5) { me -> with(me) { use(1, 2.0) } }
            actor("W", 5, 0.5) { me -> with(me) { use(1, 1.0) } }
            actor("P", 1, 1.0) { me -> with(me) { preemptAndWork(InterruptRule.FINISH, 1.0) } }
        },
        Base("work reacts to a failure", 1) {
            actor("H", 5) { me -> with(me) { reactingWork(2.0) } }
            actor("W", 5, 0.5) { me -> with(me) { use(1, 1.0) } }
            actor("F", 0, 1.0) {
                if (!r.isFailed) {
                    r.fail()
                    delay(1.0)
                    if (r.isFailed) r.repair()
                }
            }
        },
        Base("items and messages", 1) {
            actor("H") { me -> with(me) { use(1, 2.0) } }
            actor("A") { me -> with(me) { timed(listOf(bin.itemsTrigger(2), mailTrigger()), 2.0) } }
            actor("B", 5, 0.1) { me -> with(me) { timed(listOf(bin.itemsTrigger(1)), 10.0) } }
            actor("C", 5, 0.2) { me -> with(me) { timed(listOf(mailTrigger(), r.seizeTrigger()), 2.0) } }
            actor("Prod", 5, 2.0) {
                sent += 2
                send(Item("a"), bin)
                send(Item("b"), bin)
            }
            actor("Msg", 5, 2.0) { deliver() }
        },
        Base("a capacity cut at a release", 2) {
            actor("H1") { me -> with(me) { use(1, 2.0) } }
            actor("H2") { me -> with(me) { use(1, 3.0) } }
            actor("W", 5, 0.5) { me -> with(me) { use(2, 1.0) } }
            actor("W1", 3, 0.6) { me -> with(me) { use(1, 1.0) } }
            actor("Cut", 5, 2.0) {
                if (canDecrease(this@Base, 1, 1.0)) r.changeCapacity(r.CapacityChangeNotice(1, 1.0))
            }
        }
    )

    class RunResult(
        val problems: List<String>,
        val events: List<Pair<Double, String>>,
        val notices: List<Pair<Double, String>>,
        val applied: Boolean,
        val log: List<String>,
        val actorNames: List<String>
    )

    private var myCounter = 0L

    fun run(base: Base, variant: Variant, disruption: Disruption? = null, position: Position? = null): RunResult {
        val model = Model("SameInstant${myCounter++}", autoCSVReports = false)
        model.lengthOfReplication = 30.0
        model.numberOfReplications = 1
        val m = SameInstantModel(model, base, variant, disruption, position)
        val observer = ReferenceOfferObserver()
        val before = ResourceInvariants.offerObserver
        ResourceInvariants.offerObserver = observer
        val problems = mutableListOf<String>()
        try {
            model.simulate()
        } catch (e: Throwable) {
            var cause: Throwable = e
            while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
            val at = cause.stackTrace.firstOrNull { it.className.startsWith("ksl.") }?.let { " at ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: ""
            problems.add("the run stopped: ${cause.javaClass.simpleName}: ${cause.message?.take(300)}$at")
        } finally {
            ResourceInvariants.offerObserver = before
        }
        problems.addAll(m.problems)
        for (d in observer.differences) problems.add("reference: ${d.kind}: ${d.detail}")
        return RunResult(problems, m.events.toList(), m.notices.toList(), m.applied, m.log.toList(), m.actors.keys.filter { it != "cleanup" })
    }

    /** The points of a baseline run at which a disruption is tried. */
    fun positions(baseline: RunResult, until: Double = 5.0): List<Position> {
        val perInstant = baseline.events.groupingBy { it.first }.eachCount()
        val after = baseline.events.withIndex()
            .filter { (_, e) -> e.first <= until && (perInstant[e.first] ?: 0) >= 2 }
            .map { Position.AfterEvent(it.index) }
        val inNotice = baseline.notices.withIndex().filter { it.value.first <= until }.map { Position.InNotice(it.index) }
        return after + inNotice
    }

    fun describe(baseline: RunResult, p: Position): String = when (p) {
        is Position.AfterEvent -> "after event #${p.index} ${baseline.events[p.index]}"
        is Position.InNotice -> "in notice #${p.index} ${baseline.notices[p.index]}"
    }

    class Finding(val key: String, val base: String) {
        var count = 0
        var first: String = ""
        var log: List<String> = emptyList()
        var size = Int.MAX_VALUE
    }

    class Exploration(val runs: Int, val applied: Int, val findings: List<Finding>)

    /** Explores every base, variant, disruption and position; the findings are grouped by base, disruption kind and problem. */
    fun explore(bases: List<Base> = BASES, variants: List<Variant> = VARIANTS, pairs: Boolean = false): Exploration {
        val findings = LinkedHashMap<String, Finding>()
        var runs = 0
        var applied = 0
        fun record(base: Base, kind: String, problem: String, where: String, log: List<String>, size: Int) {
            val shape = problem.replace(Regex("[0-9]+(\\.[0-9]+)?"), "#").replace(Regex("\\b(H|W|P|T|X|A|B|C)[0-9a-z]*\\b"), "@")
            val key = "$base | $kind | ${shape.take(200)}"
            val f = findings.getOrPut(key) { Finding(key, base.name) }
            f.count++
            if (size < f.size) {
                f.size = size
                f.first = "$where: $problem"
                f.log = log
            }
        }
        for (base in bases) for (variant in variants) {
            val baseline = run(base, variant)
            runs++
            for (p in baseline.problems) record(base, "baseline", p, "baseline $variant", baseline.log, 0)
            val ps = positions(baseline)
            val singles = disruptions(baseline.actorNames)
            val tried = if (!pairs) singles else
                singles.flatMap { a -> singles.filter { it.kind in PAIRED_KINDS }.map { b -> pair(a, b) } }
            for (d in tried) for (p in ps) {
                val r = run(base, variant, d, p)
                runs++
                if (!r.applied) continue
                applied++
                val where = "$variant, ${d.name}, ${describe(baseline, p)}"
                for (problem in r.problems) record(base, d.kind, problem, where, r.log, r.events.size)
            }
        }
        return Exploration(runs, applied, findings.values.toList())
    }
}
