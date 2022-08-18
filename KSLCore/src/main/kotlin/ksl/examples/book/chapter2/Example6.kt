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