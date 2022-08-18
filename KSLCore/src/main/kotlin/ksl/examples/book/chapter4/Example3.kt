package ksl.examples.book.chapter4

import ksl.utilities.random.rvariable.BinomialRV
import ksl.utilities.statistic.IntegerFrequency

/**
 * This example illustrates how to create an instance of an IntegerFrequency
 * class in order to tabulate the frequency of occurrence of integers within
 * a sample.
 */
fun main() {
    val f = IntegerFrequency("Frequency Demo")
    val bn = BinomialRV(0.5, 100)
    val sample = bn.sample(10000)
    f.collect(sample)
    println(f)
}
