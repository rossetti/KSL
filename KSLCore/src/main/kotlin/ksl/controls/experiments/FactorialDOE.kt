package ksl.controls.experiments

import ksl.simulation.Model

/**
 *  Facilitates the simulation of a model via a factorial design.
 *  @param model the model to simulate
 *  @param factorSettings a mapping between each factor and a string
 *  representing the name of the control or parameter to associate with the factor
 *  @param numRepsPerDesignPoint the number of replications for each design point. Defaults to 10.
 */
class FactorialDOE(
    private val model: Model,
    private val factorSettings: Map<Factor, String>,
    numRepsPerDesignPoint: Int = 10
) {
    private val myControls = model.controls()
    private val myRVParameterSetter = model.rvParameterSetter

    private val mySimulationRunner = SimulationRunner(model)

    /**
     *  Use to hold executed simulation runs, 1 for each design point executed
     */
    private val mySimulationRuns = mutableListOf<SimulationRun>()

    init {
        require(factorSettings.isNotEmpty()) { "factorControls must not be empty" }
        require(numRepsPerDesignPoint >= 1)  {"The number of replications per design point must be >= 1." }
        //TODO need to connect factors with controls and parameters
    }

    val factorialDesign = FactorialDesign(factorSettings.keys, "${model}_Factorial_DOE")

    val numDesignPoints : Int
        get() = factorialDesign.numDesignPoints

    private val myReplicates = IntArray(numDesignPoints) { numRepsPerDesignPoint }

    /**
     *  Causes any previous simulation runs associated with the execution of design points
     *  to be cleared.
     */
    fun clearSimulationRuns(){
        mySimulationRuns.clear()
    }

    /**
     *  Specifies that each design point have [numReps] replications.
     *  If [numReps] is less than 1, then it is silently changed to 1.
     */
    fun replicationsPerDesignPoint(numReps: Int) {
        myReplicates.fill(if (numReps < 1) 1 else numReps)
    }

    /**
     *  Allows the number of replications for each of the design points in the design
     *  to be different by allowing an unbalanced design. If any element in the
     *  array is less than 1, it is set to 1. There must be at least 1 replicate
     *  for each design point.
     */
    var designPointReplications: IntArray
        get() = myReplicates.copyOf()
        set(array) {
            require(array.size == numDesignPoints) { "The size (${array.size}) of the array must be the number of design points: ${myReplicates.size}" }
            for ((i, r) in array.withIndex()) {
                myReplicates[i] = if (array[i] < 1) 1 else array[i]
            }
        }

    /**
     *  Simulates the specified design point from the factorial design for the
     *  specified number of replications
     *  @param designPoint the design point to simulate
     *  @param numReps the number of replications for the design point
     */
    fun simulateDesignPoint(designPoint: Int, numReps: Int) {
        require(designPoint in 1..numDesignPoints) {"The design point ($designPoint) was not in the design." }
        require(numReps >= 1) {"The number of replications per design point must be >= 1." }
        val dp = factorialDesign.designPointToMap(designPoint)
        // dp holds (factor name, factor level) for the factors at this design point
        // use to hold the inputs for the simulation
        val inputs = mutableMapOf<String, Double>()
        //TODO need to apply to controls or parameters, setup and run the simulation
        for((f, v) in dp){
            // get the factor from the design
            val factor = factorialDesign.factors[f]!!
            // get the control parameter from the factor setting
            val cp = factorSettings[factor]!!
            // get correct control name or parameter name for assigning to input map
            inputs[cp] = v
        }
        //TODO need to setup experiment and its name
        //TODO  use SimulationRunner to run the simulation
        //TODO add SimulationRun to simulation run list
        TODO("not implemented yet")

    }

    /**
     *   Simulates the design points specified by [points] for the corresponding
     *   number of [replications].
     *   The number of design points must be 1 and the number of total design points.
     *   The replications and points arrays must have the same number of elements.
     *   The elements of the points array specify the design point to simulate.
     *   If any element in the points array is not a valid design point it is skipped (not simulated).
     *   The elements of the replications array are expected to be a valid number of replications
     *   for the corresponding design point. If any element of the replications array is 0 or less
     *   then the design point is skipped (not simulated).  Thus, the user can (in theory)
     *   simulated the design points in any order via the points array and skip design points
     *   as needed.
     *   @param clearRuns indicates that any previous simulation runs for the design points will be cleared
     *   prior to executing these design points
     */
    fun simulateDesignPoints(points: IntArray, replications: IntArray, clearRuns: Boolean = true){
        require(points.isNotEmpty()){"The design points array must not be empty!"}
        require(replications.isNotEmpty()){"The replications array must not be empty!"}
        require(points.size <= numDesignPoints) {"The number of design points must be <= $numDesignPoints"}
        require(replications.size <= numDesignPoints) {"The size of the replications array must be <= $numDesignPoints"}
        require(points.size == replications.size) {"The size the arrays must be the same."}
        if (clearRuns) {
            mySimulationRuns.clear()
        }
        for((i,point) in points.withIndex()) {
            if (point in 1..numDesignPoints) {
                // valid design point
                if (replications[i] >= 1){
                    // has replications
                    simulateDesignPoint(point, replications[i])
                }
            }
        }
    }

    /**
     *   Causes all design points to be simulated in order 1, 2, 3,...
     *   using the number of replications provided in designPointReplications
     *   @param numReps the number of replications per design point. If 0 (the default)
     *   then the current specification of replications per design point is used.
     *   @param clearRuns indicates that any previous simulation runs for the design points will be cleared
     *   prior to executing these design points
     */
    fun simulateDesign(numReps: Int = 0, clearRuns: Boolean = true) {
        if (numReps >= 1) {
            replicationsPerDesignPoint(numReps)
        }
        val points = (1..numDesignPoints).toList().toIntArray()
        simulateDesignPoints(points, designPointReplications, clearRuns)
    }
}