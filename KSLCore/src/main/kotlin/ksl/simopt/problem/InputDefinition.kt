package ksl.simopt.problem

import ksl.utilities.Interval
import ksl.utilities.math.KSLMath
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom
import kotlin.math.ceil
import kotlin.math.floor

/**
 *  Represents the definition of an input variable for a ProblemDefinition.
 *  Input variables are the variables used in the problem to model the decision parameter
 *  of the simulation model. The input variable name should correspond to some named parameter
 *  (e.g., control) in the model.
 *
 *  The specified granularity indicates the acceptable precision for the variable's value
 *  with respect to decision-making. If the granularity is 0, then no rounding will be applied
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
class InputDefinition @JvmOverloads constructor(
    val name: String,
    val lowerBound: Double,
    val upperBound: Double,
    granularity: Double = 0.0
) {
    init {
        require(name.isNotBlank()) { "name cannot be blank" }
        require(lowerBound.isFinite()) {"The lower bound must be finite."}
        require(upperBound.isFinite()) {"The upper bound must be finite."}
        require(lowerBound < upperBound) { "The lower bound must be less than upper bound" }
        require(granularity.isFinite()) {"The granularity must be finite."}
        require(granularity >= 0.0) { "granularity must be >=  0.0" }
    }

    /**
     *  Represents the definition of an input variable for a ProblemDefinition.
     *  Input variables are the variables used in the problem to model the decision parameter
     *  of the simulation model. The input variable name should correspond to some named parameter
     *  (e.g., control) in the model.
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
     *  @param interval the feasible range of the variable as an interval
     *  @param granularity the acceptable precision for decision-making
     */
    @Suppress("unused")
    constructor(
        name: String,
        interval: Interval,
        granularity: Double
    ) : this(name, interval.lowerLimit, interval.upperLimit, granularity)

    /**
     *  Represents the definition of an integer input variable for a ProblemDefinition.
     *  Input variables are the variables used in the problem to model the decision parameter
     *  of the simulation model. The input variable name should correspond to some named parameter
     *  (e.g., control) in the model. The granularity of the variable will be 1.0
     *
     *  @param name the name of the input variable
     *  @param interval the feasible range of the variable as an IntRange
     */
    @Suppress("unused")
    constructor(
        name: String,
        interval: IntRange,
    ) : this(name, interval.first, interval.last)

    /**
     *  Represents the definition of an integer input variable for a ProblemDefinition.
     *  Input variables are the variables used in the problem to model the decision parameter
     *  of the simulation model. The input variable name should correspond to some named parameter
     *  (e.g., control) in the model. The granularity of the variable will be 1.0
     *
     *  @param name the name of the input variable
     *  @param lowerBound the lower bound on the range of possible values
     *  @param upperBound the upper bound on the range of possible values
     */
    @Suppress("unused")
    constructor(
        name: String,
        lowerBound: Int,
        upperBound: Int,
    ) : this(name, lowerBound.toDouble(), upperBound.toDouble(), 1.0)

    /**
     *  The interval over which the variable is defined.
     */
    val interval: Interval
        get() = Interval(lowerBound, upperBound)

    /**
     *  The specified granularity indicates the acceptable precision for the variable's value
     *  with respect to decision-making. If the granularity is 0, then no rounding will be applied
     *  when evaluating the variable. Granularity defines the level of precision for an input variable
     *  to which the problem will be solved. Setting granularity to 0, the default, means that the solver
     *  will attempt to find a solution to the level of machine precision. For any positive granularity value,
     *  the solution will be found to some multiple of that granularity. As a special case, setting granularity to 1
     *  implies an integer-ordered input variable. The specification of granularity reflects a reality for the
     *  decision maker that there is a level of precision beyond which it is not practical to implement a solution.
     */
    var granularity: Double = granularity
        set(value) {
            require(value >= 0.0) { "granularity must be >=  0.0" }
            field = value
        }

    /**
     *  True when this variable's values are spaced exactly one apart, which requires a
     *  granularity of exactly 1.0.
     *
     *  Note that this is STRICTER than the usual meaning of "integer-ordered" in the
     *  discrete-optimization-via-simulation literature, where it is enough that the feasible
     *  values be integers. A variable with granularity 5.0 over the range 30 to 100 takes only
     *  integer values and is integer-ordered in that looser sense, but it is not unit-spaced and
     *  this property is false for it.
     *
     *  The distinction is not academic: the solvers that require this property step one unit
     *  along a coordinate at a time, in the variable's own units, so a coarser spacing would put
     *  every step between feasible values rather than on them.
     */
    val isIntegerOrdered: Boolean
        get() = KSLMath.equal(granularity, 1.0)

    /**
     *  The mid-point of the variable. The returned pair is the name and mid-point value.
     */
    val midPoint: Pair<String, Double> = Pair(name, (lowerBound + upperBound) / 2.0)

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
        return KSLMath.gRound(x, granularity)
    }

    /**
     *  Randomly generates a value within the input variable's range with
     *  the appropriate granularity
     *  @param rnStream a random number stream. By default, this uses
     *  the default random number stream [KSLRandom.defaultRNStream]
     *  @return the generated point
     */
    fun randomValue(rnStream: RNStreamIfc = KSLRandom.defaultRNStream()): Double {
        val x = rnStream.rUniform(lowerBound, upperBound)
        return roundToGranularity(x)
    }

    /**
     *  Requires granularity to be greater than 0.0 or an IllegalArgumentException will be thrown.
     *  If granularity is 0.0, then the set of points is infinite.
     *
     *  Returns a list of points starting at the lower-bound stepping by the
     *  granularity to the upper bound. This is the set of possible points
     *  for the defined interval based on the granularity.
     *
     *  @return the list of points
     */
    @Suppress("unused")
    fun granularPoints(): List<Double> {
        require(granularity > 0.0) { "granularity must be > 0.0" }
        val list = mutableListOf<Double>()
        var x = lowerBound
        do {
            val y = roundToGranularity(x)
            list.add(y)
            x = y + granularity
        } while (x <= upperBound)
        return list
    }

    /**
     *  The number of distinct grid values this variable can take on: the multiples of `granularity`
     *  that fall within the closed range from `lowerBound` to `upperBound`. This is the count that
     *  `granularPoints` would produce (computed in closed form, without materializing the list).
     *
     *  Returns `null` when the variable is continuous (`granularity == 0.0`), i.e. the number of
     *  distinct values is effectively unbounded. Returns `0` when the granularity is so coarse that no
     *  multiple of it lies within the bounds (e.g. range `0.4..0.6` with granularity `1.0`), in which
     *  case there is no feasible grid value at all.
     */
    fun numGranularPoints(): Long? {
        if (granularity <= 0.0) return null
        val eps = 1e-9
        val loMultiple = ceil(lowerBound / granularity - eps)
        val hiMultiple = floor(upperBound / granularity + eps)
        val count = hiMultiple - loMultiple + 1.0
        return if (count < 1.0) 0L else count.toLong()
    }

    override fun toString(): String {
        return "InputDefinition(name='$name', interval=$interval, granularity=$granularity)"
    }


}
