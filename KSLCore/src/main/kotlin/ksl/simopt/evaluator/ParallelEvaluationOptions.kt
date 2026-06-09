package ksl.simopt.evaluator

/**
 * Options that select and configure parallel evaluation when building an evaluator via
 * `Evaluator.createProblemEvaluator` (and the `Solver.create*` factories that delegate to it).
 *
 * Defaults preserve the historical behavior: [enabled] = false builds the sequential
 * `SimulationProvider`. When [enabled] is true, a `ParallelSimulationProvider` is built instead,
 * which evaluates the points of a multi-point request concurrently (single-point requests still
 * run on one reused model — see [shortCircuitSinglePoint]).
 *
 * @param enabled when true, build a parallel evaluation oracle; default false (sequential)
 * @param numWorkers the maximum number of concurrent model evaluations; null uses the number of
 *   available processors. Must be > 0 when specified.
 * @param shortCircuitSinglePoint when true (default), a single-point request runs on one reused
 *   model rather than the parallel path; see `ParallelSimulationProvider`
 */
data class ParallelEvaluationOptions(
    val enabled: Boolean = false,
    val numWorkers: Int? = null,
    val shortCircuitSinglePoint: Boolean = true
) {
    init {
        require(numWorkers == null || numWorkers > 0) {
            "numWorkers must be > 0 when specified; was $numWorkers"
        }
    }
}
