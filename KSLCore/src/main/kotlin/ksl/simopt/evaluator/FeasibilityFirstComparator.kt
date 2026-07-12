package ksl.simopt.evaluator

/**
 *  A clock-independent, feasibility-first ordering used to select the recommended solution —
 *  distinct from the penalized-objective ordering ([Solution.compareTo]) used to guide the search
 *  within an iteration.
 *
 *  Solutions are ordered by, in priority:
 *  1. validity — a valid solution ranks ahead of an invalid one;
 *  2. statistical response-constraint feasibility — a solution we are confident is feasible
 *     (see [Solution.isResponseConstraintFeasible]) ranks ahead of one we are not;
 *  3. among feasible solutions, the smaller (orientation-adjusted) estimated objective
 *     ([Solution.estimatedObjFncValue]);
 *  4. among non-feasible solutions, the smaller total response-constraint violation
 *     ([Solution.responseConstraintViolationPenalty]).
 *
 *  Because it never uses the penalized objective — whose growing multiplier is iteration-relative —
 *  the ordering is consistent across solutions discovered at different iterations. This is why it is
 *  the right key for cross-iteration selection (the reported best, the best-solution archive), whereas
 *  the penalized objective is the right key for within-iteration search ranking.
 *
 *  @param overallCILevel the overall confidence used for the feasibility test across all response
 *  constraints. Must be in (0, 1). Default 0.99.
 */
class FeasibilityFirstComparator(
    val overallCILevel: Double = 0.99
) : Comparator<Solution> {
    init {
        require(overallCILevel > 0.0 && overallCILevel < 1.0) { "The overall CI level must be in (0,1)" }
    }

    override fun compare(a: Solution, b: Solution): Int {
        if (a.isValid != b.isValid) return if (a.isValid) -1 else 1
        val af = a.isResponseConstraintFeasible(overallCILevel)
        val bf = b.isResponseConstraintFeasible(overallCILevel)
        if (af != bf) return if (af) -1 else 1
        return if (af) {
            a.estimatedObjFncValue.compareTo(b.estimatedObjFncValue)
        } else {
            a.responseConstraintViolationPenalty.compareTo(b.responseConstraintViolationPenalty)
        }
    }
}
