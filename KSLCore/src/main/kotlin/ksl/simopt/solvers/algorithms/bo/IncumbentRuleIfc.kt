package ksl.simopt.solvers.algorithms.bo

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
 *  The minimum surrogate posterior mean over the observed points. This is noise-robust: it does not
 *  reward a point merely for having received a lucky (low) noisy observation. This is the default.
 */
class BestPosteriorMeanIncumbent : IncumbentRuleIfc {
    override fun incumbent(bo: BayesianOptimizationSolver): Double {
        val observed = bo.observedSolutions
        if (observed.isEmpty()) return Double.POSITIVE_INFINITY
        var best = Double.POSITIVE_INFINITY
        for (s in observed) {
            val mu = bo.surrogate.predict(s.inputMap.inputValues).mean
            if (mu < best) best = mu
        }
        return best
    }

    override fun toString(): String = "BestPosteriorMeanIncumbent()"
}

/**
 *  The minimum observed (penalized) objective value over the observed points.
 */
class BestObservedIncumbent : IncumbentRuleIfc {
    override fun incumbent(bo: BayesianOptimizationSolver): Double {
        val observed = bo.observedSolutions
        if (observed.isEmpty()) return Double.POSITIVE_INFINITY
        var best = Double.POSITIVE_INFINITY
        for (s in observed) {
            val v = s.penalizedObjFncValue
            if (v < best) best = v
        }
        return best
    }

    override fun toString(): String = "BestObservedIncumbent()"
}
