package ksl.utilities.random.robj

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom

class DUniformList<T>(elements: MutableList<T> = mutableListOf(), stream: RNStreamIfc = KSLRandom.nextRNStream()) :
    RList<T>(elements, stream) {

    override val randomElement: T
        get() = elements[rnStream.randInt(0, elements.size - 1)]

}

fun main() {
    val originList: DUniformList<String> = DUniformList()

    originList.add("KC")
    originList.add("CH")
    originList.add("NY")

    for (i in 1..10) {
        println(originList.randomElement)
    }

    println()
    val od: MutableMap<String, DUniformList<String>> = HashMap()

    val kcList: DUniformList<String> = DUniformList()

    kcList.add("CO")
    kcList.add("AT")
    kcList.add("NY")

    val chList: DUniformList<String> = DUniformList()

    chList.add("AT")
    chList.add("NY")
    chList.add("KC")

    val nyList: DUniformList<String> = DUniformList()

    nyList.add("AT")
    nyList.add("KC")
    nyList.add("CH")

    od["KC"] = kcList
    od["CH"] = chList
    od["NY"] = nyList

    for (i in 1..10) {
        val key = originList.randomElement
        val rs = od[key]!!
        println(rs.randomElement)
    }
}
