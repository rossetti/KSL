package ksl.examples.general.lectures.week3

import ksl.utilities.random.rvariable.UniformRV
import ksl.utilities.statistic.Statistic
import kotlin.math.sqrt

fun main() {
    println("Standard Monte-Carlo Estimator")
    estimatePI()
    println()
    println("Hit-Or-Miss Estimator")
    hitOrMiss()
}

fun hitOrMiss(){
    val a = 0.0
    val b = 1.0
    val ucdf = UniformRV(a, b)
    val stat = Statistic("Area Estimator")
    val n = 1000 // sample size
    for (i in 1..n) {
        val x = ucdf.value
        val y = ucdf.value
        val fx = sqrt(1-x*x)
        stat.collect(y <= fx)
    }
    System.out.printf("True Area = %10.3f %n", Math.PI/4.0)
    System.out.printf("Area estimate = %10.3f %n", stat.average)
    println("Confidence Interval")
    println(stat.confidenceInterval)
    println("Half-width = ${stat.halfWidth}")
}

fun estimatePI(){
    val a = 0.0
    val b = 1.0
    val ucdf = UniformRV(a, b)
    val stat = Statistic("Area Estimator")
    val n = 1000 // sample size
    for (i in 1..n) {
        val x1 = ucdf.value
        val gx = sqrt(1-x1*x1)
        val y = (b - a) * gx
        stat.collect(y)
    }
    System.out.printf("True Area = %10.3f %n", Math.PI/4.0)
    System.out.printf("Area estimate = %10.3f %n", stat.average)
    println("Confidence Interval")
    println(stat.confidenceInterval)
    println("Half-width = ${stat.halfWidth}")
}