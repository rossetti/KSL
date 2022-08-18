package ksl.examples.book.chapter3

import ksl.utilities.random.rvariable.NormalRV


/**
 * This example illustrates how to use the classes within the rvariable package.
 * Specifically, a Normal(mean=20, variance=4.0) random variable is
 * created and values are obtained via the value property
 */

fun main() {
    // create a normal mean = 20.0, variance = 4.0 random variable
    val n = NormalRV(20.0, 4.0)
    System.out.printf("%3s %15s %n", "n", "Values")
    // generate some values
    for (i in 1..5) {
        // getValue() method returns generated values
        val x = n.value
        System.out.printf("%3d %15f %n", i, x)
    }
}
