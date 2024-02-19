package ksl.examples.general.misc

import org.jetbrains.letsPlot.commons.intern.math.ipow

fun main(){
    val twos = IntArray(10){ (2).ipow(it+3).toInt() }
    println(twos.joinToString())
}

fun chunky(){
    val r = 1..40
    val chunks: List<List<Int>> = r.chunked(39)
    println(chunks)

    println(1..39)
}