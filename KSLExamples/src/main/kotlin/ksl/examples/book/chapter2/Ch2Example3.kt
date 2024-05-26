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
 * Example 2.3
 *
 * This example illustrates how to clone an instance of a stream.
 * This will produce a new stream that has the same underlying state
 * as the current stream and thus will produce exactly the same
 * sequence of pseudo-random numbers. This is one approach
 * for implementing common random numbers.
 */
fun main() {
    // get the default stream
    val s = KSLRandom.defaultRNStream()
    // make a clone of the stream
    val clone = s.instance()
    print(String.format("%3s %15s %15s %n", "n", "U", "U again"))
    for (i in 1..3) {
        print(String.format("%3d %15f %15f %n", i, s.randU01(), clone.randU01()))
    }
}