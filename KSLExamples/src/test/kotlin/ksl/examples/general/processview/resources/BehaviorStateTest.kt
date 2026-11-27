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
package ksl.examples.general.processview.resources

import ksl.simulation.Model
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** The resources tutorial's pitfall on a behavior's state: where the state lives decides whether it resets. */
class BehaviorStateTest {

    private fun longBreaks(counting: BreakCount): List<Int> {
        val model = Model("BehaviorState$counting${System.nanoTime()}", autoCSVReports = false)
        val shop = DoctorBreaks(model, counting)
        model.numberOfReplications = 3
        model.lengthOfReplication = 180.0
        model.simulate()
        return shop.longBreaksPerReplication
    }

    @Test
    @DisplayName("state captured from outside a cyclic behavior carries over between replications")
    fun capturedCarriesOver() = assertEquals(listOf(0, 1, 1), longBreaks(BreakCount.CAPTURED))

    @Test
    @DisplayName("state captured from outside a cyclic behavior and reset in initialize starts over each replication")
    fun resetInInitialize() = assertEquals(listOf(0, 0, 0), longBreaks(BreakCount.RESET_IN_INITIALIZE))

    @Test
    @DisplayName("state local to a behavior run once per replication starts over each replication")
    fun localToBehavior() = assertEquals(listOf(0, 0, 0), longBreaks(BreakCount.LOCAL_TO_BEHAVIOR))
}
