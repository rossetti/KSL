package ksl.examples.book.chapter2

import ksl.utilities.random.rvariable.KSLRandom

/**
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
    System.out.printf("%3s %15s %15s %n", "n", "U", "U again")
    for (i in 1..3) {
        System.out.printf("%3d %15f %15f %n", i, s.randU01(), clone.randU01())
    }
}