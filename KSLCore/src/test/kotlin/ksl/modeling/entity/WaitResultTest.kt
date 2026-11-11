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

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  The rules of the two result types, independent of any wait: what a satisfied and a timed-out
 *  result report, and how reading a value is refused when there is none.
 */
class WaitResultTest {

    /** A trigger that is never used in a wait; only its identity matters here. */
    private class InertTrigger<T> : WaitTrigger<T>() {
        override fun canFireNow(entity: ProcessModel.Entity): Boolean = false
        override fun fireNow(process: ProcessModel.Entity.ProcessCoroutine): T = error("not used")
        override fun register(group: WaitGroup): Registration = error("not used")
    }

    @Test
    @DisplayName("a satisfied result has its value; a timed-out one refuses value and gives null")
    fun waitResult() {
        val satisfied: WaitResult<String> = WaitResult.Satisfied("x", 2.5)
        assertFalse(satisfied.timedOut)
        assertEquals("x", satisfied.value)
        assertEquals("x", satisfied.valueOrNull())
        assertEquals(2.5, satisfied.timeWaited)

        val timedOut: WaitResult<String> = WaitResult.TimedOut(4.0)
        assertTrue(timedOut.timedOut)
        assertEquals(4.0, timedOut.timeWaited)
        assertNull(timedOut.valueOrNull())
        assertThrows<IllegalStateException> { timedOut.value }
    }

    @Test
    @DisplayName("a waitForFirst result gives the winner's value and refuses the others")
    fun waitForFirstResult() {
        val a = InertTrigger<Int>()
        val b = InertTrigger<String>()
        val stranger = InertTrigger<Int>()
        val r = WaitForFirstResult(listOf(a, b), a, 7, 1.0)
        assertFalse(r.timedOut)
        assertTrue(r.firedBy(a))
        assertFalse(r.firedBy(b))
        assertEquals(7, r.valueOf(a))
        assertEquals(7, r.valueOrNull(a))
        assertNull(r.valueOrNull(b))
        assertThrows<IllegalStateException> { r.valueOf(b) }
        assertThrows<IllegalArgumentException> { r.valueOf(stranger) }
        assertThrows<IllegalArgumentException> { r.valueOrNull(stranger) }
    }

    @Test
    @DisplayName("a timed-out waitForFirst result has no trigger and no values")
    fun waitForFirstTimedOut() {
        val a = InertTrigger<Int>()
        val r = WaitForFirstResult(listOf(a), null, null, 3.0)
        assertTrue(r.timedOut)
        assertNull(r.trigger)
        assertFalse(r.firedBy(a))
        assertNull(r.valueOrNull(a))
        assertThrows<IllegalStateException> { r.valueOf(a) }
    }
}
