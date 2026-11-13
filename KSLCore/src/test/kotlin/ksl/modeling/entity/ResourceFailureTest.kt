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

import ksl.modeling.variable.Counter
import ksl.modeling.variable.Response
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  A resource's failures: the failed state, `fail` and `repair` under each interrupt rule, requests that
 *  wait through a failure, pools and shifts, the declared failures on each basis, and their statistics.
 */
class ResourceFailureTest {

    private class FModel(parent: ModelElement) : ProcessModel(parent, "failures") {
        val r = ResourceWithQ(this, "R")
        val r2 = ResourceWithQ(this, "R2", capacity = 2)
        val p1 = ResourceWithQ(this, "P1")
        val p2 = ResourceWithQ(this, "P2")
        val pool = ResourcePoolWithQ(this, listOf(p1, p2), name = "Pool")

        val log = mutableListOf<String>()
        val setups = mutableListOf<FModel.() -> Unit>()

        inner class Actor(val tag: String, val body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            val p: KSLProcess = process("p-$tag") { body() }
        }

        fun actor(tag: String, body: suspend KSLProcessBuilder.() -> Unit) = Actor(tag, body).also { activate(it.p) }

        fun at(t: Double, action: () -> Unit) = actor("at$t") { delay(t); action() }

        /** Seizes [res] at [from], holds it for [hold], and logs when it got it and when it let it go. */
        fun job(tag: String, res: ResourceWithQ, from: Double, hold: Double) = actor(tag) {
            delay(from)
            val a = seize(res)
            log.add("$tag got@$time")
            delay(hold)
            release(a)
            log.add("$tag done@$time")
        }

        /** Logs each failure and repair of [res]. */
        fun watch(res: Resource) {
            res.addAllocationListener(object : AllocationListenerIfc {
                override fun allocate(allocation: Allocation) {}
                override fun deallocate(allocation: Allocation) {}
                override fun failureStateChanged(resource: Resource) {
                    log.add("${resource.name} ${if (resource.isFailed) "fail" else "repair"}@$time")
                }
            })
        }

        override fun initialize() {
            log.clear()
            for (s in setups) s()
        }

        override fun replicationEnded() {
            LoansAndFreezesCheck.check(r, r2, p1, p2)
        }
    }

    private fun run(
        reps: Int = 1,
        length: Double = 100.0,
        configure: FModel.() -> Unit = {},
        setup: FModel.() -> Unit
    ): Pair<Model, FModel> {
        val model = Model("Failures${System.nanoTime()}", autoCSVReports = false)
        val m = FModel(model)
        m.configure()
        m.setups.add(setup)
        model.numberOfReplications = reps
        model.lengthOfReplication = length
        model.simulate()
        return model to m
    }

    // ---------- fail and repair by hand ----------

    @Test
    @DisplayName("each rule with one holder: the end of its delay, and the waiting request served after repair")
    fun eachRuleWithOneHolder() {
        val expected = mapOf(
            InterruptRule.FINISH to listOf("W got@0.0", "W done@10.0", "N got@12.0", "N done@13.0"),
            InterruptRule.RESUME to listOf("W got@0.0", "W done@19.0", "N got@19.0", "N done@20.0"),
            InterruptRule.RESTART to listOf("W got@0.0", "W done@22.0", "N got@22.0", "N done@23.0")
        )
        for ((rule, log) in expected) {
            val (_, m) = run {
                job("W", r, 0.0, 10.0)
                job("N", r, 1.0, 1.0)
                at(3.0) { r.fail(rule); LoansAndFreezesCheck.check(r) }
                at(12.0) { r.repair(); LoansAndFreezesCheck.check(r) }
            }
            assertEquals(log, m.log, "rule $rule")
        }
    }

    @Test
    @DisplayName("FINISH: the holder completes, and the unit it releases is not given to the queue until repair")
    fun finishKeepsTheUnitFromTheQueue() {
        val (_, m) = run {
            job("W", r, 0.0, 10.0)
            job("N", r, 1.0, 1.0)
            at(3.0) { r.fail(InterruptRule.FINISH) }
            at(11.0) { log.add("at 11 busy=${r.numBusy} avail=${r.numAvailableUnits} waiting=${r.waitingQ.size} failed=${r.isFailed}") }
            at(12.0) { r.repair() }
        }
        assertEquals("at 11 busy=0 avail=0 waiting=1 failed=true", m.log.first { it.startsWith("at 11") })
        assertTrue("N got@12.0" in m.log)
    }

    @Test
    @DisplayName("a capacity above 1 with several holders: all are interrupted")
    fun severalHoldersAllInterrupted() {
        val (_, m) = run {
            job("A", r2, 0.0, 10.0)
            job("B", r2, 0.0, 6.0)
            at(2.0) { r2.fail(InterruptRule.RESUME) }
            at(5.0) { r2.repair() }
        }
        assertEquals(listOf("A got@0.0", "B got@0.0", "B done@9.0", "A done@13.0"), m.log)
    }

    @Test
    @DisplayName("a request arriving while failed waits and is served at repair")
    fun arrivalWhileFailed() {
        val (_, m) = run {
            at(3.0) { r.fail() }
            job("N", r, 5.0, 1.0)
            at(8.0) { r.repair() }
        }
        assertEquals(listOf("N got@8.0", "N done@9.0"), m.log)
    }

    @Test
    @DisplayName("a pool with one failed member: a new request takes the other, and the waiters go to the other")
    fun poolWithAFailedMember() {
        val (_, m) = run {
            // both members busy, then P1 fails; its holder's release is held back, P2's goes to the waiter
            job("H1", p1, 0.0, 4.0)
            job("H2", p2, 0.0, 6.0)
            actor("W") {
                delay(1.0)
                val pa = seize(pool)
                log.add("W got ${pa.allocations.single().myResource.name}@$time")
                delay(1.0); release(pa)
            }
            at(2.0) { p1.fail() }
            actor("X") {
                delay(10.0)
                val pa = seize(pool)
                log.add("X got ${pa.allocations.single().myResource.name}@$time")
                release(pa)
            }
            at(20.0) { p1.repair() }
        }
        assertTrue("W got P2@6.0" in m.log, m.log.toString())
        assertTrue("X got P2@10.0" in m.log, m.log.toString())
    }

    @Test
    @DisplayName("a failure while off shift, and shift changes while failed: the repair leaves the state the shift gives")
    fun failuresAndShifts() {
        val (_, m) = run {
            fun note(label: String) = log.add("$label ${r.state.name.substringAfter("_")}")
            at(2.0) { r.goOffShift(); note("off") }
            at(3.0) { r.fail(); note("fail") }
            at(5.0) { r.repair(); note("repair") }
            at(6.0) { r.goOnShift(); note("on") }
            at(10.0) { r.fail(); note("fail") }
            at(11.0) { r.goOffShift(); note("off") }
            job("N", r, 11.5, 1.0)
            at(12.0) { r.goOnShift(); note("on") }
            at(15.0) { r.repair(); note("repair") }
        }
        assertEquals(
            listOf(
                "off Inactive", "fail Failed", "repair Inactive", "on Idle",
                "fail Failed", "off Failed", "on Failed", "repair Idle", "N got@15.0", "N done@16.0"
            ),
            m.log
        )
    }

    @Test
    @DisplayName("misuse: fail when failed and repair when not failed throw")
    fun misuse() {
        val errors = mutableListOf<String>()
        run {
            at(1.0) { try { r.repair() } catch (e: IllegalStateException) { errors.add(e.message!!) } }
            at(2.0) { r.fail() }
            at(3.0) { try { r.fail() } catch (e: IllegalStateException) { errors.add(e.message!!) } }
            at(4.0) { r.repair() }
        }
        assertEquals(2, errors.size)
        assertTrue(errors[0].contains("not failed"), errors[0])
        assertTrue(errors[1].contains("already failed"), errors[1])
    }

    // ---------- a promise broken by a failure ----------

    @Test
    @DisplayName("a release wakes a request with a promise and the resource fails at the same instant: the request waits for repair")
    fun promiseBrokenByAFailure() {
        val (_, m) = run {
            actor("W") { val a = seize(r); delay(5.0); release(a); r.fail() }
            job("N", r, 1.0, 1.0)
            at(8.0) { r.repair() }
        }
        assertEquals(listOf("N got@8.0", "N done@9.0"), m.log)
    }

    // ---------- an S4 behavior ----------

    @Test
    @DisplayName("a behavior holding its own unit when the resource fails: its work is held and goes on at repair")
    fun behaviorHoldingItsOwnUnit() {
        val (_, m) = run(configure = {
            r.interruptRule = InterruptRule.RESUME
            r.behavior { val a = seize(r); delay(10.0); release(a); log.add("behavior done@$time") }
        }) {
            at(3.0) { r.fail() }
            at(7.0) { r.repair() }
        }
        assertEquals(listOf("behavior done@14.0"), m.log)
    }

    @Test
    @DisplayName("a behavior frozen by a failure and stopped at once: its unit goes back and nothing of it fires afterwards")
    fun stopAFrozenBehavior() {
        val (_, m) = run(configure = {
            r.interruptRule = InterruptRule.RESUME
            r.behavior { val a = seize(r); delay(10.0); release(a); log.add("behavior done@$time") }
        }) {
            at(3.0) { r.fail() }
            at(5.0) { r.stopBehavior(BehaviorStop.NOW); log.add("stopped busy=${r.numBusy}") }
            at(7.0) { r.repair() }
            job("N", r, 8.0, 1.0)
        }
        assertEquals(listOf("stopped busy=0", "N got@8.0", "N done@9.0"), m.log)
    }

    // ---------- the declared failures ----------

    @Test
    @DisplayName("CALENDAR_TIME: a failure every 10 time units, each lasting 2")
    fun calendarTime() {
        val (_, m) = run(length = 40.0, configure = {
            r.failures(ConstantRV(10.0), ConstantRV(2.0))
        }) { watch(r) }
        assertEquals(listOf("R fail@10.0", "R repair@12.0", "R fail@22.0", "R repair@24.0", "R fail@34.0", "R repair@36.0"), m.log)
    }

    @Test
    @DisplayName("BUSY_TIME: the busy time is banked across an idle period and the failure comes at the exact instant")
    fun busyTime() {
        val (_, m) = run(length = 30.0, configure = {
            r.failures(ConstantRV(5.0), ConstantRV(1.0), ResourceFailureBasis.BUSY_TIME)
        }) {
            watch(r)
            job("A", r, 0.0, 3.0)
            job("B", r, 6.0, 10.0)
        }
        // busy 0 to 3 (2 left), idle 3 to 6, busy again: fails at 8; repaired at 9 and still busy: fails at 14
        assertEquals(
            listOf("A got@0.0", "A done@3.0", "B got@6.0", "R fail@8.0", "R repair@9.0", "R fail@14.0", "R repair@15.0", "B done@16.0"),
            m.log
        )
    }

    @Test
    @DisplayName("BUSY_TIME: no busy time counts while off shift")
    fun busyTimeOffShift() {
        val (_, m) = run(length = 30.0, configure = {
            r.capacityChangeRule = CapacityChangeRule.IGNORE
            r.failures(ConstantRV(5.0), ConstantRV(1.0), ResourceFailureBasis.BUSY_TIME)
        }) {
            watch(r)
            job("A", r, 0.0, 10.0)
            at(2.0) { r.goOffShift() }
            at(4.0) { r.goOnShift() }
        }
        // busy throughout, but off shift 2 to 4: the clock runs 0 to 2 and from 4, failing at 7
        assertTrue("R fail@7.0" in m.log, m.log.toString())
    }

    @Test
    @DisplayName("USES: the resource fails at the release that completes the nth use")
    fun uses() {
        val (_, m) = run(length = 13.0, configure = {
            r.failures(ConstantRV(3.0), ConstantRV(1.0), ResourceFailureBasis.USES)
        }) {
            watch(r)
            for ((i, t) in listOf(0.0, 2.0, 4.0, 6.5, 8.0, 10.0).withIndex()) job("J$i", r, t, 1.0)
        }
        val failures = m.log.filter { it.startsWith("R ") }
        assertEquals(listOf("R fail@5.0", "R repair@6.0", "R fail@11.0", "R repair@12.0"), failures)
    }

    @Test
    @DisplayName("a failure that comes due while failed by hand waits for that repair, and its clock starts again")
    fun failureDueWhileFailedByHand() {
        val (_, m) = run(length = 30.0, configure = {
            r.failures(ConstantRV(10.0), ConstantRV(2.0))
        }) {
            watch(r)
            at(9.0) { r.fail() }
            at(15.0) { r.repair() }
        }
        assertEquals(listOf("R fail@9.0", "R repair@15.0", "R fail@25.0", "R repair@27.0"), m.log)
    }

    // ---------- statistics ----------

    @Test
    @DisplayName("PTimeFailed and NumFailures over replications")
    fun failureStatistics() {
        val (model, _) = run(reps = 3, length = 100.0, configure = {
            r.failures(ConstantRV(8.0), ConstantRV(2.0))
        }) {}
        val pTime = model.getModelElement("R:PTimeFailed") as Response
        val count = model.getModelElement("R:NumFailures") as Counter
        // failed 8 to 10, 18 to 20, ..., 98 to 100: ten failures, 20 of the 100 time units
        assertEquals(0.2, pTime.acrossReplicationStatistic.average, 1e-12)
        assertEquals(0.0, pTime.acrossReplicationStatistic.variance, 1e-12)
        assertEquals(10.0, count.acrossReplicationStatistic.average, 1e-12)
    }

    @Test
    @DisplayName("no failure statistics are added without asking, and hand failures are counted when asked")
    fun noNewElementsWithoutOptingIn() {
        val (model, _) = run {}
        assertNull(model.getModelElement("R:PTimeFailed"))
        assertNull(model.getModelElement("R:NumFailures"))
        val (asked, _) = run(configure = { r.collectFailureStatistics() }) {
            at(10.0) { r.fail() }
            at(30.0) { r.repair() }
        }
        assertEquals(0.2, (asked.getModelElement("R:PTimeFailed") as Response).acrossReplicationStatistic.average, 1e-12)
        assertEquals(1.0, (asked.getModelElement("R:NumFailures") as Counter).acrossReplicationStatistic.average, 1e-12)
    }

    @Test
    @Tag("slow")
    @DisplayName("availability: 1 - PTimeFailed covers MTTF/(MTTF+MTTR) for exponential failures and repairs")
    fun availability() {
        val (model, _) = run(reps = 30, length = 20_000.0, configure = {
            r.failures(ExponentialRV(10.0, streamNum = 1), ExponentialRV(2.0, streamNum = 2))
        }) {}
        val stat = (model.getModelElement("R:PTimeFailed") as Response).acrossReplicationStatistic
        val ci = stat.confidenceInterval(0.99)
        val expectedFailed = 2.0 / 12.0
        assertNotNull(ci)
        assertTrue(expectedFailed in ci, "expected $expectedFailed in $ci")
    }
}
