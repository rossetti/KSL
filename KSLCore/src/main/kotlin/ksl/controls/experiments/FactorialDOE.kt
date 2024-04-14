package ksl.controls.experiments

import ksl.simulation.Model

/**
 *  Facilitates the simulation of a model via a factorial design.
 *  @param model the model to simulate
 *  @param factorSettings a mapping between each factor and a string
 *  representing the name of the control or parameter to associate with the factor
 */
class FactorialDOE(
    private val model: Model,
    private val factorSettings: Map<Factor, String>,
    numRepsPerDesignPoint: Int = 1
) {
    private val myControls = model.controls()
    private val myRVParameterSetter = model.rvParameterSetter

    init {
        require(factorSettings.isNotEmpty()) { "factorControls must not be empty" }
        require(numRepsPerDesignPoint >= 1)  {"The number of replications per design point must be >= 1." }
        //TODO need to connect factors with controls and parameters
    }

    val factorialDesign = FactorialDesign(factorSettings.keys, "${model}_Factorial_DOE")

    private val myReplicates = IntArray(factorSettings.keys.cartesianProductSize()) { numRepsPerDesignPoint }

    /**
     *  Allows the number of replications for each of the design points in the design
     *  to be different by allowing an unbalanced design. If any element in the
     *  array is less than 1, it is set to 1. There must be at least 1 replicate
     *  for each design point.
     */
    var designPointReplications: IntArray
        get() = myReplicates.copyOf()
        set(value) {
            require(value.size == myReplicates.size) { "The size (${value.size}) of the array must be the number of design points: ${myReplicates.size}" }
            for ((i, r) in value.withIndex()) {
                myReplicates[i] = if (value[i] < 1) 1 else value[i]
            }
        }

    /**
     *  Simulates the specified design point from the factorial design for the
     *  specified number of replications
     *  @param designPoint the design point to simulate
     *  @param numReps the number of replications for the design point
     */
    fun simulate(designPoint: Int, numReps: Int) {
        require(designPoint in 1..myReplicates.size) {"The design point ($designPoint) was not in the design." }
        require(numReps >= 1) {"The number of replications per design point must be >= 1." }
        
        TODO("not implemented yet")

    }

    fun simulateDesign() {
        for ((i, nr) in myReplicates.withIndex()) {
            simulate(i + 1, nr)
        }
    }
}