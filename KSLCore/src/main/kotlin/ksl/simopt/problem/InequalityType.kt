package ksl.simopt.problem

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