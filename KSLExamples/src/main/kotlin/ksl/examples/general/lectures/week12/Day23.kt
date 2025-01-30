package ksl.examples.general.lectures.week12

import ksl.modeling.elements.REmpiricalList
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV


fun main() {

}

class Person(val age: Int, val company: String)

fun mapExample(){
     val map = mutableMapOf<String, Person>()
     val p1 = Person(62, "UA")
     val p2 = Person(30, "DoJ")
     map["Manuel"] = p1
     map["Joe"] = p2
     val person: Person? = map["Manuel"]
     val anotherMap = mutableMapOf(
         "Manuel" to p1,
         "Joe" to p2
     )

}

class System(parent: ModelElement, name: String?) : ProcessModel(parent, name) {

    val r1 = RandomVariable(this, ExponentialRV(2.0))
    val r2 = RandomVariable(this, ExponentialRV(12.0))
    val r3 = RandomVariable(this, ExponentialRV(20.0))

    val cdf = doubleArrayOf(0.2, 0.8, 1.0)
    val list = listOf(r1, r2, r3)
    val rList = REmpiricalList<RandomVariable>(this, list, cdf)

    val rs1 = Response(this, "Response1")
    val rs2 = Response(this, "Response2")
    val rs3 = Response(this, "Response3")

    val rvMap = mapOf(
        "One" to r1,
        "Two" to r2,
        "Three" to r3,
    )

    val rsMap = mapOf(
        "One" to rs1,
        "Two" to rs2,
        "Three" to rs3,
    )

    init {
        //creates the line
        
    }
    private inner class AssemblyLine(
        system: System,
        name: String?
    ) : ModelElement(system, name) {

        val station1 = ResourceWithQ(this, "${this.name}:Station1")
        val station2 = ResourceWithQ(this, "${this.name}:Station2")
        val station3 = ResourceWithQ(this, "${this.name}:Station3")

        override fun initialize() {
            super.initialize()
        }
    }

    private val line1 = AssemblyLine(this, "Line1")
    private val line2 = AssemblyLine(this, "Line2")
    private val line3 = AssemblyLine(this, "Line3")


    override fun initialize() {

        val rv: RandomVariable = rList.randomElement
        val pt  = rv.value

        val part1 = Part("One")
    }

    private inner class Part(val type: String) : Entity() {

        val someProcess = process {

            delay(rvMap[type]!!)
            val st = time - createTime
            rsMap[type]!!.value = st
        }
    }
}