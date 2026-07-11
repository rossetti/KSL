package ksl.simopt.problem

import ksl.simopt.evaluator.Solution

/**
 *  A penalty function applied to a constraint's violation; the penalty is added to the problem's
 *  objective function.
 *
 *  Following Option B, a penalty is bound to the constraint it serves (see [constraint]). A
 *  problem-level default is an unbound template (constraint = null) that [ProblemDefinition]
 *  resolves into a per-constraint bound instance via [boundTo] before it is used to compute a value.
 */
abstract class PenaltyFunction(
    /**
     *  The constraint this penalty serves, or null for an unbound template. Bound instances that are
     *  actually used to compute a penalty always have a non-null value.
     */
    val constraint: PenalizableConstraint?
) {
    /**
     *  Returns a copy of this penalty bound to the supplied constraint. Used to resolve a default
     *  template into a per-constraint instance.
     */
    abstract fun boundTo(c: PenalizableConstraint): PenaltyFunction

    /**
     *  The penalty contribution for this penalty's constraint at the supplied solution. Must be a
     *  pure function of the solution (and this penalty's own state) so that the penalized objective
     *  is a stable, Solver-free property of an immutable solution. Called only on bound instances.
     */
    abstract fun penalty(solution: Solution): Double
}
