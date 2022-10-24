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

package ksl.examples.book.chapter2

import ksl.utilities.random.rvariable.KSLRandom

/**
 * This example illustrates another approach to producing
 * antithetic pseudo-random numbers using the same stream.
 * This approach resets the stream to its starting point and
 * then sets the antithetic option to true.
 * Before the reset, the stream produces u1, u2, u3,...
 * After the reset and turning the antithetic option on,
 * the stream produces 1-u1, 1-u2, 1-u3, ...
 */
fun main() {
    val s = KSLRandom.defaultRNStream()
    s.resetStartStream()
    // generate regular
    System.out.printf("%3s %15s %n", "n", "U")
    for (i in 1..5) {
        val u = s.randU01()
        System.out.printf("%3d %15f %n", i, u)
    }
    // generate antithetic
    s.resetStartStream()
    s.antithetic = true
    println()
    System.out.printf("%3s %15s %n", "n", "1-U")
    for (i in 1..5) {
        val u = s.randU01()
        System.out.printf("%3d %15f %n", i, u)
    }
}