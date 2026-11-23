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

package ksl.modeling.entity.guiderules

import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 *  A rule of the waits tutorial's case 4, "The shuttle that leaves full or on time" (`ksl-waits-tutorial.md`): an
 *  event due after the end of the replication is never put on the calendar, so cancelling it throws. The event
 *  scheduled while the replication runs is checked elsewhere; this class checks an event scheduled as the
 *  replication is initialized, in every replication, beside one due before the end.
 */
class WaitsShuttleCasesTest {

    /** The replication lasts 10. As it is initialized, one event is scheduled for 8 and one for 25, after the end. */
    private class Stop(parent: ModelElement) : ModelElement(parent, "Stop") {
        val log = mutableListOf<String>()

        override fun initialize() {
            val inside = schedule({ _: KSLEvent<Nothing> -> log.add("inside ran@$time") }, 8.0)
            val after = schedule({ _: KSLEvent<Nothing> -> log.add("after ran@$time") }, 25.0)
            log.add("inside scheduled=${inside.isScheduled} after scheduled=${after.isScheduled}")
            val outcome = try {
                after.cancel = true
                "cancelled"
            } catch (e: IllegalArgumentException) {
                "threw"
            }
            log.add("cancelling after: $outcome")
        }
    }

    @Test
    @DisplayName("an event scheduled at initialization for after the end is never on the calendar, and cancelling it throws, every replication")
    fun eventScheduledAtInitializationAfterTheEnd() {
        val model = Model("ShuttleEnd${System.nanoTime()}", autoCSVReports = false)
        val stop = Stop(model)
        model.numberOfReplications = 2
        model.lengthOfReplication = 10.0
        val error = try {
            model.simulate(); null
        } catch (e: Throwable) {
            e
        }
        assertNull(error, "the run stopped: $error")
        val one = listOf("inside scheduled=true after scheduled=false", "cancelling after: threw", "inside ran@8.0")
        assertEquals(one + one, stop.log)
    }
}
