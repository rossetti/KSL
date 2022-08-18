package ksl.examples.book.chapter3

import ksl.utilities.random.robj.DPopulation
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.permute

/**
 * This example illustrates how to define a population of
 * values (DPopulation) and use it to perform sampling operations
 * such as random samples and permutations.  Similar functionality
 * is also demonstrated by directly using the static methods of
 * the KSLRandom class.
 */
fun main() {
    // create an array to hold a population of values
    val y = DoubleArray(10)
    for (i in 0..9) {
        y[i] = (i + 1).toDouble()
    }

    // create the population
    val p = DPopulation(y)
    println(p.contentToString())

    println("Print the permuted population")
    // permute the population
    p.permute()
    println(p.contentToString())

    // directly permute the array using KSLRandom
    println("Permuting y")
    KSLRandom.permute(y)
    println(y.contentToString())

    // sample from the population
    val x = p.sample(5)
    println("Sampling 5 from the population")
    println(x.contentToString())

    // create a string list and permute it
    val strList: MutableList<String> = ArrayList()
    strList.add("a")
    strList.add("b")
    strList.add("c")
    strList.add("d")
    println("The mutable list")
    println(strList)
    KSLRandom.permute(strList)
    println("The permuted list")
    println(strList)
    println("Permute using extension function")
    strList.permute()
    println(strList)
}