/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.modeling.agent

import ksl.simulation.Model
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Batch 6 low-priority polish guards. */
class Batch6PolishTest {

    @Test
    fun validationInitialErrorNamesTheFieldWhenProvided() {
        val named = assertThrows<IllegalArgumentException> { positive(-1.0, "mass") }
        assertTrue(named.message!!.contains("mass"), "named initial error should name the field")
        val anon = assertThrows<IllegalArgumentException> { positive(-1.0) }
        assertTrue(anon.message!!.contains("value"), "unnamed falls back to a generic label")
    }

    @Test
    fun collectPerformanceRejectsConflictingAllPerformanceFlag() {
        val model = Model("b6-perf")
        val m = object : AgentModel(model, "b6") {
            val res = AgentResource(this, "worker", capacity = 1)
        }
        val first = m.res.collectPerformance(allPerformance = false)
        // Same flag → idempotent, returns the same observer.
        assertSame(first, m.res.collectPerformance(allPerformance = false))
        // Conflicting flag → rejected rather than silently ignored.
        assertThrows<IllegalArgumentException> { m.res.collectPerformance(allPerformance = true) }
    }

    @Test
    fun agentResourceStartsOnShift() {
        val model = Model("b6-shift")
        val m = object : AgentModel(model, "b6s") {
            val res = AgentResource(this, "worker", capacity = 2)
        }
        assertFalse(m.res.isOffShift, "a freshly-constructed resource is on-shift")
    }
}
