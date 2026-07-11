package ksl.simopt.problem

import ksl.simopt.evaluator.Solution

/**
 *  The capability that a penalty function binds to (Option B): a constraint that can report its own
 *  violation magnitude for a given solution. Deterministic constraints ([ConstraintIfc]) read the
 *  solution's input values; response constraints ([ResponseConstraint]) read the estimated response.
 *
 *  Both constraint families implement this so a single [PenaltyFunction] slot can serve either.
 */
interface PenalizableConstraint {
    /**
     *  The non-negative constraint-violation magnitude for the supplied solution (0 means satisfied).
     */
    fun violation(solution: Solution): Double

    /**
     *  A stable identity used to key this constraint's penalty memory on a solution. Response
     *  constraints use their (problem-unique) response name; deterministic constraints carry no
     *  penalty memory (their violation is exact), so their key is a formal identity only.
     */
    val key: String
}
