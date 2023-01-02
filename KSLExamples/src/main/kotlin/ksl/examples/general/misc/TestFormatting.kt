package ksl.examples.general.misc

import ksl.utilities.distributions.StudentT

class TestFormatting {
}

fun main(){
    println("%10s".format("Manuel David Rossetti"))
    val width = 10
    val fmt = "%${width}s"
    println(fmt.format("special"))

    val level = 0.95
    val dof = 5.0
    val alpha = 1.0 - level
    val p = 1.0 - alpha / 2.0
    val t: Double = StudentT.invCDF(dof, p)
    println("p = $p dof = $dof t-value = $t")
}