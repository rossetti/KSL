/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.utilities.math

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

internal class TestKSLMath {

    @BeforeEach
    fun setUp() {
        println("Testing KSLMath.")
    }

    @AfterEach
    fun tearDown() {
    }

    /**
     * Reference values captured from JSLCore R1.0.12 (the parity test
     * this method replaced). Pure deterministic functions, so the values
     * are stable forever.
     */
    @Test
    fun matchesFrozenReferenceValues() {
        assertEquals(120.0, KSLMath.binomialCoefficient(10, 3))
        assertEquals(363.73937555566835, KSLMath.logFactorial(100))
        assertEquals(9.332621545372994E157, KSLMath.factorial(100))
    }
}
