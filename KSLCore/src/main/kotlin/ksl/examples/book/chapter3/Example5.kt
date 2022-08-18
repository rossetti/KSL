package ksl.examples.book.chapter3

import ksl.utilities.random.rvariable.KSLRandom

/**
 * This example illustrates that the user can use the static methods
 * of KSLRandom to generate from any of the defined random variables
 * as simple function calls.
 */
fun main() {
    val v = KSLRandom.rUniform(10.0, 15.0) // generate a U(10, 15) value
    val x = KSLRandom.rNormal(5.0, 2.0) // generate a Normal(mu=5.0, var= 2.0) value
    val n = KSLRandom.rPoisson(4.0).toDouble() //generate from a Poisson(mu=4.0) value
    System.out.printf("v = %f, x = %f, n = %f %n", v, x, n)
}
