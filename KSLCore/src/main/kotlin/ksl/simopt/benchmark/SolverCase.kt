package ksl.simopt.benchmark

/**
 *  A named solver configuration for benchmarking. The factory creates a fresh,
 *  member-safe solver instance per cell (a new instance on every call, bound only to
 *  the supplied evaluator, with its own solver-side streams), for whatever problem the
 *  experiment hands it — the same configuration races on every problem in the grid.
 *  The harness reads each instance's `configurationProperties` so the configuration
 *  that actually ran is recorded rather than assumed.
 *
 *  Budget agnosticism: the case's factory should configure the algorithm (schedules,
 *  population sizes, replications per evaluation) but NOT its termination — the
 *  benchmark experiment wraps the factory so every created instance receives the
 *  experiment's replication-budget stopping criterion and an iteration ceiling derived
 *  from the budget. A maximum-iteration count set by the case's factory is overridden.
 *
 *  @param label a short, unique (within an experiment) identifier for the
 *  configuration; flows into cell labels, solver names, and result records
 *  @param solverFactory creates a fresh solver instance per benchmark cell
 *  @param description an optional human-readable description of the configuration
 */
class SolverCase(
    val label: String,
    val solverFactory: BenchmarkSolverFactoryIfc,
    val description: String = ""
) {
    init {
        require(label.isNotBlank()) { "The solver case label must not be blank" }
    }

    override fun toString(): String {
        return "SolverCase(label=$label${if (description.isNotBlank()) ", description=$description" else ""})"
    }
}
