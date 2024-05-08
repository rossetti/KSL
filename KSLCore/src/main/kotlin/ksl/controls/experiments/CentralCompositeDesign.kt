package ksl.controls.experiments

import ksl.utilities.Identity
import kotlin.math.pow
import kotlin.math.sqrt

/**
 *  A [central composite design](https://www.itl.nist.gov/div898/handbook/pri/section3/pri3361.htm)
 *  represent a two-level factorial design that has
 *  been augmented with a center point and axial point to enable the modeling
 *  of quadratic response surface models.
 *
 *  This base class specifies a circumscribed central composite design. Thus, the extreme
 *  values for the high and low settings for the factors will be exceeded. Care must
 *  be taken to ensure that the values for the axial points are valid values for the
 *  design factors in the original parameter space.
 *
 *  @param factors the factors for the design
 *  @param numFactorialReps the number of replications at each point in the factorial design.
 *  The default is 1.
 *  @param numCenterReps the number of replications for the center point in the factorial design.
 *  The default is 1.
 *  @param numAxialReps the number of replications for the axial points in the factorial design.
 *   The default is 1.
 *  @param axialSpacing the axial spacing in coded units for the design. The axial spacing must
 *  be greater than 0.0. The user is responsible for selecting an appropriate axial spacing value
 *  that determines the quality of the design.
 */
open class CentralCompositeDesign(
    factors: Set<TwoLevelFactor>, //TODO could this be an iterator to two level factorial design
    val numFactorialReps: Int = 1,
    val numAxialReps: Int = 1,
    val numCenterReps: Int = 1,
    val axialSpacing: Double,
    name: String? = null
) : Identity(name), ExperimentalDesignIfc {

    protected val myDesign: ExperimentalDesign = ExperimentalDesign(factors)

    protected val myTwoLevelFactorialDesign = TwoLevelFactorialDesign(factors)

    final override val factors: Map<String, Factor>
        get() = myDesign.factors

    final override val factorNames: List<String>
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

    final override fun centerPoint(): DoubleArray {
        return super.centerPoint()
    }

    protected fun makeAxialPoints() {
        for (i in 0 until numFactors) {
            val posArray = DoubleArray(numFactors) { 0.0 }
            val neqArray = DoubleArray(numFactors) { 0.0 }
            posArray[i] = axialSpacing
            neqArray[i] = -axialSpacing
            val posPt = toOriginalValues(posArray)
            val neqPt = toOriginalValues(neqArray)
            myDesign.addDesignPoint(posPt, numAxialReps, enforceRange = false)
            myDesign.addDesignPoint(neqPt, numAxialReps, enforceRange = false)
        }
    }

    protected fun makeFactorialPoints(ptItr: DesignPointIteratorIfc): Int {
        var pCount = 0
        while (ptItr.hasNext()) {
            val dp = ptItr.next()
            myDesign.addDesignPoint(dp.settings, numFactorialReps)
            pCount++
        }
        return pCount
    }

    override fun designIterator(replications: Int?): DesignPointIteratorIfc {
        return myDesign.designIterator(replications)
    }

    override fun iterator(): Iterator<DesignPoint> {
        return myDesign.iterator()
    }

    companion object {

        /**
         *  Computes the axial spacing for a rotatable design based on page 470 of
         *  Box et al. (2005) "Response Surfaces Mixtures and Ridge Analysis" Wiley.
         *
         *  @param numFactors the factors for the design
         *  @param numFactorialReps the number of replications at each point in the factorial design.
         *  The default is 1.
         *  @param numAxialReps the number of replications for the axial points in the factorial design.
         *   The default is 1.
         *  @return the axial spacing in coded units for the design. The axial spacing must
         *  be greater than 0.0. The user is responsible for selecting an appropriate axial spacing value
         *  that determines the quality of the design.
         */
        fun rotatableAxialSpacing(
            numFactors: Int,
            fraction: Int = 0,
            numFactorialReps: Int = 1,
            numAxialReps: Int = 1
        ): Double {
            require(numFactors >= 2) { "There must be 2 or more factors" }
            require(fraction >= 0) { "The fraction must be >= 0" }
            require(numFactorialReps >= 1) { "The number of factorial replications must be >= 1" }
            require(numAxialReps >= 1) { "The number of axial point replications must be >= 1" }
            val dp = 2.0.pow(numFactors - fraction)
            val v: Double = (dp * numFactorialReps) / numAxialReps
            return sqrt(sqrt(v))
        }
    }
}