package ksl.examples.book.chapter3

import ksl.utilities.statistic.Statistic

/**
 *  Example 3.7
 *  Computing the sample size.
 */
fun main() {
    val desiredHW = 0.1
    val s0 = 6.0
    val level = 0.99
    val n = Statistic.estimateSampleSize(
        desiredHW = desiredHW,
        stdDev = s0,
        level = level
    )
    println("Sample Size Determination")
    println("desiredHW = $desiredHW")
    println("stdDev = $s0")
    println("Level = $level")
    println("recommended sample size = $n")
}