package examplepkg

class TestFormatting {
}

fun main(){
    println("%10s".format("Manuel David Rossetti"))
    val width = 10
    val fmt = "%${width}s"
    println(fmt.format("special"))
}