package examplepkg

import ksl.utilities.KSLArrays
import ksl.utilities.random.permute
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.DUniformRV
import ksl.utilities.random.rvariable.ExponentialRV

fun main(){

    val a: DoubleArray = DoubleArray(0)
    a.forEach {println(it)}
    val b : Array<Double> = a.toTypedArray()
    b.forEach {println(it)}

    val rv = ExponentialRV(10.0)

    for(i in 1..10){
        println(rv.value)
    }
    println(rv.previous)

    val xs = rv.sample(20)

    val r: IntRange = 1..20

    val du = DUniformRV(r)
    for(i in 1..10){
        println(du.value)
    }

    val s = rv.sample(5)
    println(s.contentToString())
    s.permute()
    println()

    println(s.contentToString())

    val crv = ConstantRV(10.0)
    println(crv)
    println(crv.value)
    crv.constVal = 12.0
    println(crv)
    println(crv.value)
    crv.constVal = 22.0
    println(crv)
    println(crv.value)

    val ar = arrayOf(
        doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.0),
        doubleArrayOf(0.0, 0.0, 0.0, 1.0, 1.0),
        doubleArrayOf(0.0, 0.0, 1.0, 1.0, 1.0),
        doubleArrayOf(0.0, 0.0, 0.0, 1.0, 1.0),
        doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.0)
    )

    var nr = KSLArrays.copy2DArray(ar)

    for (array in nr){
        for (value in array){
            print("$value ")
        }
        println()
    }
}
