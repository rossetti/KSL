package ksl.examples.book.chapter3

import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.NormalRV

/**
 * This example illustrates how to use the classes within the rvariable package.
 * Specifically, a Normal(mean=20, variance=4.0) random variable is
 * created and values are obtained via the getValue() method.
 *
 *
 * In this case, stream 3 is used to generate from the random variable.
 */
fun main() {
    // get stream 3
    val stream = KSLRandom.rnStream(3)
    // create a normal mean = 20.0, variance = 4.0, with the stream
    val n = NormalRV(20.0, 4.0, stream)
    System.out.printf("%3s %15s %n", "n", "Values")
    for (i in 1..5) {
        // value property returns generated values
        val x = n.value
        System.out.printf("%3d %15f %n", i, x)
    }
}