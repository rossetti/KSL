package ksl.examples.book.chapter3

import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.randomlySelect

/**
 * This example illustrates how to use the randomlySelect() method
 * of the KSLRandom class to randomly select from a list. The extension
 * function for lists can also be used.
 */
fun main() {
    // create a list
    val strings = listOf("A", "B", "C", "D")
    // randomly pick from the list, with equal probability
    for (i in 1..5) {
        println(KSLRandom.randomlySelect(strings))
    }
    println()
    for (i in 1..5) {
        println(strings.randomlySelect())
    }
}
