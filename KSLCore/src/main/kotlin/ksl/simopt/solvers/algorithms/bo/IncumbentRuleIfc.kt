package ksl.simopt.solvers.algorithms.bo

import ksl.simopt.evaluator.Solution

/**
 *  Strategy for choosing the incumbent value — the "value to beat" passed to the acquisition
 *  function. Under simulation noise, the best *observed* value is optimistically biased, so a
 *  posterior-mean-based incumbent is generally preferred.
 */
fun interface IncumbentRuleIfc {
    /** The current incumbent objective value (for minimization). */
    fun incumbent(bo: BayesianOptimizationSolver): Double
}

/**
 *  The points an incumbent is drawn from: those we are confident are response-feasible, or all of
 *  them when none qualifies.
 *
 *  The incumbent is the value an acquisition function must beat, so it sets what counts as
 *  improvement. Drawn from every observed point, an infeasible design that is cheap because it is
 *  infeasible becomes the bar, and the search is rewarded for finding more of them. Restricting to
 *  confidently-feasible points makes improvement mean improvement on the answer the run will
 *  actually report -- the same standard `Solver.bestSolution` and the benchmark's confirmation
 *  stage apply. Falling back to the whole set keeps the rule defined before anything feasible has
 *  been found, when the search still needs a bar to climb.
 */
private fun incumbentPool(bo: BayesianOptimizationSolver): List<Solution> {
    val observed = bo.observedSolutions
    if (observed.isEmpty()) return observed
    return observed.filter { it.isResponseConstraintFeasible(bo.recommendationCILevel) }
        .ifEmpty { observed }
}

/**
 *  The minimum surrogate posterior mean over the confidently-feasible observed points. This is
 *  noise-robust: it does not reward a point merely for having received a lucky (low) noisy
 *  observation. This is the default.
 */
class BestPosteriorMeanIncumbent : IncumbentRuleIfc {
    override fun incumbent(bo: BayesianOptimizationSolver): Double {
        val pool = incumbentPool(bo)
        if (pool.isEmpty()) return Double.POSITIVE_INFINITY
        var best = Double.POSITIVE_INFINITY
        for (s in pool) {
            val mu = bo.surrogate.predict(s.inputMap.inputValues).mean
            if (mu < best) best = mu
        }
        return best
    }

    override fun toString(): String = "BestPosteriorMeanIncumbent()"
}

/**
 *  The minimum observed (penalized) objective value over the confidently-feasible observed points,
 *  every point judged at the solver's current clock so the comparison is within one subproblem of
 *  the penalty sequence rather than across several.
 */
class BestObservedIncumbent : IncumbentRuleIfc {
    override fun incumbent(bo: BayesianOptimizationSolver): Double {
        val pool = incumbentPool(bo)
        if (pool.isEmpty()) return Double.POSITIVE_INFINITY
        val clock = bo.currentEvaluationClock
        var best = Double.POSITIVE_INFINITY
        for (s in pool) {
            val v = s.penalizedObjFncValueAt(clock)
            if (v < best) best = v
        }
        return best
    }

    override fun toString(): String = "BestObservedIncumbent()"
}
