package ksl.controls.experiments

import ksl.simulation.Model
import ksl.utilities.random.rvariable.parameters.RVParameterSetter

/**
 *  Facilitates the simulation of a model via a factorial design.
 *
 *  The map representing the factor and its associated string requires some further
 *  discussion.  The naming convention for controls and random variable parameters is important to note.
 *
 *  For controls, by default, the key to associate with the value is the model element's name
 *  concatenated with the property that was annotated with the control.  For example, if
 *  the resource had name Worker and annotated property initialCapacity, then the key
 *  will be "Worker.initialCapacity". Note the use of the "." character to separate
 *  the model element name and the property name.  Since, the KSL model element naming
 *  convention require unique names for each model element, the key will be unique for the control.
 *  However, the model element name may be a very long string depending on your approach
 *  to naming the model elements. The name associated with each control can be inspected by
 *  asking the model for its controls via model.controls() and then using the methods on the Controls
 *  class for the names. The controlsMapAsJsonString() or asMap() functions are especially helpful
 *  for this purpose.
 *
 *  For the parameters associated with random variables, the naming convention is different.
 *  Again, the model element name is used as part of the identifier, then the value of
 *  rvParamConCatString from the companion object is concatenated between the name of the
 *  model element and the name of its parameter.  For example, suppose there is a
 *  random variable that has been named ServiceTimeRV that is exponentially distributed.
 *  Also assume that rvParamConCatString is "_PARAM_", which is its default value. Then,
 *  to access the mean of the service time random variable, we use "ServiceTimeRV_PARAM_mean".
 *  Thus, it is important to note the name of the random variable within the model and the
 *  KSL's default names for the random variable parameters.  When random variables are
 *  not explicitly named by the modeler, the KSL will automatically provide a default
 *  unique name. Thus, if you plan to control a specific random variable's parameters, you
 *  should strongly consider providing an explicit name. To get the names (and current values)
 *  of the random variable parameters, you can print out the toString() method of the
 *  RVParameterSetter class after obtaining it from the model via the model's rvParameterSetter
 *  property.
 *
 *  Suppose factor A was associated with the worker's initial capacity and factor B was
 *  associated with the mean of the service time distribution, then the factor settings map
 *  would be mapOf(factorA to "Worker.initialCapacity", factorB to "ServiceTimeRV_PARAM_mean")
 *  where factorA and factorB are references to the associated Factor instances.
 *
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
//    private val myControls = model.controls()
//    private val myRVParameterSetter = model.rvParameterSetter

    private val mySimulationRunner = SimulationRunner(model)

    /**
     *  capture the original experiment run parameters so that they can
     *  be restored to original after executing a design point
     */
    private val myOriginalExpRunParams: ExperimentRunParameters = model.extractRunParameters()

    /**
     *  Use to hold executed simulation runs, 1 for each design point executed
     */
    private val mySimulationRuns = mutableListOf<SimulationRun>()

    var baseExperimentName = model.experimentName
        set(value) {
            require(value.isNotBlank()) { "name must not be blank" }
            field = value
        }

    /**
     *  Returns the list of executed runs, one run for each design point simulated
     */
    val simulationRuns: List<SimulationRun>
        get() = mySimulationRuns

    init {
        require(factorSettings.isNotEmpty()) { "factorControls must not be empty" }
        require(numRepsPerDesignPoint >= 1)  {"The number of replications per design point must be >= 1." }
        // check if supplied control or parameter keys make sense for this model
        require(model.validateInputKeys(factorSettings.values.toSet())) {
            "The factor settings contained invalid input names"
        }
//        for ((f, n) in factorSettings){
//            val rvKeys = RVParameterSetter.splitFlattenedRVKey(n)
//            require(myControls.hasControl(n) || myRVParameterSetter.containsParameter(rvKeys[0], rvKeys[1]))
//              {"The name $n related to factor (${f.name}) is not a control or a rv parameter."}
//        }
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
     *  specified number of replications.  The specified number of replications
     *  will override whatever is specified in the [experimentRunParameters].
     *  Also, the name of the experiment associated with the design point is automatically
     *  changed to include the design point being simulated.
     *
     *  @param designPoint the design point to simulate
     *  @param numReps the number of replications for the design point
     *  @param baseExperimentName the base name for each experiment representing the design point
     *  @param clearRuns Any prior simulation runs are cleared prior to executing. The default is false
     *  @param addRuns If true the executed run will be added to the executed simulation runs. The
     *  default is true.
     */
    fun simulateDesignPoint(
        designPoint: Int,
        numReps: Int,
        baseExperimentName: String = "${myOriginalExpRunParams.experimentName}_DP_$designPoint",
        clearRuns: Boolean = false,
        addRuns: Boolean = true
    ) {
        require(designPoint in 1..numDesignPoints) {"The design point ($designPoint) was not in the design." }
        require(numReps >= 1) {"The number of replications per design point must be >= 1." }
        if (clearRuns) {
            mySimulationRuns.clear()
        }
        val dp = factorialDesign.designPointToMap(designPoint)
        // dp holds (factor name, factor level) for the factors at this design point
        // use to hold the inputs for the simulation
        val inputs = mutableMapOf<String, Double>()
        // fill the inputs map based on the factor level settings
        // the simulation runner takes care of assigning the inputs to the model
        for((f, v) in dp){
            // get the factor from the design
            val factor = factorialDesign.factors[f]!!
            // get the control parameter from the factor setting
            val cp = factorSettings[factor]!!
            // get correct control name or parameter name for assigning to input map
            inputs[cp] = v
        }
        // setup experiment and its name
        model.numberOfReplications = numReps
        model.experimentName = baseExperimentName
        // use SimulationRunner to run the simulation
        val sr = mySimulationRunner.simulate(inputs, model.extractRunParameters())
        // add SimulationRun to simulation run list
        if (addRuns){
            mySimulationRuns.add(sr)
        }
        // reset the model run parameters back to their original values
        model.changeRunParameters(myOriginalExpRunParams)
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
    fun simulateDesignPoints(
        points: IntArray,
        replications: IntArray,
        clearRuns: Boolean = true,
    ){
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
    fun simulateDesign(
        numReps: Int = 0,
        clearRuns: Boolean = true,
    ) {
        if (numReps >= 1) {
            replicationsPerDesignPoint(numReps)
        }
        val points = (1..numDesignPoints).toList().toIntArray()
        simulateDesignPoints(points, designPointReplications, clearRuns)
    }
}