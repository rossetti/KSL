package ksl.simopt.benchmark

/**
 *  Whether a problem's reference solution is a proven optimum or just the best point
 *  known so far.
 */
enum class ReferenceType {
    /** The reference is a (mathematically) known optimum — gaps against it are exact. */
    KNOWN_OPTIMUM,

    /** The reference is the best solution known to date (e.g., accumulated from prior
     *  studies of a problem with no closed-form optimum). */
    BEST_KNOWN
}

/**
 *  The basis against which a run's optimality gap was computed. Runs on problems with a
 *  reference solution record the reference's type; runs on problems without one are
 *  gapped against the best objective value found across all runs of that problem within
 *  the experiment.
 */
enum class GapType {
    /** Gap against a known optimum (exact optimality gap). */
    KNOWN_OPTIMUM,

    /** Gap against the best known solution for the problem. */
    BEST_KNOWN,

    /** Gap against the best objective value found across all runs of the problem in this
     *  experiment (relative gap; the best run's gap is zero by construction). */
    BEST_FOUND
}

/**
 *  A reference solution for a benchmark problem: the inputs and objective value that
 *  runs are measured against, and whether that reference is a proven optimum or merely
 *  the best known point.
 *
 *  The objective value is expressed on the problem's natural (model) scale — not
 *  reoriented for minimization — and gap computations orient the difference by the
 *  problem's optimization type.
 *
 *  @param inputs the reference design point, keyed by input name
 *  @param objectiveValue the (true or best-known) objective value at the reference point
 *  @param type whether the reference is a proven optimum or the best known point
 */
data class ReferenceSolution(
    val inputs: Map<String, Double>,
    val objectiveValue: Double,
    val type: ReferenceType
) {
    init {
        require(inputs.isNotEmpty()) { "The reference inputs must not be empty" }
        require(objectiveValue.isFinite()) { "The reference objective value must be finite" }
    }
}
