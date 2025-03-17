package ksl.simopt.problem

import ksl.utilities.Interval


/**
 * enum to codify < and > in constraints for user convenience in problem definition.
 * (Internally all input and response constraints are implemented as <)
 * We could instead adopt one version (typically < in the literature)
 * and force the user to modify their coefficients.
 */
enum class InequalityType {
    LESS_THAN,
    GREATER_THAN
}



class ProblemDefinition(
    val objFnResponseName: String,
    val inputNames: Set<String>,
    val responseNames: Set<String>
) {

    var maxSamplesPerMember = 1E5.toInt()
        set(value) {
            require (value > 0) {"The maximum number of samples per member is $value, must be > 0"}
            field = value
        }


}