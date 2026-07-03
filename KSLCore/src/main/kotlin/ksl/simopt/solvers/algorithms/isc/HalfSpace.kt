package ksl.simopt.solvers.algorithms.isc

import ksl.simopt.problem.InequalityType
import ksl.simopt.problem.LinearConstraint

/**
 *  A linear half-space in the normalized "less-than-or-equal" orientation: the set of points `x`
 *  satisfying `a · x <= b`. ISC's most-promising-area polytope, the original problem's linear
 *  constraints, and the COMPASS halfway hyperplanes are all represented uniformly as half-spaces so
 *  that membership tests, redundancy checks, and the RMD sampler can share one representation.
 *
 *  @param a the coefficient vector, in the problem's input order
 *  @param b the right-hand-side bound
 */
class HalfSpace(val a: DoubleArray, val b: Double) {

    /** The left-hand side `a · x` evaluated at [x]. */
    fun lhs(x: DoubleArray): Double {
        var s = 0.0
        for (i in a.indices) s += a[i] * x[i]
        return s
    }

    /** True if `a · x <= b` within the supplied tolerance. */
    fun isSatisfied(x: DoubleArray, tol: Double = defaultTolerance): Boolean = lhs(x) <= b + tol

    override fun toString(): String = "HalfSpace(${a.joinToString(prefix = "[", postfix = "]")} . x <= $b)"

    companion object {
        const val defaultTolerance: Double = 1.0e-9

        /**
         *  Builds a half-space (in `a · x <= b` form) from a problem [LinearConstraint], expanding the
         *  constraint's coefficients to the full input order and negating a greater-than constraint so
         *  the result is always less-than-or-equal.
         *
         *  @param constraint the linear constraint
         *  @param inputNames the problem's input names, defining the coefficient order
         */
        fun fromLinearConstraint(constraint: LinearConstraint, inputNames: List<String>): HalfSpace {
            val coefficients = constraint.coefficients(inputNames) // unadjusted, full input order
            return if (constraint.inequalityType == InequalityType.LESS_THAN) {
                HalfSpace(coefficients, constraint.rhsValue)
            } else {
                HalfSpace(DoubleArray(coefficients.size) { -coefficients[it] }, -constraint.rhsValue)
            }
        }
    }
}
