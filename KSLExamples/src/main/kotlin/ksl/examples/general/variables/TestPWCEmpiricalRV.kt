package ksl.examples.general.variables

import ksl.utilities.random.rvariable.PWCEmpiricalRV

fun main(){
    val b = doubleArrayOf(0.0, 0.8, 1.24, 1.45, 1.83, 2.76)
    val rv = PWCEmpiricalRV(b)
    for(i in 1..20){
        println(rv.value)
    }
}