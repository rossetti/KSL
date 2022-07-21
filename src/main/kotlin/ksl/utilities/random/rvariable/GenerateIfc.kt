package ksl.utilities.random.rvariable

/**
 *  A SAM for specifying the function generate() of random variables
 */
fun interface GenerateIfc {

    fun generate(): Double
}