package ksl.examples.general.lectures.week1

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProvider

fun main() {
//    println("Hello, World!")
//    hello(age = 62, name = "manuel")

    demoRandomness()
}

fun hello(name: String, age: Int, n: Int = 10){
    for(i in 1..n){
        println("Hello, $name, You are old = $age")
    }
}

fun demoRandomness(){
    val rp = RNStreamProvider()
    val s: RNStreamIfc = rp.nextRNStream()
    s.advanceToNextSubStream()
    for(i in 1..5){
        val u = s.randU01()
        println("random value = $u")
    }

}