package ksl.controls.experiments

import ksl.utilities.Identity


class CentralCompositeDesign(
    factors: Set<TwoLevelFactor>,
    val numFactorialReps: Int = 1,
    val numAxialReps: Int = 1,
    val numCenterReps: Int = 1,
    val axialSpacing: Double,
    name: String? = null
) : Identity(name), ExperimentalDesignIfc {

    private val myDesign: ExperimentalDesign = ExperimentalDesign(factors)

    private val myTwoLevelFactorialDesign = TwoLevelFactorialDesign(factors)

    val numFactors: Int
        get() = myDesign.factors.size

    override val factors: Map<String, Factor>
        get() = myDesign.factors

    override val factorNames: List<String>
        get() = myDesign.factorNames

    val numFactorialPoints: Int

    val numAxialPoints: Int
        get() = 2 * numFactors

    init {
        require(numFactorialReps > 0) { "Number of factorial replications must be > 0" }
        require(numAxialReps > 0) { "Number of axial replications must be > 0" }
        require(numCenterReps > 0) { "Number of center replications must be > 0" }
        require(axialSpacing > 0.0) { "The axial spacing must be > 0" }
        // fill the design with the factorial points
        val ptItr = myTwoLevelFactorialDesign.iterator()
        numFactorialPoints = makeFactorialPoints(ptItr)
        //make the axial points
        makeAxialPoints()
        //make the center point with the appropriate number of replications
        myDesign.addDesignPoint(centerPoint(), numCenterReps)
    }

    private fun makeAxialPoints() {
        for (i in 0 until numFactors) {
            val posArray = DoubleArray(numFactors) { 0.0 }
            val neqArray = DoubleArray(numFactors) { 0.0 }
            posArray[i] = axialSpacing
            neqArray[i] = -axialSpacing
            val posPt = toOriginalValues(posArray)
            val neqPt = toOriginalValues(neqArray)
            myDesign.addDesignPoint(posPt, numAxialReps)
            myDesign.addDesignPoint(neqPt, numAxialReps)
        }
    }

    private fun makeFactorialPoints(ptItr: DesignPointIteratorIfc): Int {
        var pCount = 0
        while (ptItr.hasNext()) {
            val dp = ptItr.next()
            myDesign.addDesignPoint(dp.settings, numFactorialReps)
            pCount++
        }
        return pCount
    }

    override fun designIterator(replications: Int): DesignPointIteratorIfc {
        return myDesign.designIterator(replications)
    }

    override fun iterator(): Iterator<DesignPoint> {
        return myDesign.iterator()
    }
}