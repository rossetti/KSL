package examplepkg

import ksl.utilities.random.rvariable.ExponentialRV

fun main(){

    var rv = ExponentialRV(10.0)

    for(i in 1..10){
        println(rv.value)
    }
    println(rv.previous)

    val xs = rv.sample(20)


}