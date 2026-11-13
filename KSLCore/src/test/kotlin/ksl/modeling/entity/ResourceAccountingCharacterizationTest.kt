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

import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  Pins a process-view resource's accounting, and the release of a holder's units when it is
 *  terminated or a replication ends. Failures and loans are about to add a fourth state and change how
 *  units are counted; with neither in use, every number here must stay the same.
 */
class ResourceAccountingCharacterizationTest {

    /**
     *  Deterministic customers every 3 seize R1 (capacity 2, on a schedule of 2 for 30 then 1 for 20)
     *  for 4, then the pool of A and B for 2, then R2 (off shift from 10 to 15 and from 110 to 118) for 1.
     */
    private class AModel(parent: ModelElement) : ProcessModel(parent, "accounting") {
        val r1 = ResourceWithQ(this, "R1", 2)
        val r2 = ResourceWithQ(this, "R2", 1)
        val a = ResourceWithQ(this, "A")
        val b = ResourceWithQ(this, "B")
        val pool = ResourcePoolWithQ(this, listOf(a, b), name = "P")
        val counts = mutableListOf<String>()

        init {
            val schedule = CapacitySchedule(this, 0.0, repeatable = true, name = "S")
            schedule.addItem(capacity = 2, duration = 30.0)
            schedule.addItem(capacity = 1, duration = 20.0)
            r1.useSchedule(schedule, CapacityChangeRule.IGNORE)
        }

        inner class Customer : Entity() {
            val p: KSLProcess = process {
                val x = seize(r1); delay(4.0); release(x)
                val y = seize(pool); delay(2.0); release(y)
                val z = seize(r2); delay(1.0); release(z)
            }
        }

        override fun initialize() {
            schedule(EventActionIfc<Nothing> { arrive() }, 0.0)
            schedule(EventActionIfc<Nothing> { r2.goOffShift() }, 10.0)
            schedule(EventActionIfc<Nothing> { r2.goOnShift() }, 15.0)
            schedule(EventActionIfc<Nothing> { r2.goOffShift() }, 110.0)
            schedule(EventActionIfc<Nothing> { r2.goOnShift() }, 118.0)
        }

        private fun arrive() {
            activate(Customer().p)
            schedule(EventActionIfc<Nothing> { arrive() }, 3.0)
        }

        override fun replicationEnded() {
            LoansAndFreezesCheck.check(r1, r2, a, b)
            for (r in listOf(r1, r2, a, b)) counts.add("N ${r.name} seized=${r.numTimesSeized} released=${r.numTimesReleased} busy=${r.numBusy}")
        }
    }

    @Test
    @DisplayName("a resource's accounting over three replications: units busy, utilizations, state proportions, seizes and releases")
    fun accounting() {
        val model = Model("Accounting${System.nanoTime()}", autoCSVReports = false)
        val m = AModel(model)
        model.numberOfReplications = 3
        model.lengthOfReplication = 200.0
        model.simulate()
        val seen = mutableListOf<String>()
        for (r in model.responses) {
            val s = r.acrossReplicationStatistic
            seen.add("R ${r.name} ${"%.9f".format(s.average)} n=${s.count.toInt()}")
        }
        for (c in model.counters) {
            val s = c.acrossReplicationStatistic
            seen.add("C ${c.name} ${"%.9f".format(s.average)} n=${s.count.toInt()}")
        }
        assertEquals(List(3) { m.counts.subList(0, 4) }.flatten(), m.counts, "every replication counts the same")
        seen.addAll(m.counts.subList(0, 4))
        assertEquals(
            listOf(
                "R R1:NumActiveUnits 1.600000000 n=3",
                "R R1:PTimeInactive 0.000000000 n=3",
                "R R1:PTimeIdle 0.000000000 n=3",
                "R R1:PTimeBusy 1.000000000 n=3",
                "R R1:InstantaneousUtil 0.847500000 n=3",
                "R R1:NumBusyUnits 1.295000000 n=3",
                "R R1:ScheduledUtil 0.809375000 n=3",
                "R R1:Q:NumInQ 0.490000000 n=3",
                "R R1:Q:TimeInQ 1.454545455 n=3",
                "R R1:WIP 1.785000000 n=3",
                "R R2:NumActiveUnits 0.935000000 n=3",
                "R R2:PTimeInactive 0.065000000 n=3",
                "R R2:PTimeIdle 0.615000000 n=3",
                "R R2:PTimeBusy 0.320000000 n=3",
                "R R2:InstantaneousUtil 0.320000000 n=3",
                "R R2:NumBusyUnits 0.320000000 n=3",
                "R R2:ScheduledUtil 0.342245989 n=3",
                "R R2:Q:NumInQ 0.155000000 n=3",
                "R R2:Q:TimeInQ 0.484375000 n=3",
                "R R2:WIP 0.475000000 n=3",
                "R A:NumActiveUnits 1.000000000 n=3",
                "R A:PTimeInactive 0.000000000 n=3",
                "R A:PTimeIdle 0.450000000 n=3",
                "R A:PTimeBusy 0.550000000 n=3",
                "R A:InstantaneousUtil 0.550000000 n=3",
                "R A:NumBusyUnits 0.550000000 n=3",
                "R A:ScheduledUtil 0.550000000 n=3",
                "R A:Q:NumInQ 0.000000000 n=3",
                "R A:Q:TimeInQ NaN n=0",
                "R A:WIP 0.550000000 n=3",
                "R B:NumActiveUnits 1.000000000 n=3",
                "R B:PTimeInactive 0.000000000 n=3",
                "R B:PTimeIdle 0.910000000 n=3",
                "R B:PTimeBusy 0.090000000 n=3",
                "R B:InstantaneousUtil 0.090000000 n=3",
                "R B:NumBusyUnits 0.090000000 n=3",
                "R B:ScheduledUtil 0.090000000 n=3",
                "R B:Q:NumInQ 0.000000000 n=3",
                "R B:Q:TimeInQ NaN n=0",
                "R B:WIP 0.090000000 n=3",
                "R P:NumBusy 0.640000000 n=3",
                "R P:FractionBusy 0.320000000 n=3",
                "R P:Q:NumInQ 0.000000000 n=3",
                "R P:Q:TimeInQ 0.000000000 n=3",
                "C R1:SeizeCount 66.000000000 n=3",
                "C R2:SeizeCount 64.000000000 n=3",
                "C A:SeizeCount 55.000000000 n=3",
                "C B:SeizeCount 9.000000000 n=3",
                "N R1 seized=66 released=64 busy=2",
                "N R2 seized=64 released=64 busy=0",
                "N A seized=55 released=55 busy=0",
                "N B seized=9 released=9 busy=0"
            ),
            seen
        )
    }

    private class HModel(parent: ModelElement) : ProcessModel(parent, "holders") {
        val r = ResourceWithQ(this, "R")
        val log = mutableListOf<String>()
        val setups = mutableListOf<HModel.() -> Unit>()

        inner class Actor(val tag: String, val body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            val p: KSLProcess = process("p-$tag") { body() }
        }

        fun actor(tag: String, body: suspend KSLProcessBuilder.() -> Unit) = Actor(tag, body).also { activate(it.p) }

        fun at(t: Double, action: () -> Unit) {
            schedule(EventActionIfc<Nothing> { action() }, t)
        }

        override fun initialize() {
            log.clear()
            for (s in setups) s()
        }
    }

    private fun runHolders(reps: Int = 1, length: Double = 50.0, setup: HModel.() -> Unit): HModel {
        val model = Model("Holders${System.nanoTime()}", autoCSVReports = false)
        val m = HModel(model)
        m.setups.add(setup)
        model.numberOfReplications = reps
        model.lengthOfReplication = length
        model.simulate()
        return m
    }

    @Test
    @DisplayName("a holder terminated in its delay releases its unit, which the waiting request is offered at once")
    fun holderTerminatedInDelay() {
        val m = runHolders {
            val h = actor("H") { val x = seize(r); delay(100.0); release(x) }
            actor("W") { delay(1.0); val x = seize(r); log.add("W:served@$time"); release(x) }
            at(5.0) { h.terminateProcess(); log.add("after termination busy=${r.numBusy}") }
        }
        assertEquals(listOf("after termination busy=0", "W:served@5.0"), m.log)
    }

    @Test
    @DisplayName("holders in service at the end of a replication: nothing is carried over into the next")
    fun holdersAtEndOfReplication() {
        val seen = mutableListOf<String>()
        runHolders(reps = 3, length = 20.0) {
            actor("H") { val x = seize(r); delay(100.0); release(x) }
            actor("W") { delay(1.0); val x = seize(r); log.add("W:served@$time"); release(x) }
            at(2.0) { seen.add("at 2 busy=${r.numBusy} waiting=${r.myWaitingQ.size} log=$log") }
        }
        assertEquals(List(3) { "at 2 busy=1 waiting=1 log=[]" }, seen)
    }
}
