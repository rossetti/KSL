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

import ksl.utilities.random.rng.RNStreamProvider


/**
 * Example 2.1
 *
 * This example illustrates how to make a stream provider, get streams
 * from the provider, and use the streams to generate pseudo-random
 * numbers.
 */
fun main() {
    // make a provider for creating streams
    val p1 = RNStreamProvider()
    // get the first stream from the provider
    val p1s1 = p1.rnStream(1)
    // make another provider, the providers are identical
    val p2 = RNStreamProvider()
    // thus the first streams returned are identical
    val p2s1 = p2.rnStream(1)
    print(String.format("%3s %15s %15s %n", "n", "p1s1", "p2s2"))
    for (i in 1..10) {
        print(String.format("%3d %15f %15f %n", i, p1s1.randU01(), p2s1.randU01()))
    }
}