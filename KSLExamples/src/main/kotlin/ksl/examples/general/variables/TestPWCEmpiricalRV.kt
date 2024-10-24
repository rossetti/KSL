package ksl.examples.general.variables

import ksl.utilities.distributions.PWCEmpiricalCDF
import ksl.utilities.random.rvariable.PWCEmpiricalRV

fun main() {
    testPWCEmpiricalRV()
    testPWCEmpiricalCDF()
}

fun testPWCEmpiricalRV() {
    val b = doubleArrayOf(0.0, 0.8, 1.24, 1.45, 1.83, 2.76)
    val rv = PWCEmpiricalRV(b)
    for (i in 1..20) {
        println(rv.value)
    }
}

fun testPWCEmpiricalCDF() {
    val b = doubleArrayOf(0.25, 0.5, 1.0, 1.5, 2.0)
    val p = doubleArrayOf(0.31, 0.10, 0.25, 0.34)
    val pwc = PWCEmpiricalCDF(b, p)
    println(pwc)
    println()
    for (i in 1..30) {
        val x = i / 10.0
        val cp = pwc.cdf(x)
        val iCDF = pwc.invCDF(cp)
        println("F($x) = $cp and invF($cp) = $iCDF")
    }

    val params = pwc.parameters()
    println(params.joinToString())
    pwc.parameters(params)

    println()
    println(pwc)
}