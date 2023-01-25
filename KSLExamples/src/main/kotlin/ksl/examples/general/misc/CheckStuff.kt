package ksl.examples.general.misc

fun main(){
    val r = 1..40
    val chunks: List<List<Int>> = r.chunked(39)
    println(chunks)
}