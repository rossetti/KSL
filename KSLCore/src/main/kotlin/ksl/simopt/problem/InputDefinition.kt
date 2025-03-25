package ksl.simopt.problem

import ksl.utilities.Interval
import ksl.utilities.KSLArrays
import ksl.utilities.math.KSLMath

/**
 *  Represents the definition of an input variable for a ProblemDefinition.
 *  Input variables are the variables used in the problem to model the decision parameter
 *  of the simulation model. The input variable name should correspond to some named parameter
 *  (e.g. control) in the model.
 *
 *  The specified granularity indicates the acceptable precision for the variable's value
 *  with respect to decision-making. If the granularity is 0 then no rounding will be applied
 *  when evaluating the variable. Granularity defines the level of precision for an input variable
 *  to which the problem will be solved. Setting granularity to 0, the default, means that the solver
 *  will attempt to find a solution to the level of machine precision. For any positive granularity value,
 *  the solution will be found to some multiple of that granularity. As a special case, setting granularity to 1
 *  implies an integer-ordered input variable. The specification of granularity reflects a reality for the
 *  decision maker that there is a level of precision beyond which it is not practical to implement a solution.
 *
 *  @param name the name of the input variable
 *  @param lowerBound the lower bound on the range of possible values
 *  @param upperBound the upper bound on the range of possible values
 *  @param granularity the acceptable precision for decision-making
 */
class InputDefinition(
    val name: String,
    val lowerBound: Double,
    val upperBound: Double,
    granularity: Double = 0.0
){
    init {
        require(name.isNotBlank()) { "name cannot be blank" }
        require(lowerBound < upperBound) { "lower bound must be less than upper bound" }
        require(granularity >= 0.0) { "granularity must be >=  0.0" }
    }

    constructor(
        name: String,
        interval: Interval,
        granularity: Double
    ) : this(name, interval.lowerLimit, interval.upperLimit, granularity)

    val interval: Interval
         get() = Interval(lowerBound, upperBound)

    var granularity: Double = granularity
        set(value) {
            require(value >= 0.0) { "granularity must be >=  0.0" }
            field = value
        }

    /**
     *
     * @param x the value to check
     * @return true if x is in the interval defined by the lower and upper bounds (includes end points)
     */
    operator fun contains(x: Double): Boolean {
        return x in lowerBound..upperBound
    }

    /**
     *  This function does not check if the supplied value is within the specified bounds.
     *  Thus, the returned value may be infeasible with respect to bounds.
     *
     *  @param x the input value to round to the specified granularity
     *  @return the rounded value
     */
    fun roundToGranularity(x: Double): Double {
        return KSLMath.mround(x, granularity)
    }

}
