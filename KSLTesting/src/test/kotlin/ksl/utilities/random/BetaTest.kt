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

package ksl.utilities.random

import jsl.utilities.random.rvariable.JSLRandom
import ksl.utilities.random.rvariable.BetaRV
import ksl.utilities.random.rvariable.KSLRandom
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BetaTest {

    var bKSL: BetaRV? = null
    var bJSL: jsl.utilities.random.rvariable.BetaRV? = null
    @BeforeEach
    fun setUp() {
//        bJSL = jsl.utilities.random.rvariable.BetaRV(2.4, 5.5, 1)
//        bKSL = BetaRV(2.4, 5.5, 1)
    }

    @Test
    fun test1() {
        for (i in 1..10){
            val a = KSLRandom.rBeta(2.4, 5.5, 1)
            val b = JSLRandom.rBeta(2.4, 5.5, 1)
            println("KSL a = $a  JSL b = $b")
            Assertions.assertTrue(a == b)
        }
    }
}