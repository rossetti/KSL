package ksl.examples.book.chapter5

import ksl.utilities.random.rvariable.UniformRV
import ksl.utilities.statistic.Statistic

/**
 * This example illustrates how to perform simple Monte-Carlo
 * integration on the sqrt(x) over the range from 1 to 4.
 */
fun main() {
    val a = 1.0
    val b = 4.0
    val ucdf = UniformRV(a, b)
    val stat = Statistic("Area Estimator")
    val n = 100 // sample size
    for (i in 1..n) {
        val x = ucdf.value
        val gx = Math.sqrt(x)
        val y = (b - a) * gx
        stat.collect(y)
    }
    System.out.printf("True Area = %10.3f %n", 14.0 / 3.0)
    System.out.printf("Area estimate = %10.3f %n", stat.average)
    println("Confidence Interval")
    println(stat.confidenceInterval)
}