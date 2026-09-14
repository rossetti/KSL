package ksl.simopt.solvers.concurrent

import ksl.simopt.evaluator.Solution

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
    val replicationsPerCandidate: Int = 50,
    /**
     * The overall confidence used when testing response-constraint feasibility during selection,
     * matching `Solver.recommendationCILevel` so that confirmation and the solvers agree about
     * which solutions count as feasible.
     */
    val recommendationCILevel: Double = 0.99,
    /**
     * The rule used to rank candidates and to pick the winner. Null (the default) uses
     * `FeasibilityFirstComparator` at [recommendationCILevel], which is what the solvers themselves
     * use, so confirmation and search agree about what "best" means.
     *
     * Supplied so that selection policy can be treated as a study-level variable — a benchmark can
     * run the same search under two rules and report the difference — rather than something only a
     * library change can vary.
     */
    val selectionComparator: Comparator<Solution>? = null,
    /**
     * How many candidates to carry into a screening stage before the finalists are chosen. Null
     * (the default) disables screening entirely and preserves the single-stage behaviour.
     *
     * Must be at least [topK], and is only useful well above it: the point is to let the screening
     * stage, not the search, decide which candidates reach confirmation.
     */
    val screenK: Int? = null,
    /**
     * The replications per candidate used by the screening stage. Null (the default) disables
     * screening; it must be supplied exactly when [screenK] is.
     *
     * **Size this against the constraint, not by habit.** Feasibility is tested with a one-sided
     * interval, so a candidate is declared feasible only when its upper limit falls below zero, and
     * at small replication counts that is unreachable for any candidate near the limit. On a
     * `P(event) <= 0.05` constraint at 99% overall confidence, measured against
     * `ResponseConstraint.testFeasibility` itself:
     *
     * <pre>
     *   n=30   only 0 violations out of 30 passes; 1 in 30 (p = 0.033) already fails
     *   n=100  passes up to p = 0.01;  p = 0.02 fails
     *   n=200  passes up to p = 0.02
     * </pre>
     *
     * So screening at 100 replications against a 0.05 limit still rejects every candidate sitting
     * at a realistic 0.02 to 0.04, and the screening stage inherits the degeneracy it was added to
     * cure. Roughly 200 is where discrimination arrives for that constraint. A tighter limit needs
     * more.
     */
    val screeningReplications: Int? = null
) {
    init {
        require(topK >= 1) { "topK must be >= 1" }
        require(replicationsPerCandidate >= 1) { "replicationsPerCandidate must be >= 1" }
        require(recommendationCILevel > 0.0 && recommendationCILevel < 1.0) {
            "recommendationCILevel must be in (0,1)"
        }
        // Half-configured screening would silently not screen, which is the failure this stage
        // exists to remove rather than reproduce.
        require((screenK == null) == (screeningReplications == null)) {
            "screenK and screeningReplications must be supplied together or not at all; got " +
                "screenK=$screenK, screeningReplications=$screeningReplications"
        }
        require(screenK == null || screenK >= topK) {
            "screenK ($screenK) must be >= topK ($topK); screening a pool smaller than the " +
                "finalist count cannot change which candidates are confirmed"
        }
        // A single observation carries no sample variance, so every constraint test returns "not
        // shown feasible" and the screening stage would declare the whole pool infeasible whatever
        // it observed.
        require(screeningReplications == null || screeningReplications >= 2) {
            "screeningReplications ($screeningReplications) must be >= 2; a single replication " +
                "carries no sample variance, so no constraint could be declared feasible"
        }
    }

    /** True when both screening parameters are set, so the screening stage runs. */
    val isScreeningEnabled: Boolean
        get() = screenK != null && screeningReplications != null
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
