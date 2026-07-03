package ksl.simopt.solvers.concurrent

/**
 * Options for the optional confirmation stage run after all members of a concurrent
 * solver run complete. Members can return "best" solutions estimated with different
 * precision (different replication counts, different luck), so picking the winner from
 * point estimates alone favors noise. The confirmation stage re-evaluates the top
 * candidates with common random numbers and picks the winner from the confirmed
 * estimates — standard ranking-and-selection hygiene.
 *
 * @param topK the number of best candidate solutions to confirm; must be at least 1
 * @param replicationsPerCandidate the number of replications for each confirmed
 * candidate; must be at least 1
 */
data class ConfirmationOptions(
    val topK: Int = 3,
    val replicationsPerCandidate: Int = 50
) {
    init {
        require(topK >= 1) { "topK must be >= 1" }
        require(replicationsPerCandidate >= 1) { "replicationsPerCandidate must be >= 1" }
    }
}

/**
 * Options governing concurrent solver execution (parallel random restarts, solver
 * portfolios).
 *
 * @param numWorkers the maximum number of members running at the same time; null (the
 * default) uses the smaller of the member count and the available processors. Must be
 * positive when specified.
 * @param substreamBlockSize the size of the sub-stream tape block reserved for each
 * member: member k's simulation streams start at sub-stream index k times this value,
 * so members draw from non-overlapping tape regions regardless of scheduling.
 * Sub-stream positioning is a constant-time jump, so a generous default costs nothing.
 * A member consumes roughly (its evaluations times its replications per evaluation)
 * sub-streams; the default of one million comfortably exceeds realistic runs, and the
 * pooled evaluator factory logs a warning if a member overruns its block.
 * @param confirmation when non-null, a confirmation stage re-evaluates the top member
 * solutions under common random numbers after all members complete; see
 * [ConfirmationOptions]. When null (the default) the winner is picked by point estimate.
 */
data class ConcurrentRunOptions(
    val numWorkers: Int? = null,
    val substreamBlockSize: Int = DEFAULT_SUBSTREAM_BLOCK_SIZE,
    val confirmation: ConfirmationOptions? = null
) {
    init {
        require(numWorkers == null || numWorkers > 0) {
            "numWorkers must be > 0 when specified; was $numWorkers"
        }
        require(substreamBlockSize > 0) { "substreamBlockSize must be > 0" }
    }

    companion object {
        /** The default sub-stream tape block reserved per member (one million sub-streams). */
        const val DEFAULT_SUBSTREAM_BLOCK_SIZE: Int = 1_000_000
    }
}
