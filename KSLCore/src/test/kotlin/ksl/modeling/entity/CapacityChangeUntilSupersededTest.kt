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

import ksl.modeling.agent.AgentModel
import ksl.modeling.agent.AgentResource
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Capacity changes with an infinite duration, which last until another change supersedes them. Shift changes
 *  use them, and they are the default duration of a change notice.
 *
 *  Before R1.8 such a change was treated as ending at time infinity, an event that is never placed on the
 *  calendar. Under the IGNORE rule, going back on shift while the off-shift change was still pending threw
 *  ("Cannot cancel an event that is not scheduled"). Under the WAIT rule, going off shift while a unit was busy
 *  left the resource off shift for the rest of the replication, whenever it went back on.
 */
class CapacityChangeUntilSupersededTest {

    /** What the probe saw at one time. */
    data class Snapshot(val capacity: Int, val numBusy: Int, val pending: Boolean)

    /**
     *  A resource of `capacity` units, [customers] customers that each hold one unit from 0 to [holdUntil], a
     *  controller running [control], and a probe that records the resource at each of [probeTimes].
     */
    private class ShiftModel(
        parent: ModelElement,
        rule: CapacityChangeRule,
        capacity: Int = 1,
        private val customers: Int = 1,
        private val holdUntil: Double = 10.0,
        private val probeTimes: List<Double>,
        private val control: suspend KSLProcessBuilder.(ShiftModel) -> Unit
    ) : ProcessModel(parent, null) {
        val resource = ResourceWithQ(this, name = "Server", capacity = capacity)
        val shift = ShiftControl(resource)
        val seen = mutableMapOf<Double, Snapshot>()

        init {
            resource.capacityChangeRule = rule
        }

        inner class Customer : Entity() {
            val run: KSLProcess = process { val a = seize(resource); delay(holdUntil); release(a) }
        }

        inner class Controller : Entity() {
            val run: KSLProcess = process { control(this@ShiftModel) }
        }

        inner class Probe : Entity() {
            val run: KSLProcess = process {
                var last = 0.0
                for (t in probeTimes) {
                    delay(t - last)
                    last = t
                    seen[t] = Snapshot(resource.capacity, resource.numBusy, resource.isPendingCapacityChange)
                }
            }
        }

        override fun initialize() {
            seen.clear()
            shift.initialize()
            repeat(customers) { activate(Customer().run) }
            activate(Controller().run)
            activate(Probe().run)
        }
    }

    private fun simulate(build: (Model) -> ShiftModel): ShiftModel {
        val m = Model("Shifts", autoCSVReports = false)
        val model = build(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 100.0
        val error = runCatching { m.simulate() }.exceptionOrNull()
        assertNull(error, "the run must not fail: ${error?.message}")
        return model
    }

    @Test
    @DisplayName("IGNORE: off shift while busy, back on before the release, does not fail")
    fun ignoreOffThenOnWhileBusy() {
        val m = simulate {
            ShiftModel(it, CapacityChangeRule.IGNORE, probeTimes = listOf(3.0, 5.0, 11.0)) { s ->
                delay(2.0); s.shift.goOffShift()
                delay(2.0); s.shift.goOnShift()
            }
        }
        assertEquals(Snapshot(0, 1, true), m.seen[3.0], "off shift at once, the busy unit still owed")
        assertEquals(Snapshot(1, 1, false), m.seen[5.0], "back on shift: the pending decrease is withdrawn")
        assertEquals(Snapshot(1, 0, false), m.seen[11.0], "after the release")
    }

    @Test
    @DisplayName("WAIT: back on shift before the release withdraws the off-shift change")
    fun waitOnBeforeTheRelease() {
        val m = simulate {
            ShiftModel(it, CapacityChangeRule.WAIT, probeTimes = listOf(3.0, 5.0, 11.0)) { s ->
                delay(2.0); s.shift.goOffShift()
                delay(2.0); s.shift.goOnShift()
            }
        }
        assertEquals(Snapshot(1, 1, true), m.seen[3.0], "WAIT keeps the busy unit until it is released")
        assertEquals(Snapshot(1, 1, false), m.seen[5.0], "the off-shift change never takes effect")
        assertEquals(Snapshot(1, 0, false), m.seen[11.0], "still on shift after the release")
    }

    @Test
    @DisplayName("WAIT: off shift while busy, on again after the release, comes back on shift")
    fun waitOnAfterTheRelease() {
        val m = simulate {
            ShiftModel(it, CapacityChangeRule.WAIT, probeTimes = listOf(11.0, 51.0)) { s ->
                delay(2.0); s.shift.goOffShift()
                delay(48.0); s.shift.goOnShift()
            }
        }
        assertEquals(Snapshot(0, 0, false), m.seen[11.0], "off shift once the unit was released, nothing pending")
        assertEquals(Snapshot(1, 0, false), m.seen[51.0], "back on shift")
    }

    @Test
    @DisplayName("WAIT: off shift while busy and never back on leaves nothing pending")
    fun waitOffWhileBusy() {
        val m = simulate {
            ShiftModel(it, CapacityChangeRule.WAIT, probeTimes = listOf(11.0)) { s ->
                delay(2.0); s.shift.goOffShift()
            }
        }
        assertEquals(Snapshot(0, 0, false), m.seen[11.0])
    }

    @Test
    @DisplayName("IGNORE: two decreases in a row while units are busy are both applied")
    fun ignoreTwoDecreasesInARow() {
        val m = simulate {
            ShiftModel(it, CapacityChangeRule.IGNORE, capacity = 3, customers = 3, probeTimes = listOf(4.0, 11.0)) { s ->
                delay(2.0); s.resource.changeCapacity(s.resource.CapacityChangeNotice(capacity = 2))
                delay(1.0); s.resource.changeCapacity(s.resource.CapacityChangeNotice(capacity = 1))
            }
        }
        assertEquals(Snapshot(2, 3, true), m.seen[4.0], "the second decrease waits behind the first")
        assertEquals(Snapshot(1, 0, false), m.seen[11.0], "both decreases applied once the units are released")
    }

    @Test
    @DisplayName("pendingCapacityChangeEndTime: NaN with nothing pending, infinite for a change until superseded")
    fun pendingEndTime() {
        val ends = mutableListOf<Double>()
        simulate {
            ShiftModel(it, CapacityChangeRule.IGNORE, probeTimes = listOf(1.0)) { s ->
                delay(1.0); ends += s.resource.pendingCapacityChangeEndTime
                s.shift.goOffShift(); ends += s.resource.pendingCapacityChangeEndTime
            }
        }
        assertTrue(ends[0].isNaN())
        assertEquals(Double.POSITIVE_INFINITY, ends[1])
    }

    @Test
    @DisplayName("Stopping a schedule whose pending change ends after the run does not fail")
    fun stopScheduleWithAChangeEndingAfterTheRun() {
        simulate {
            ShiftModel(it, CapacityChangeRule.IGNORE, probeTimes = listOf(1.0)) { s ->
                delay(5.0)
                s.resource.stopUsingSchedule()
            }.also { model ->
                val schedule = CapacitySchedule(model, 0.0, name = "Long")
                schedule.addItem(capacity = 1, duration = 1.0)
                schedule.addItem(capacity = 0, duration = 1000.0)   // at 1, pending while the unit is busy; ends after the run
                model.resource.useSchedule(schedule, CapacityChangeRule.IGNORE)
            }
        }
    }

    /** The reproduction from the report, on an agent resource. */
    private class AgentShift(parent: ModelElement) : AgentModel(parent, "AgentShift") {
        val server = AgentResource(this, "Server", 1)
        var capacityAfter = -1

        inner class Customer : Entity() {
            val script: KSLProcess = process { val a = seize(server); delay(10.0); release(a) }
        }

        inner class Controller : Entity() {
            val script: KSLProcess = process {
                delay(2.0); server.goOffShift()
                delay(2.0); server.goOnShift()
                delay(10.0); capacityAfter = server.capacity
            }
        }

        override fun initialize() {
            super.initialize()
            activate(Customer().script)
            activate(Controller().script)
        }
    }

    @Test
    @DisplayName("An agent resource taken off and back on shift while busy (the report's reproduction)")
    fun agentResourceShift() {
        val m = Model("AgentShiftModel", autoCSVReports = false)
        val model = AgentShift(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 100.0
        val error = runCatching { m.simulate() }.exceptionOrNull()
        assertNull(error, "the run must not fail: ${error?.message}")
        assertEquals(1, model.capacityAfter)
        assertFalse(model.server.isOffShift)
    }
}
