/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

import jsl.utilities.math.JSLMath
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class TestKSLMath {

    @BeforeEach
    fun setUp() {
        println("Testing KSLMath.")
    }

    @AfterEach
    fun tearDown() {
    }

    @Test
    fun testAgainstJSL(){
        println(KSLMath)
        println()
        JSLMath.printParameters(System.out)
        println()
        assert(JSLMath.binomialCoefficient(10,3) == KSLMath.binomialCoefficient(10, 3))
        println()
        assert(JSLMath.logFactorial(100) == KSLMath.logFactorial(100))
        println()
        assert(JSLMath.factorial(100) == KSLMath.factorial(100))
    }
}