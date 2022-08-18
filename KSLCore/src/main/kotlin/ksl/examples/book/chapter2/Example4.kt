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