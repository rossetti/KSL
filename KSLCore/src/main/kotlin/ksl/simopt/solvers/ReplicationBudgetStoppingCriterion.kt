package ksl.simopt.solvers

/**
 *  Stops a solver once its cumulative requested replications reach the budget — the
 *  equal-effort termination rule for fair cross-algorithm comparison. Assign an instance
 *  to the solver's [Solver.solutionQualityEvaluator] property (and give the solver a
 *  generous maximum number of iterations so the budget, not the iteration limit, is the
 *  binding constraint).
 *
 *  The criterion is consulted between iterations, so solvers that consume many
 *  replications per iteration (population-based algorithms) may overshoot the budget by
 *  up to one generation. Consumers comparing algorithms should therefore read the
 *  solver's actual [Solver.numReplicationsRequested] after the run and normalize by it,
 *  rather than assuming the budget was consumed exactly.
 *
 *  The budget is a ceiling, not a floor: a solver whose own stopping rules (iteration
 *  limit, temperature schedule, no-improvement checks) would stop it earlier still stops
 *  earlier.
 *
 *  Instances are stateless — all accounting lives in the solver — so one instance may be
 *  shared across solvers, including concurrently executing ones.
 *
 *  @param replicationBudget the total number of requested replications at which the
 *  solver stops; must be positive
 */
class ReplicationBudgetStoppingCriterion(
    val replicationBudget: Int
) : SolutionQualityEvaluatorIfc {

    init {
        require(replicationBudget > 0) { "The replication budget must be > 0" }
    }

    override fun isStoppingCriteriaReached(solver: Solver): Boolean {
        return solver.numReplicationsRequested >= replicationBudget
    }
}
