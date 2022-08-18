package ksl.examples.book.chapter4

import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.statistic.Histogram

/**
 * This example illustrates how to make an instance of a Histogram
 * and use it to collect statistics on a randomly generated sample.
 */
fun main() {
    val d = ExponentialRV(2.0)
    val h = Histogram.create(0.0, 20, 0.1)
    for (i in 1..100) {
        h.collect(d.value)
    }
    println(h)
}