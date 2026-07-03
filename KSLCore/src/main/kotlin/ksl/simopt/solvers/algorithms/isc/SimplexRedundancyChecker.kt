package ksl.simopt.solvers.algorithms.isc

import org.hipparchus.exception.MathIllegalStateException
import org.hipparchus.optim.MaxIter
import org.hipparchus.optim.PointValuePair
import org.hipparchus.optim.linear.LinearConstraint
import org.hipparchus.optim.linear.LinearConstraintSet
import org.hipparchus.optim.linear.LinearObjectiveFunction
import org.hipparchus.optim.linear.NonNegativeConstraint
import org.hipparchus.optim.linear.Relationship
import org.hipparchus.optim.linear.SimplexSolver
import org.hipparchus.optim.nonlinear.scalar.GoalType

/**
 *  A linear-programming–backed redundancy checker built on Hipparchus's [SimplexSolver]. A half-space
 *  `a · x <= b` is redundant with respect to a set of other half-spaces exactly when the largest value
 *  of `a · x` over the region defined by the others does not exceed `b`. This checker therefore solves
 *  the LP `max a · x  s.t.  others` and declares the target redundant when the optimum is `<= b`
 *  (within [tolerance]):
 *
 *  - **bounded optimum `<= b`** ⇒ every feasible point already satisfies the target ⇒ **redundant**;
 *  - **bounded optimum `> b`** ⇒ some feasible point violates the target ⇒ **not redundant**;
 *  - **unbounded** (the others do not bound `a · x` from above) ⇒ the target can be violated
 *    arbitrarily ⇒ **not redundant**;
 *  - **infeasible others** (their intersection is empty) ⇒ the target is vacuously satisfied by every
 *    feasible point ⇒ **redundant**.
 *
 *  Decision variables are treated as free (they may be negative), since the polytope half-spaces are
 *  expressed in the problem's natural coordinates, which can include negative values. The semantics
 *  match [BruteForceRedundancyChecker] (Fourier–Motzkin) on the supplied half-space set; this LP-based
 *  variant is the recommended choice in higher dimensions, where Fourier–Motzkin elimination can blow
 *  up. On any unexpected solver state the checker delegates to a [BruteForceRedundancyChecker] so a
 *  decision is always returned.
 *
 *  This is the LP upgrade described in the ISC implementation plan; it requires the `hipparchus-optim`
 *  dependency.
 *
 *  @param tolerance the slack used when comparing the LP optimum to the bound; defaults to
 *  [HalfSpace.defaultTolerance]
 *  @param maxIterations the simplex iteration cap; must be at least 1
 */
class SimplexRedundancyChecker(
    val tolerance: Double = HalfSpace.defaultTolerance,
    val maxIterations: Int = DEFAULT_MAX_ITERATIONS
) : RedundantConstraintChecker {

    init {
        require(tolerance >= 0.0) { "tolerance must be non-negative" }
        require(maxIterations >= 1) { "maxIterations must be at least 1" }
    }

    private val fallback = BruteForceRedundancyChecker(tolerance)

    override fun isRedundant(target: HalfSpace, others: List<HalfSpace>): Boolean {
        if (others.isEmpty()) {
            // Unconstrained region: a . x is unbounded above unless a is identically zero.
            val allZero = target.a.all { it == 0.0 }
            return allZero && 0.0 <= target.b + tolerance
        }
        val objective = LinearObjectiveFunction(target.a.copyOf(), 0.0)
        val constraints = others.map { LinearConstraint(it.a.copyOf(), Relationship.LEQ, it.b) }
        val solver = SimplexSolver()
        return try {
            val result: PointValuePair = solver.optimize(
                objective,
                LinearConstraintSet(constraints),
                GoalType.MAXIMIZE,
                NonNegativeConstraint(false),
                MaxIter(maxIterations)
            )
            result.value <= target.b + tolerance
        } catch (e: MathIllegalStateException) {
            when (e.specifier) {
                org.hipparchus.optim.LocalizedOptimFormats.UNBOUNDED_SOLUTION -> false // can be violated arbitrarily
                org.hipparchus.optim.LocalizedOptimFormats.NO_FEASIBLE_SOLUTION -> true // vacuously redundant
                else -> fallback.isRedundant(target, others) // unexpected state: decide conservatively
            }
        }
    }

    companion object {
        /** Default simplex iteration cap. */
        const val DEFAULT_MAX_ITERATIONS: Int = 10_000
    }
}
