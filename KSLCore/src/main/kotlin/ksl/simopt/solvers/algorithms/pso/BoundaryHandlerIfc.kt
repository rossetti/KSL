package ksl.simopt.solvers.algorithms.pso

import ksl.simopt.problem.ProblemDefinition

/**
 *  Strategy interface for handling particles that move outside the input ranges. Given a continuous
 *  position, a boundary handler returns a position brought back within each input's `[lowerBound,
 *  upperBound]`. The handler returns a new array; it does not modify the supplied one. The result
 *  is still passed through [ksl.simopt.problem.ProblemDefinition.toInputMap] by the solver for
 *  granularity rounding before evaluation.
 */
fun interface BoundaryHandlerIfc {

    /**
     *  Enforces the input-range bounds on the supplied continuous position.
     *
     *  @param x the continuous position (problem input order)
     *  @param problemDefinition the problem definition supplying per-input bounds
     *  @return a new position within the input ranges
     */
    fun enforce(x: DoubleArray, problemDefinition: ProblemDefinition): DoubleArray
}

/**
 *  Clamps each coordinate to its input range: values below the lower bound become the lower bound,
 *  values above the upper bound become the upper bound. This is the default boundary handler.
 */
class ClampToBounds : BoundaryHandlerIfc {

    override fun enforce(x: DoubleArray, problemDefinition: ProblemDefinition): DoubleArray {
        val lb = problemDefinition.inputLowerBounds
        val ub = problemDefinition.inputUpperBounds
        val result = x.copyOf()
        for (i in result.indices) {
            if (result[i] < lb[i]) result[i] = lb[i]
            else if (result[i] > ub[i]) result[i] = ub[i]
        }
        return result
    }

    override fun toString(): String = "ClampToBounds()"
}

/**
 *  Reflects overshoot back into the input range: a value that exceeds a bound is mirrored back
 *  inside by the amount of the overshoot. A final clamp guards against a reflection that would
 *  overshoot the opposite bound (possible only when the overshoot exceeds the range width).
 */
class ReflectAtBounds : BoundaryHandlerIfc {

    override fun enforce(x: DoubleArray, problemDefinition: ProblemDefinition): DoubleArray {
        val lb = problemDefinition.inputLowerBounds
        val ub = problemDefinition.inputUpperBounds
        val result = x.copyOf()
        for (i in result.indices) {
            var v = result[i]
            if (v < lb[i]) {
                v = lb[i] + (lb[i] - v)
            } else if (v > ub[i]) {
                v = ub[i] - (v - ub[i])
            }
            // Guard against a reflection overshooting the opposite bound.
            if (v < lb[i]) v = lb[i] else if (v > ub[i]) v = ub[i]
            result[i] = v
        }
        return result
    }

    override fun toString(): String = "ReflectAtBounds()"
}
