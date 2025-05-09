package ksl.examples.general.rvariable

import ksl.utilities.Interval
import ksl.utilities.distributions.TruncatedNormal
import ksl.utilities.random.rvariable.TruncatedNormalRV

fun main(){
    exampleTT1()
}

fun exampleTT1(){
    val tn = TruncatedNormal(0.0, 1.0, Interval(-0.5, 0.5))

    println(tn)
    println()
    val tnRV = tn.randomVariable()

    for(i in 1..10){
        println(tnRV.value)
    }

    println()
    println(tnRV)

    println()

    tnRV.resetStartStream()
    val rv = TruncatedNormalRV(0.0, 1.0, Interval(-0.5, 0.5), tnRV.streamNumber)
    for(i in 1..10){
        println(rv.value)
    }
}