package ksl.controls.experiments

import ksl.utilities.Identity


class CentralCompositeDesign(
    factors: Set<TwoLevelFactor>,
    val factorialReps: Int = 1,
    val axialReps: Int = 1,
    val centerReps: Int =  1,
    val axialSpacing: Double,
    name: String? = null
) : Identity(name), ExperimentalDesignIfc {

    private val myDesign : ExperimentalDesign = ExperimentalDesign(factors)

    private val myTwoLevelFactorialDesign = TwoLevelFactorialDesign(factors)

    val numFactors: Int
        get() = myDesign.factors.size

    override val factors: Map<String, Factor>
        get() = myDesign.factors

    override val factorNames: List<String>
        get() = myDesign.factorNames

    val numFactorialPoints: Int

    init {
        require(factorialReps > 0) { "Number of factorial replications must be > 0" }
        require(axialReps > 0) { "Number of axial replications must be > 0" }
        require(centerReps > 0) { "Number of center replications must be > 0" }
        require(axialSpacing > 0.0) { "The axial spacing must be > 0" }
        // fill the design with the factorial points
        val ptItr = myTwoLevelFactorialDesign.iterator()
        numFactorialPoints = fillFactorialPoints(ptItr)
        //TODO fill the axial points

        //make the center point with the appropriate number of replications
        myDesign.addDesignPoint(centerPoint(), centerReps)
    }

    private fun fillFactorialPoints(ptItr: DesignPointIteratorIfc) : Int {
        var pCount = 0
        while (ptItr.hasNext()) {
            val dp = ptItr.next()
            myDesign.addDesignPoint(dp.settings, factorialReps)
            pCount++
        }
        return pCount
    }

    val numAxialPoints: Int
        get() = 2*numFactorialPoints

    fun centerPoint() : DoubleArray {
        val list = mutableListOf<Double>()
        for (factor in factors.values){
            list.add(factor.midPoint)
        }
        return list.toDoubleArray()
    }

    override fun designIterator(replications: Int): DesignPointIteratorIfc {
        return myDesign.designIterator(replications)
    }

    override fun iterator(): Iterator<DesignPoint> {
        return myDesign.iterator()
    }
}