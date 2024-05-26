package ksl.examples.book.chapter2

import kotlin.math.ln

/**
 * Example 2.7
 *
 * Consider a random variable, X, that represents the time until failure for a machine tool.
 * Suppose X is exponentially distributed with an expected value of 1.33.
 * Generate a random variate for the time until the first failure using a uniformly distributed value of u = 0.7.
 */
fun main() {
    val u = 0.7
    val mean = 1.333333
    val x = rExpo(mean, u)
    println("Generated X = $x")
}

fun rExpo(mean: Double, u: Double) : Double {
    return -mean*ln(1.0 - u)
}