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

package ksl.examples.general.utilities

import ksl.utilities.random.rng.RNStreamFactory
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rvariable.BernoulliRV
import ksl.utilities.random.rvariable.KSLRandom


fun main() {

//    val range = 1.0.rangeTo(10.0)
//    println(range)
//    val start = 1.0
//    val end = -10.0
//    val r = start.rangeTo(end)
//    println(r)

//    testIt()
 //   test()
//    test1()
//    test2()
    test3()
}

fun test() {
    val rnp = RNStreamProvider()
    val defaultStream = rnp.defaultRNStream()
    for (i in 1..3) {
        println("u = " + defaultStream.randU01())
    }
    val f = RNStreamProvider()
    val s1 = f.defaultRNStream()
    println("default stream")
    for (i in 1..3) {
        println("u = " + s1.randU01())
    }
    s1.advanceToNextSubStream()
    println("advanced")
    for (i in 1..3) {
        println("u = " + s1.randU01())
    }
    s1.resetStartStream()
    println("reset")
    for (i in 1..3) {
        println("u = " + s1.randU01())
    }
    val s2 = f.nextRNStream()
    println("2nd stream")
    //TODO doesn't match JSL generator
    for (i in 1..3) {
        println("u = " + s2.randU01())
    }
}


fun testIt() {
    val p1 = RNStreamProvider()
    //val stream = p1.nextRNStream()
    val stream = p1.defaultRNStream()
    for (i in 0..8) {
        println(stream.randU01())
    }
    println()
    val stream2 = p1.nextRNStream()
    for (i in 0..8) {
        println(stream2.randU01())
    }
}

fun test1() {
    val rm = RNStreamFactory()
    val rng = rm.nextStream()
    var sum = 0.0
    val n = 1000
    for (i in 1..n) {
        sum = sum + rng.randU01()
    }
    println("-----------------------------------------------------")
    println("This test program should print the number   490.9254839801")
    println("Actual test result = $sum")
    check(sum==490.9254839801)
}

fun test2() {
    // test the advancement of streams
    val count = 100
    val advance = 20
    val rm = RNStreamFactory()
    rm.advanceSeeds(advance)
    val rng = rm.nextStream()
    var sum = 0.0
    for (i in 1..count) {
        val x = rng.randU01()
        sum = sum + x
//        println("$i   $x   $sum")
    }
    println("-----------------------------------------------------")
    println("This test program should print the number   55.445704270784404")
    println("Actual test result = $sum")
    check(sum == 55.445704270784404){
        "didn't match"
    }
}

fun test3() {
    // check stream assignment
    val rv = BernoulliRV(0.10, 5)
    println("rv was assigned stream : ${rv.streamNumber}")

    val last = KSLRandom.DefaultRNStreamProvider.lastRNStreamNumber()
    println()
    println("last = $last")
}
