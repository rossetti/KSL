package ksl.examples.book.chapter3

import ksl.utilities.random.rvariable.TriangularRV

/**
 * This example illustrates how to use the classes within the rvariable package.
 * Specifically, a Triangular( min = 2.0, mode = 5.0, max = 10.0) random variable is
 * created and values are obtained via the sample() method.
 */
fun main() {
    // create a triangular random variable with min = 2.0, mode = 5.0, max = 10.0
    val t = TriangularRV(2.0, 5.0, 10.0)
    // sample 5 values
    val sample = t.sample(5)
    System.out.printf("%3s %15s %n", "n", "Values")
    for (i in sample.indices) {
        System.out.printf("%3d %15f %n", i + 1, sample[i])
    }
}