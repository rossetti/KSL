/*
 * Copyright (c) 2018. Manuel D. Rossetti, rossetti@uark.edu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package ksl.examples.general.variables.nhpp

import ksl.modeling.nhpp.PiecewiseConstantRateFunction
import ksl.modeling.nhpp.PiecewiseLinearRateFunction
import ksl.modeling.nhpp.PiecewiseRateFunction
import ksl.utilities.random.rvariable.ExponentialRV


fun main() {
//    testPiecewiseConstantRateFunction1();
//        testPiecewiseConstantRateFunction2();
//        testPiecewiseConstantRateFunction3();
//        testPiecewiseConstantRateFunction4();
//        testPiecewiseConstantRateFunction5();
        testPiecewiseLinearRateFunction1();
}

fun testPiecewiseLinearRateFunction1() {
    val ar = doubleArrayOf(0.5, 0.5, 0.9, 0.9, 1.2, 0.9, 0.5)
    val dd = doubleArrayOf(200.0, 400.0, 400.0, 200.0, 300.0, 500.0)

    val f = PiecewiseLinearRateFunction(dd, ar)

    println("Rates")
    val rates: DoubleArray = f.rates
    for (rr in rates) {
        println("rate = $rr")
    }
    println("Durations")
    val durations: DoubleArray = f.durations
    for (rr in durations) {
        println("duration = $rr")
    }
    println("-----")
    println("intervals")
    System.out.println(f)

    // check the rate function
    println("-----")
    println("rate function")
    val mt: Double = f.timeRangeUpperLimit
    run {
        var t = 0.0
        while (t < mt) {
            println("rate($t)= ${f.rate(t)}")
            t = t + 1
        }
    }
    println("-----")
    println("cumulative rate function")
    // check the cumulative rate function
    var t = 0.0
    while (t < mt) {
        println("cum rate($t)= ${f.cumulativeRate(t)}")
        t = t + 1
    }
    println("-----")
    println("inverse cumulative rate function")
    // check the cumulative rate function
    val mr: Double = f.cumulativeRateRangeUpperLimit
    var r = 0.0
    while (r <= mr) {
        println("inv cum rate(" + r + ")= " + f.inverseCumulativeRate(r))
        r = r + 1
    }
}


fun testPiecewiseConstantRateFunction1() {
    val d = doubleArrayOf(15.0, 20.0, 15.0)
    val ar = doubleArrayOf(1.0, 2.0, 1.0)
    val f = PiecewiseConstantRateFunction(d, ar)
    println("-----")
    println("intervals")
    System.out.println(f)

    // check the rate function
    println("-----")
    println("rate function")
    val mt: Double = f.timeRangeUpperLimit
    var t = 0.0
    while (t < mt) {
        println("rate($t)= ${f.rate(t)}")
        t = t + 1
    }
    println("-----")
    println("cumulative rate function")
    // check the cumulative rate function
    t = 0.0
    while (t < mt) {
        println("cum rate($t)= ${f.cumulativeRate(t)}")
        t = t + 1
    }
    println("-----")
    println("inverse cumulative rate function")
    // check the cumulative rate function
    val mr: Double = f.cumulativeRateRangeUpperLimit
    var r = 0.0
    while (r <= mr) {
        println("inv cum rate(" + r + ")= " + f.inverseCumulativeRate(r))
        r = r + 1
    }
}

fun testPiecewiseConstantRateFunction2() {
    val d = doubleArrayOf(15.0, 20.0, 15.0, 20.0, 15.0)
    val ar = doubleArrayOf(1.0, 0.0, 1.0, 0.0, 1.0)
    val f: PiecewiseRateFunction = PiecewiseConstantRateFunction(d, ar)
    println("-----")
    println("intervals")
    System.out.println(f)

    // check the rate function
    println("-----")
    println("rate function")
    val mt: Double = f.timeRangeUpperLimit
    var t = 0.0
    while (t < mt) {
        println("rate(" + t + ")= " + f.rate(t))
        t = t + 1
    }
    println("-----")
    println("cumulative rate function")
    // check the cumulative rate function
    t = 0.0
    while (t < mt) {
        println("cum rate(" + t + ")= " + f.cumulativeRate(t))
        t = t + 1
    }
    println("-----")
    println("inverse cumulative rate function")
    // check the cumulative rate function
    val mr: Double = f.cumulativeRateRangeUpperLimit
    var r = 0.0
    while (r <= mr) {
        println("inv cum rate(" + r + ")= " + f.inverseCumulativeRate(r))
        r = r + 1
    }
}

fun testPiecewiseConstantRateFunction3() {
    val d = doubleArrayOf(15.0, 20.0, 15.0)
    val ar = doubleArrayOf(1.0, 2.0, 1.0)
    val f = PiecewiseConstantRateFunction(d, ar)
    println("-----")
    println("intervals")
    System.out.println(f)

    // check the rate function
    println("-----")
    println("Generate non-stationary Poisson process.")
    var a = 0.0
    var y = 0.0
    val tau: Double = f.cumulativeRateRangeUpperLimit
    val e = ExponentialRV(1.0)
    var n = 0
    y = y + e.value
    while (y < tau) {
        a = f.inverseCumulativeRate(y)
        n++
        println("a[$n] = $a")
        y = y + e.value()
    }
}

fun testPiecewiseConstantRateFunction4() {
    val d = doubleArrayOf(15.0, 20.0, 15.0)
    val r = doubleArrayOf(1.0, 2.0, 1.0)
    val f = PiecewiseConstantRateFunction(d, r)
    println("-----")
    println("intervals")
    System.out.println(f)

    // check the rate function
    println("-----")
    println("Generate non-stationary Poisson process.")
    var a = 0.0
    var y = 0.0
    val tau: Double = f.cumulativeRateRangeUpperLimit
    val e = ExponentialRV(1.0)
    var n = 0
    y = y + e.value
    while (y < tau) {
        a = f.inverseCumulativeRate(y)
        n++
        println("a[$n] = $a")
        y = y + e.value
    }
}

fun testPiecewiseConstantRateFunction5() {
    val d = doubleArrayOf(15.0, 20.0, 15.0)
    val ar = doubleArrayOf(1.0, 2.0, 1.0)
    val f = PiecewiseConstantRateFunction(d, ar)
    println("-----")
    println("intervals")
    System.out.println(f)
    println("Rates")
    val rates: DoubleArray = f.rates
    for (r in rates) {
        println("rate = $r")
    }
    println("-----")
    val pc = f.instance(2.0)
    println("After multiplication by 2")
    System.out.println(pc)
    println("Rates")
    val nr = pc.rates
    for (r in nr) {
        println("rate = $r")
    }
}
