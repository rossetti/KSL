package ksl.examples.book.chapter2

import ksl.utilities.random.rvariable.KSLRandom

/**
 * This example illustrates how to create a new stream from
 * an existing stream such that the new stream produces the
 * antithetic pseudo-random numbers of the first stream.
 * That is if stream A produces u1, u2, .., then the
 * antithetic of stream A produces 1-u1, 1-u2, ....
 */
fun main() {
    // get the default stream
    val s = KSLRandom.defaultRNStream()
    // make its antithetic version
    val ans = s.antitheticInstance()
    System.out.printf("%3s %15s %15s %15s %n", "n", "U", "1-U", "sum")
    for (i in 1..5) {
        val u = s.randU01()
        val au = ans.randU01()
        System.out.printf("%3d %15f %15f %15f %n", i, u, au, u + au)
    }
}
