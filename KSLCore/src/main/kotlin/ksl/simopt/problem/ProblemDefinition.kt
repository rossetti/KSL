package ksl.simopt.problem

import com.sun.beans.introspect.PropertyInfo
import ksl.simopt.evaluator.EstimatedResponse
import ksl.simopt.evaluator.ResponseMap
import ksl.simopt.evaluator.Solution
import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.Interval
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.randomlySelect


/**
 *  This class describes an optimization problem for use within simulation optimization algorithms.
 *  The general optimization problem is presented as minimizing the expected value of some function H(x), where
 *  x is some input parameters to the simulation and H(.) is the simulation model response for the objective
 *  function. The input parameters are assumed to be real-valued specified by a name between a lower and upper bound
 *  and a granularity. The granularity specifies the acceptable precision of the input. The problem can
 *  have a set of linear constraints. The linear constraints are a deterministic function of the inputs.
 *  The problem can also have a set of functional constraints.  A functional constraint is a deterministic
 *  function of the inputs that is bounded by constraints. This permits non-linear deterministic
 *  functional forms for the problem. In addition, a set of probabilistic constraints of the form E[G(x)] < c
 *  can be specified, where G(x) is some response from the simulation.
 *
 *  To use this class, the user first defines the objective function response name, the names of the input variables,
 *  and the names of the responses to appear in the problem. Then the reference to the class can be used
 *  to specify inputs and constraints.
 *
 *  @param problemName the name of the problem for labeling and identification purposes. If no name is supplied,
 *  then a unique name is constructed.
 *  @param modelIdentifier the identifier of the model that this problem definition is associated with. The identifier should
 *  correspond to an actual model name registered with a simulation provider.  The input names and response
 *  names should be valid for the identified model.
 *  @param objFnResponseName the name of the response within the simulation model. This name is used to extract the
 *  observed simulation values from the simulation
 *  @param inputNames the names of the inputs for the simulation model. These names are used to set values for
 *  the simulation when executing experiments. Any constraints specified on the input variables must use these names.
 *  @param responseNames the names of any responses that will appear in response constraints. The default is empty.
 *  @param indifferenceZoneParameter a parameter that represents the smallest actual difference that is important
 *  to detect for the objective function response. This parameter can be used by solvers to determine if differences
 *  between solutions are considered practically insignificant. The default is zero.
 */
class ProblemDefinition(
    problemName: String? = null,
    val modelIdentifier: String,
    val objFnResponseName: String,
    inputNames: List<String>,
    responseNames: List<String> = emptyList(),
    indifferenceZoneParameter: Double = 0.0,
) : IdentityIfc by Identity(problemName) {

    /**
     *  The names of the input parameters over which the problem is
     *  being optimized, i.e. the decision variable names.
     *  These need to correspond to simulation model inputs. This
     *  list cannot be empty.
     */
    val inputNames: List<String>

    /**
     *  The names of the responses over which the problem is
     *  being optimized. These are the simulation response names.
     *  This list may be empty.
     */
    val responseNames: List<String>

    init {
        require(indifferenceZoneParameter >= 0.0) { "The indifference zone parameter must be >= 0.0" }
        require(objFnResponseName.isNotBlank()) { "The objective function response name cannot be blank" }
        require(inputNames.isNotEmpty()) { "The set of input names cannot be empty" }
        for (name: String in inputNames) {
            require(name.isNotBlank()) { "An input name was blank" }
        }
        this.inputNames = inputNames.distinct()
        for (name: String in responseNames) {
            require(name.isNotBlank()) { "A response name was blank" }
        }
        this.responseNames = responseNames.distinct()
        require(!responseNames.contains(objFnResponseName)) { "The objective function response name cannot be within the set of response constraint names." }
    }

    /**
     *  A parameter that represents the smallest actual difference that is important
     *  to detect for the objective function response. This parameter can be used by solvers to determine if differences
     *  between solutions are considered practically insignificant. The default is zero.
     *  Must be greater than or equal to zero.
     */
    var indifferenceZoneParameter: Double = indifferenceZoneParameter
        set(value) {
            require(value >= 0.0) { "The indifference zone parameter must be >= 0.0" }
            field = value
        }

    /**
     *  Returns a list of the names of all the responses referenced in the problem
     *  in the order in which they are specified in the problem creation.
     *  The first name will be the name of the response associated with the objective
     *  function, then each response name from the provided set of response names.
     */
    val allResponseNames: List<String>
        get() {
            val list = mutableListOf(objFnResponseName)
            list.addAll(responseNames)
            return list
        }

    /**
     *  Can be supplied to provide a method for specifying a feasible starting point.
     *  The default is to randomly generate a starting point
     */
    var startingPointGenerator: StartingPointIfc? = null

    private val myInputDefinitions = mutableMapOf<String, InputDefinition>()

    /**
     *  The input definitions as a list. The key is the name of the
     *  input parameter and the value is the input definition.
     */
    val inputDefinitions: Map<String, InputDefinition>
        get() = myInputDefinitions

    /**
     *  The input definitions for the problem as a list
     */
    val inputDefinitionList: List<InputDefinition>
        get() = myInputDefinitions.values.toList()

    private val myLinearConstraints = mutableListOf<LinearConstraint>()

    /**
     *  The linear constraints for the problem as a list
     */
    val linearConstraints: List<LinearConstraint>
        get() = myLinearConstraints.toList()

    private val myResponseConstraints = mutableListOf<ResponseConstraint>()

    /**
     *  The response constraints as a list
     */
    val responseConstraints: List<ResponseConstraint>
        get() = myResponseConstraints.toList()

    private val myFunctionalConstraints = mutableListOf<FunctionalConstraint>()

    /**
     *  The functional constraints for the problem as a list
     */
    val functionalConstraints: List<FunctionalConstraint>
        get() = myFunctionalConstraints.toList()

    /**
     * The lower bounds for each input variable
     */
    val inputLowerBounds: DoubleArray
        get() = myInputDefinitions.values.map { it.lowerBound }.toDoubleArray()

    /**
     *  The upper bounds for each input variable
     */
    val inputUpperBounds: DoubleArray
        get() = myInputDefinitions.values.map { it.upperBound }.toDoubleArray()

    /**
     *  The intervals for each input variable
     */
    val inputIntervals: List<Interval>
        get() = myInputDefinitions.values.map { it.interval }.toList()

    /**
     *  The mid-point of each input variable's range
     */
    val inputMidPoints: DoubleArray
        get() = myInputDefinitions.values.map { it.interval.midPoint }.toDoubleArray()

    /**
     *  The mid-point of each input variable's range as an input map
     */
    val midPoints: MutableMap<String, Double>
        get() = myInputDefinitions.values.associate { it.midPoint }.toMutableMap()

    /**
     *  The range (width) of each input variable's interval
     */
    val inputRanges: DoubleArray
        get() = myInputDefinitions.values.map { it.interval.width }.toDoubleArray()

    /**
     *  The granularity associated with each input variable as an array
     */
    val inputGranularities: DoubleArray
        get() = myInputDefinitions.values.map { it.granularity }.toDoubleArray()

    /**
     *  The number of input variables
     */
    val inputSize: Int
        get() = myInputDefinitions.values.size

    /**
     * The maximum number of iterations when sampling for an input feasible point
     */
    var maxFeasibleSamplingIterations = defaultMaximumFeasibleSamplingIterations
        set(value) {
            require(value > 0) { "The maximum number of samples is $value, must be > 0" }
            field = value
        }

    /** Returns true if the name is a valid response name or the name
     *  associated with the objective function.
     *
     *  @param name the name to check
     */
    fun isValidResponse(name: String): Boolean {
        return ((name == objFnResponseName) || responseNames.contains(name))
    }

    /** Returns a new empty response map to hold the responses associated with the problem
     */
    fun emptyResponseMap(): ResponseMap {
        return ResponseMap(allResponseNames.toSet())
    }

    /**
     *  Randomly selects from the list of valid input names with equal likelihood.
     *
     *  @param rnStream the random number stream to use for randomness
     *  @return the randomly selected name
     */
    fun randomInputName(rnStream: RNStreamIfc = KSLRandom.defaultRNStream()): String {
        return inputNames.randomlySelect(rnStream)
    }

    /**
     *  Defines an input variable for the problem. The order of specification of the input variables
     *  defines the order when interpreting an array of inputs.
     *
     *  @param name the name of the input variable. Must be in the set of names supplied when the problem was created.
     *  @param lowerBound the lower bound on the range of the input variable. Must be less than the upper bound.
     *  Must be finite.
     *  @param upperBound the upper bound on the range of the input variable. Must be greater than the lower bound.
     *  Must be finite.
     *  @param granularity the granularity associated with the variable see [ksl.utilities.math.KSLMath.mround]. The
     *  default is 0.0
     */
    fun inputVariable(
        name: String,
        lowerBound: Double,
        upperBound: Double,
        granularity: Double = 0.0
    ): InputDefinition {
        require(name in inputNames) { "The name $name does not exist in the named inputs" }
        val inputData = InputDefinition(name, lowerBound, upperBound, granularity)
        myInputDefinitions[name] = inputData
        return inputData
    }

    /**
     *  Defines an input variable for the problem. The order of specification of the input variables
     *  defines the order when interpreting an array of inputs.
     *
     *  @param name the name of the input variable. Must be in the set of names supplied when the problem was created.
     *  @param interval the interval containing the variable
     *  @param granularity the granularity associated with the variable see [ksl.utilities.math.KSLMath.mround]. The
     *  default is 0.0
     */
    fun inputVariable(name: String, interval: Interval, granularity: Double = 0.0): InputDefinition {
        return inputVariable(name, interval.lowerLimit, interval.upperLimit, granularity)
    }

    /**
     *  Creates an [LinearConstraint] based on the supplied linear equation as specified by the map.
     *  The names in the map must be valid input names.  If an input name does not exist in the map,
     *  then the coefficient for that variable is assumed to be 0.0.
     *  @param equation the pair (name, value) represents the input name and the coefficient value in the linear
     *  constraint
     *  @param rhsValue the right-hand side of the constraint
     *  @param inequalityType the inequality type (less_than or greater_than)
     */
    fun linearConstraint(
        equation: Map<String, Double>,
        rhsValue: Double = 0.0,
        inequalityType: InequalityType = InequalityType.LESS_THAN
    ): LinearConstraint {
        for ((name, _) in equation) {
            require(name in inputNames) { "The name $name does not exist in the named inputs" }
        }
        val eqMap = mutableMapOf<String, Double>()
        for (name: String in inputNames) {
            eqMap[name] = equation[name] ?: 0.0
        }
        val ic = LinearConstraint(eqMap, rhsValue, inequalityType)
        myLinearConstraints.add(ic)
        return ic
    }

    /**
     *  Creates an [ResponseConstraint] based on the supplied response name and right-hand side value.
     *  @param name the name of the response. Must be a pre-defined response name that is associated with
     *  the problem definition
     *  @param rhsValue the right-hand side of the constraint
     *  @param inequalityType the inequality type (less_than or greater_than). The default is less than
     *  @param target the constraint's target. A parameter often used by solver methods that behaves
     *  as a cut-off point between desirable and unacceptable systems
     *  @param tolerance the constraint's tolerance. A parameter often used by solver methods that
     *  specifies how much we are willing to be off from the target. Similar to an indifference parameter.
     *  @return the constructed response constraint
     */
    fun responseConstraint(
        name: String,
        rhsValue: Double,
        inequalityType: InequalityType = InequalityType.LESS_THAN,
        target: Double = 0.0,
        tolerance: Double = 0.0
    ): ResponseConstraint {
        require(name in responseNames) { "The name $name does not exist in the response names" }
        val rc = ResponseConstraint(name, rhsValue, inequalityType, target, tolerance)
        myResponseConstraints.add(rc)
        return rc
    }

    /**
     *  Creates an [FunctionalConstraint] based on the supplied function.

     *  @param lhsFunc the function representing the left-hand side of the constraint
     *  @param rhsValue the right-hand side of the constraint
     *  @param inequalityType the inequality type (less_than or greater_than). The default is less than
     *  @return the constructed functional constraint
     */
    fun functionalConstraint(
        lhsFunc: ConstraintFunctionIfc,
        rhsValue: Double = 0.0,
        inequalityType: InequalityType = InequalityType.LESS_THAN
    ): FunctionalConstraint {
        val fc = FunctionalConstraint(inputNames, lhsFunc, rhsValue, inequalityType)
        myFunctionalConstraints.add(fc)
        return fc
    }

    /**
     *  Returns the coefficients of the constraints as a matrix. Assume we have the constraint
     *  A*x < b or A*x > b, then this function returns the A matrix. The coefficients have
     *  not been adjusted for the direction of the constraints.
     */
    fun linearConstraintMatrix(): Array<DoubleArray> {
        val array = mutableListOf<DoubleArray>()
        for (constraint in myLinearConstraints) {
            array.add(constraint.coefficients)
        }
        return array.toTypedArray()
    }

    /**
     *  Returns the coefficients of the constraints as a matrix. Assume we have the constraint
     *  A*x < b, then this function returns the A matrix. The coefficients have
     *  been adjusted to ensure a less-than orientation for the constraints.
     */
    fun linearConstraintAdjustedMatrix(): Array<DoubleArray> {
        val array = mutableListOf<DoubleArray>()
        for (constraint in myLinearConstraints) {
            array.add(constraint.adjustedCoefficients)
        }
        return array.toTypedArray()
    }

    /**
     *  Returns the adjusted left-hand side values for each constraint. Make the adjustment
     *  such that the constraint is considered less-than orientation.
     *
     *  @param inputs the input values as a map containing the (name, value) of the inputs
     *  @return the left-hand side values for each constraint.
     */
    fun linearConstraintsLHSValues(inputs: MutableMap<String, Double>): DoubleArray {
        require(inputs.size == myInputDefinitions.size) { "The size of the input array is ${inputs.size}, but the number of inputs is ${myInputDefinitions.size}" }
        return DoubleArray(myLinearConstraints.size) { myLinearConstraints[it].computeLHS(inputs) }
    }

    /**
     *  Assume we have the constraint, A*x < b or A*x > b, then this function returns the b vector. The values have not
     *  been adjusted for the direction of the constraint.
     */
    fun linearConstraintsRHS(): DoubleArray {
        return myLinearConstraints.map { it.rhsValue }.toDoubleArray()
    }

    /**
     *  Returns the coefficients of the constraints as a matrix. Assume we have the constraint
     *  A*x < b or A*x > b, then this function returns the b vector. The values have
     *  been adjusted for the direction of the constraint.
     */
    fun linearConstraintsAdjustedRHS(): DoubleArray {
        return myLinearConstraints.map { it.ltRHSValue }.toDoubleArray()
    }

    /**
     *  Returns the unadjusted right-hand side values for the response constraints.
     */
    fun responseConstraintsRHS(): DoubleArray {
        return myResponseConstraints.map { it.rhsValue }.toDoubleArray()
    }

    /**
     *  Returns the adjusted right-hand side values for the response constraints.
     */
    fun responseConstraintsAdjustedRHS(): DoubleArray {
        return myResponseConstraints.map { it.ltRHSValue }.toDoubleArray()
    }

    /**
     *  The violations associated with the response constraints for the
     *  provided values within the response map.
     *  @param responseMap the map of responses filled with data for this problem.
     *  Must have been created by this problem.
     *  @return a list of the violations, one for each response constraint in the problem
     */
    fun responseConstraintViolations(responseMap: ResponseMap): Map<String, Double> {
        val names = allResponseNames
        for(name in responseMap.keys) {
            require(name in names) { "The name $name does not exist in the response names" }
        }
        val averages = responseMap.mapValues { it.value.average }
        return responseConstraintViolations(averages)
    }

    /**
     *  The violations associated with the response constraints for the
     *  provided values within the response map.
     *  @param averages the map of responses filled with data for this problem.
     *  Must have been created by this problem.
     *  @return a map of the violations, one for each response constraint in the problem. The
     *  returned map has the response name associated with the contraint and the value of the violation
     */
    fun responseConstraintViolations(averages: Map<String, Double>): Map<String, Double> {
        val map = mutableMapOf<String, Double>()
        for (rc in myResponseConstraints) {
            require(averages.containsKey(rc.responseName)) { "The name ${rc.responseName} was not found in the supplied averages" }
            val average = averages[rc.responseName]!!
            map[rc.responseName] = rc.violation(average)
        }
        return map
    }

    /** The array x is mutated to hold values that have appropriate granularity based on the
     *  input definitions.
     *
     *  @param x the values of the inputs as an array. Assumes that the values are ordered in the
     *  same order as the names are defined for the problem
     *  @return the returned array is the same array as the input array but mutated. It is return for convenience.
     */
    fun roundToGranularity(x: DoubleArray): DoubleArray {
        require(x.size == myInputDefinitions.size) { "The size of the input array is ${x.size}, but the number of inputs is ${myInputDefinitions.size}" }
        for ((i, inputDefinition) in myInputDefinitions.values.withIndex()) {
            x[i] = inputDefinition.roundToGranularity(x[i])
        }
        return x
    }

    /** The map values are mutated to hold values that have appropriate granularity and range based on the
     *  input definitions.
     *
     *  @param map the values of the inputs as map (name, value) pairs. The names in the map must be defined
     *  input names.
     *  @return the returned map is the same map as the input map but mutated. It is return for convenience.
     */
    fun roundToGranularity(map: MutableMap<String, Double>): MutableMap<String, Double> {
        require(map.size == myInputDefinitions.size) { "The size of the input map is ${map.size}, but the number of inputs is ${myInputDefinitions.size}" }
        for ((name, inputDefinition) in myInputDefinitions) {
            require(name in map) { "The input name $name does not exist in the map" }
            val value = map[name]!!
            map[name] = if (value < inputDefinition.lowerBound){
                inputDefinition.lowerBound
            } else if (value > inputDefinition.upperBound){
                inputDefinition.upperBound
            } else {
                inputDefinition.roundToGranularity(value)
            }
        }
        return map
    }

    /**
     *  Ensures that the supplied map is translated to an appropriate map
     *  containing name, value pairs for this problem. The resulting InputMap
     *  will have valid names and values that are input range feasible. The
     *  values will be rounded to the appropriate granularity for the named
     *  input variable.
     *
     *  @param map the map to wrap. The keys of the supplied map must be valid
     *  names for the problem.
     */
    fun toInputMap(map: MutableMap<String, Double>): InputMap {
        return InputMap(this, roundToGranularity(map))
    }

    /**
     *  Creates an input map that is centered at the mid-points of
     *  all the input variables
     */
    fun midPoints() : InputMap {
        return toInputMap(midPoints)
    }

    /**
     *  Translates the supplied array to named input pairs (name, value).
     *  Assumes that the order of the array is the same as the order of the defined names for the problem.
     *  If the supplied value is outside the range of the name variable it is adjusted to the closest
     *  boundary. In addition, the granularity of the input variable is applied.
     *  @param x the supplied array.
     */
    fun toInputMap(x: DoubleArray): InputMap {
        require(x.size == myInputDefinitions.size) { "The size of the input array is ${x.size}, but the number of inputs is ${myInputDefinitions.size}" }
        val map = mutableMapOf<String, Double>()
        for ((i, inputDefinition) in myInputDefinitions.values.withIndex()) {
            map[inputDefinition.name] = if (x[i] < inputDefinition.lowerBound){
                inputDefinition.lowerBound
            } else if (x[i] > inputDefinition.upperBound){
                inputDefinition.upperBound
            } else {
                inputDefinition.roundToGranularity(x[i])
            }
        }
        return InputMap(this, map)
    }

    /**
     *  Interprets the supplied map as inputs for the problem definition and
     *  returns true if the values are within the ranges defined for the variables.
     *  False will be returned if at least one input variable is not within its defined range.
     *  @param inputs the input values as a map containing the (name, value) of the inputs
     *   @return true if the inputs are input feasible
     */
    fun isInputRangeFeasible(inputs: Map<String, Double>): Boolean {
        if (!validateNames(inputs)) {
            return false
        }
        // check input limits first
        for ((name, value) in inputs) {
            // the name must be in the input definitions by construction
            if (!myInputDefinitions[name]!!.contains(value)) {
                return false
            }
        }
        return true
    }

    /**
     *  Interprets the supplied map as inputs for the problem definition and
     *  returns true if the values are within linear constraints.
     *  False will be returned if at least one linear constraint is infeasible.
     *  @param inputs the input values as a map containing the (name, value) of the inputs
     *   @return true if the inputs are feasible
     */
    fun isLinearConstraintFeasible(inputs: Map<String, Double>): Boolean {
        if (myLinearConstraints.isEmpty()) {
            return true
        }
        if (!validateNames(inputs)) {
            return false
        }
        for (ic in myLinearConstraints) {
            if (!ic.isSatisfied(inputs)) {
                return false
            }
        }
        return true
    }

    /**
     *  Interprets the supplied map as inputs for the problem definition and
     *  returns true if the values are within functional constraints.
     *  False will be returned if at least one functional constraint is infeasible.
     *  @param inputs the input values as a map containing the (name, value) of the inputs
     *   @return true if the inputs are feasible
     */
    fun isFunctionalConstraintFeasible(inputs: Map<String, Double>): Boolean {
        if (myFunctionalConstraints.isEmpty()) {
            return true
        }
        if (!validateNames(inputs)) {
            return false
        }
        for (ic in myFunctionalConstraints) {
            if (!ic.isSatisfied(inputs)) {
                return false
            }
        }
        return true
    }

    /**
     *  Checks if the names in the map are valid for the problem definition
     *  @param inputs the input values as a map containing the (name, value) of the inputs
     *  @return ture if all names are valid
     */
    fun validateNames(inputs: Map<String, Double>): Boolean {
        return validate(inputs, inputNames)
    }

    /**
     *  Checks if the supplied map has valid names and valid values (input range feasible)
     *  @param inputs the map to check
     *  @return false if a name or value is invalid in the map
     */
    fun validate(inputs: Map<String, Double>): Boolean {
        for ((name, value) in inputs) {
            if(!validateInputVariable(name, value)) {return false}
        }
        return true
    }

    /**
     *  Checks if the supplied value is a valid input value for the supplied input name.
     *  @param inputName the name of the input. The name must be in the input map.
     *  @param value the value associated with the input name
     *  @return true if the name and value are valid, false otherwise
     */
    fun validateInputVariable(inputName: String, value: Double) : Boolean {
        if (!myInputDefinitions.containsKey(inputName)) { return false }
        return myInputDefinitions[inputName]!!.contains(value)
    }

    /**
     *  The supplied input is considered input feasible if it is feasible with respect to
     *  the defined input parameter ranges, the linear constraints, and the functional constraints.
     *  @param inputs the input values as a map containing the (name, value) of the inputs
     *  @return true if the inputs are input feasible
     */
    fun isInputFeasible(inputs: Map<String, Double>): Boolean {
        require(inputs.size == myInputDefinitions.size) { "The size of the input map is ${inputs.size}, but the number of inputs is ${myInputDefinitions.size}" }
        return isInputRangeFeasible(inputs) && isLinearConstraintFeasible(inputs)
                && isFunctionalConstraintFeasible(inputs)
    }

    /**
     *  The supplied input is considered input feasible if it is feasible with respect to
     *  the defined input parameter ranges, the linear constraints, and the functional constraints.
     *  @param x the input values as an array. The order is used to interpret the name.
     *  @return true if the inputs are input feasible
     */
    fun isInputFeasible(x: DoubleArray): Boolean {
        return isInputFeasible(toInputMap(x))
    }

    /**
     *  Generates a random point within the ranges defined by the inputs.
     *  The point will have the appropriate granularity based on the definitions of the inputs.
     *
     *  @param rnStream the stream to use when generating random points within the input range space.
     *  By default, this uses the default random number stream [KSLRandom.defaultRNStream]
     *  @return the randomly generated point.
     */
    fun generateRandomInputValues(
        rnStream: RNStreamIfc = KSLRandom.defaultRNStream()
    ): InputMap {
        val map = mutableMapOf<String, Double>()
        for ((name, iDef) in myInputDefinitions) {
            map[name] = iDef.randomValue(rnStream)
        }
        return InputMap(this, map)
    }

    /**
     *  Randomly generates a new value for the named input variable and returns a new
     *  input map. The input map may not be feasible with respect to linear or functional constraints.
     *
     *  @param map the input map for which the named variable will be changed
     *  @param rnStream the stream to use when generating random points within the input range space.
     *  By default, this uses the default random number stream [KSLRandom.defaultRNStream]
     *  @param name the name of the input variable to randomize. Must be a valid name for
     *  the input map and thus for the problem. The default is a randomly selected name
     *  from the problem using the supplied random number stream.
     *  @return the randomly generated point.
     */
    fun randomizeInputValue(
        map: MutableMap<String, Double>,
        rnStream: RNStreamIfc = KSLRandom.defaultRNStream(),
        name: String = randomInputName(rnStream),
    ): InputMap {
        require(map.containsKey(name)) { "The supplied input map does not contain the variable: $name" }
        val iDefinition = myInputDefinitions[name]!!
        map[name] = iDefinition.randomValue(rnStream)
        roundToGranularity(map)
        return InputMap(this, map)
    }

    /**
     *  Randomly generates a new value for the named input variable and a new
     *  input map. The input should be feasible with respect to linear or functional constraints.
     *  If the number of sampling iterations needed to get a feasible point exceeds [maxFeasibleSamplingIterations]
     *  then an IllegalStateException will occur.
     *
     *  @param name the name of the input variable to randomize. Must be a valid name for
     *  the input map and thus for the problem.
     *  @param map the input map for which the named variable will be changed
     *  @param rnStream the stream to use when generating random points within the input range space.
     *  By default, this uses the default random number stream [KSLRandom.defaultRNStream]
     *  @return the randomly generated point.
     */
    fun randomizeInputFeasibleValue(
        map: MutableMap<String, Double>,
        rnStream: RNStreamIfc = KSLRandom.defaultRNStream(),
        name: String = randomInputName(rnStream)
    ): InputMap {
        require(map.containsKey(name)) { "The supplied input map does not contain the variable: $name" }
        var count = 0
        do {
            count++
            check(count <= maxFeasibleSamplingIterations) { "The number of iterations exceeded the limit $maxFeasibleSamplingIterations when sampling for an input feasible point" }
            // generate the point
            val iDefinition = myInputDefinitions[name]!!
            map[name] = iDefinition.randomValue(rnStream)
        } while (!isInputFeasible(map))
        return InputMap(this, map)
    }

    /**
     *  Generates a random point that is feasible with respect to the input ranges,
     *  the linear constraints, and the functional constraints.
     *  If the number of sampling iterations needed to get a feasible point exceeds [maxFeasibleSamplingIterations]
     *  then an IllegalStateException will occur.
     *
     *  @param rnStream the stream to use when generating random points within the input range space.
     *  By default, this uses the default random number stream [KSLRandom.defaultRNStream]
     *  @return the sampled point
     */
    fun generateInputFeasibleValues(
        rnStream: RNStreamIfc = KSLRandom.defaultRNStream()
    ): InputMap {
        var count = 0
        var inputMap: InputMap
        do {
            count++
            check(count <= maxFeasibleSamplingIterations) { "The number of iterations exceeded the limit $maxFeasibleSamplingIterations when sampling for an input feasible point" }
            // generate the point
            inputMap = generateRandomInputValues(rnStream)
        } while (!isInputFeasible(inputMap))
        return inputMap
    }

    /**
     *  Returns a starting point for the problem. If the user specified an instance of
     *  the [StartingPointIfc] via the [ProblemDefinition.startingPointGenerator] property then
     *  the supplied generator is used; otherwise, the problem definition attempts
     *  to randomly generate an input feasible starting point via the [ProblemDefinition.generateInputFeasibleValues]
     *  function.
     *
     *  @param rnStream the stream to use when generating random points within the input range space.
     *  By default, this uses the default random number stream [KSLRandom.defaultRNStream]
     *  @return the starting point
     */
    fun startingPoint(
        rnStream: RNStreamIfc = KSLRandom.defaultRNStream()
    ): InputMap {
        return startingPointGenerator?.startingPoint(this) ?: generateInputFeasibleValues(rnStream)
    }

    /**
     *  Creates an infeasible and bad solution. This is useful when initializing solvers
     *  or when rejecting requests.
     *
     *  @return the unbelievably bad solution
     */
    fun badSolution(): Solution {
        val inputMap = midPoints()
        inputMap.makeInfeasible()
        val objFunc = EstimatedResponse(objFnResponseName, Double.MAX_VALUE, Double.MAX_VALUE, 1.0)
        val list = mutableListOf<EstimatedResponse>()
        for (rc in responseConstraints) {
            val rValue = rc.ltRHSValue + Int.MAX_VALUE
            val er = EstimatedResponse(rc.responseName, rValue, Double.MAX_VALUE, 1.0)
            list.add(er)
        }
        return Solution(inputMap, 1, objFunc, list, 1)
    }

    override fun toString(): String {
        val sb = StringBuilder().apply {
            appendLine("Problem Name: $name")
            appendLine("Model Identifier: $modelIdentifier")
            appendLine("Objective Function Response Name: $objFnResponseName")
            appendLine("Input Names:")
            for (name in inputNames) {
                append("\t ")
                appendLine(name)
            }
            appendLine("Response Names:")
            if (responseNames.isNotEmpty()) {
                for (name in responseNames) {
                    append("\t ")
                    appendLine(name)
                }
            } else {
                appendLine("\t <None>")
            }
            appendLine("Input Definitions:")
            val iDefns = inputDefinitionList
            if (iDefns.isNotEmpty()) {
                for (iDefn in iDefns) {
                    append("\t ")
                    appendLine(iDefn)
                }
            } else {
                appendLine("\t <None>")
            }
            appendLine("Linear Constraints:")
            if (myLinearConstraints.isNotEmpty()) {
                for (constraint in myLinearConstraints) {
                    append("\t ")
                    appendLine(constraint)
                }
            } else {
                appendLine("\t <None>")
            }
            appendLine("Functional Constraints:")
            if (myFunctionalConstraints.isNotEmpty()) {
                for (constraint in myFunctionalConstraints) {
                    append("\t ")
                    appendLine(constraint)
                }
            } else {
                appendLine("\t <None>")
            }
            appendLine("Response Constraints:")
            if (myResponseConstraints.isNotEmpty()) {
                for (constraint in myResponseConstraints) {
                    append("\t ")
                    appendLine(constraint)
                }
            } else {
                appendLine("\t <None>")
            }
        }
        return sb.toString()
    }

    companion object {

        /**
         *  The default maximum number of iterations for when sampling for a feasible input point
         */
        var defaultMaximumFeasibleSamplingIterations = 10000
            set(value) {
                require(value >= 1) { "The default maximum number of iterations for sampling must be > 0" }
                field = value
            }

        /** Can be used to validate that the supplied names are valid for a problem definition
         *
         *  @return true if the key names in [inputs] all appear in the set [names]
         */
        fun validate(inputs: Map<String, Double>, names: List<String>): Boolean {
            require(inputs.size == names.size) { "The size of the map and set are incompatible." }
            for ((name, _) in inputs) {
                if (!names.contains(name)) return false
            }
            return true
        }
    }
}