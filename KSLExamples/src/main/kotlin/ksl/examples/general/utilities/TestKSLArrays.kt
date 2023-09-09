package ksl.examples.general.utilities

import ksl.utilities.diff
import ksl.utilities.lag

class TestKSLArrays {
}

fun main() {
    testDiffAndLag()
}

fun testDiffAndLag() {
//    val x = doubleArrayOf(1.0 / 6.0, 3.0 / 6.0, 5.0 / 6.0, 1.0)
    val x = doubleArrayOf(1.0, 3.0, 5.0, 6.0)
    println("x = " + x.joinToString())

    val d1 = x.diff(1)
    println("d1 = " + d1.joinToString())

    val x1 = x.lag(1)
    println("x1 = " + x1.joinToString())

    val d2 = x.diff(2)
    println("d2 = " + d2.joinToString())

    val x2 = x.lag(2)
    println("x2 = " + x2.joinToString())
}