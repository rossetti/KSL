package ksl.simopt.problem

import ksl.simopt.evaluator.EstimatedResponse
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

    /**
     *  True when this penalty accumulates memory across visits, so the evaluator folds each visit's
     *  new observations into it. Memoryless penalties leave this false and carry no memory.
     */
    open val usesMemory: Boolean get() = false

    /**
     *  Folds a visit's new observations for this penalty's constraint into the prior memory, returning
     *  the snapshot to carry on the resulting solution. Memoryless penalties keep the default (null).
     *
     *  @param newObservations the estimate over the observations obtained at this visit
     *  @param prior the memory carried from a prior visit, or null on the first visit
     *  @param iteration the current evaluation iteration
     */
    open fun foldVisit(
        newObservations: EstimatedResponse,
        prior: PenaltyMemory?,
        iteration: Int
    ): PenaltyMemory? = null
}
