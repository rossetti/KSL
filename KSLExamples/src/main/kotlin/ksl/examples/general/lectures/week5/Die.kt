package ksl.examples.general.lectures.week5

import ksl.utilities.random.rvariable.KSLRandom

fun main(){
    val d1 = Die(12)

    for (i in 1..5) {
        println("The die roll is ${d1.roll()}")
    }
    println()
    val tsd = TwoSixSidedDice()
    for (i in 1..5) {
        println("The dice roll was ${tsd.roll()}")
    }
}

interface RollIfc {
    fun roll(): Int
}

class Die(sides:Int = 6) : RollIfc {
    init {
        require(sides > 0) { "sides must be positive." }
    }
    val numberOfSides = sides

    private val myRNS = KSLRandom.nextRNStream()

    override fun roll(): Int {
        //myRNS.advanceToNextSubStream()
        return myRNS.randInt(1, numberOfSides)
    }
}

class TwoSixSidedDice : RollIfc {

    private val myDice = listOf(Die(6), Die(6))
    override fun roll(): Int {
        var sum = 0
        for(die in myDice){
            sum = sum + die.roll()
        }
        return sum
    }
}