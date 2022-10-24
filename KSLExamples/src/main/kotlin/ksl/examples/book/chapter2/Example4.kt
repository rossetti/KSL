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

package ksl.examples.book.chapter2

import ksl.utilities.random.rvariable.KSLRandom

/**
 * This example illustrates how to reset a stream back to its
 * starting point in its sequence and thus reproduce the same
 * sequence of pseudo-random numbers. This is an alternative
 * method for performing common random numbers.
 */
fun main() {
    val s = KSLRandom.defaultRNStream()
    // generate regular
    System.out.printf("%3s %15s %n", "n", "U")
    for (i in 1..3) {
        val u = s.randU01()
        System.out.printf("%3d %15f %n", i, u)
    }
    // reset the stream and generate again
    s.resetStartStream()
    println()
    System.out.printf("%3s %15s %n", "n", "U again")
    for (i in 1..3) {
        val u = s.randU01()
        System.out.printf("%3d %15f %n", i, u)
    }
}