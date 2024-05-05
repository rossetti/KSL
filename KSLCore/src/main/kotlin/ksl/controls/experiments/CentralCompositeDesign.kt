package ksl.controls.experiments

import ksl.utilities.Identity


class CentralCompositeDesign(
    private val factorialDesign: FactorialDesign,
    val axialSpacing: Double,
    val factorialReps: Int = 1,
    val axialReps: Int = 1,
    val centerReps: Int =  1,
    name: String? = null
) : Identity(name), ExperimentalDesignIfc {

    val numFactorialPoints: Int
        get() = factorialDesign.numDesignPoints
    val numAxialPoints: Int
        get() = 2*factorialDesign.numDesignPoints
    val numFactors: Int
        get() = factorialDesign.factors.size

    //   private val myDesign : ExperimentalDesign

    init {
        require(factorialReps > 0) { "Number of factorial replications must be > 0" }
        require(axialReps > 0) { "Number of axial replications must be > 0" }
        require(centerReps > 0) { "Number of center replications must be > 0" }
        require(axialSpacing > 0.0) { "The axial spacing must be > 0" }
    }
    
    override val factors: Map<String, Factor>
        get() = factorialDesign.factors
    override val factorNames: List<String>
        get() = factorialDesign.factorNames

    override fun designIterator(replications: Int): DesignPointIteratorIfc {
        TODO("Not yet implemented")
    }

    override fun iterator(): Iterator<DesignPoint> {
        TODO("Not yet implemented")
    }
}