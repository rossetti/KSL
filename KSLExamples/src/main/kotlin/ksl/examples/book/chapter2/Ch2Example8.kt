package ksl.examples.book.chapter2

import kotlin.math.floor
import kotlin.math.ln

/**
 * Example 2.8
 *
Use the following pseudo-random numbers u_{1} = 0.35, u_{2} = 0.64,
u_{3} = 0.14, generate a random variate from a shifted negative binomial distribution
having parameters r=3 and p= 0.3.
 */
fun main() {
    val u1 = 0.35
    val u2 = 0.64
    val u3 = 0.14
    val p = 0.3
    val x1 = rGeom(p, u1)
    val x2 = rGeom(p, u2)
    val x3 = rGeom(p, u3)
    val x = x1 + x2 + x3
    println("Generated X = $x")
}

fun rGeom(p: Double, u: Double): Double {
    val n = ln(1.0 - u)
    val d = ln(1.0 - p)
    return 1.0 + floor(n / d)
}