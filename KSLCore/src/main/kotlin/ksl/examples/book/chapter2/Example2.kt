package ksl.examples.book.chapter2

import ksl.utilities.random.rvariable.KSLRandom


/**
 * This example illustrates how to use JSLRandom to get and use streams.
 * The example illustrates how to advance a stream to it's next sub-stream,
 * how to reset the stream back to its beginning.
 */
fun main() {
    val s1 = KSLRandom.defaultRNStream()
    println("Default stream is stream 1")
    println("Generate 3 random numbers")
    for (i in 1..3) {
        println("u = " + s1.randU01())
    }
    s1.advanceToNextSubStream()
    println("Advance to next sub-stream and get some more random numbers")
    for (i in 1..3) {
        println("u = " + s1.randU01())
    }
    println("Notice that they are different from the first 3.")
    s1.resetStartStream()
    println("Reset the stream to the beginning of its sequence")
    for (i in 1..3) {
        println("u = " + s1.randU01())
    }
    println("Notice that they are the same as the first 3.")
    println("Get another random number stream")
    val s2 = KSLRandom.nextRNStream()
    println("2nd stream")
    for (i in 1..3) {
        println("u = " + s2.randU01())
    }
    println("Notice that they are different from the first 3.")
}