package ksl.simopt.problem

/**
 *  The penalty-sequence rule for a memoryful penalty (Park and Kim 2015). Given the penalty value
 *  carried from a solution's previous visit and the solution's accumulated standardized measure of
 *  violation, it produces the penalty value for the current visit. Any implementation should satisfy
 *  Park and Kim's Condition 1 (the sequence converges to 0 for a feasible constraint and diverges to
 *  infinity for an infeasible constraint) so the penalized objective converges.
 *
 *  This is the seam that keeps alternative sequences — Park and Kim's fuller PS1/PS2 and the Han,
 *  Kim, and Park (2021) improved sequence PS2+ — from being locked out: a new sequence is a new
 *  implementation of this interface and requires no change to the penalty engine.
 */
interface PenaltySequence {

    /** The initial penalty value used on a solution's first visit; must be positive. */
    val initialLambda: Double

    /**
     *  The penalty value for the current visit.
     *
     *  @param priorLambda the penalty value carried from the previous visit (or [initialLambda] on
     *   the first visit)
     *  @param standardizedMeasure the solution's current sample-mean standardized measure of
     *   violation S (Park and Kim 2015, Section 3.1); positive indicates apparent infeasibility
     *  @param visitCount the number of accumulated visits so far (at least 1)
     *  @param iteration the current evaluation iteration k
     *  @return the updated penalty value
     */
    fun update(priorLambda: Double, standardizedMeasure: Double, visitCount: Int, iteration: Int): Double
}

/**
 *  The foundational appreciation/depreciation penalty sequence of Park and Kim (2015, Eq. 4): the
 *  penalty is multiplied by the appreciation factor [appreciationFactor] (a, greater than 1) when the
 *  solution looks infeasible (standardized measure S greater than 0) and by the depreciation factor
 *  [depreciationFactor] (d, between 0 and 1) when it looks feasible (S at most 0). This makes the
 *  sequence diverge for infeasible solutions and converge to zero for interior feasible solutions,
 *  satisfying Park and Kim's Condition 1 for those cases. For boundary (active) solutions it converges
 *  only in distribution (their Theorem 3), with positive probability of not vanishing — the precise gap
 *  that the convergence-optimized PS1/PS2 close. This class is that foundational sequence, not PS1 or PS2.
 *
 *  Park and Kim's fuller PS1 (their Figure 3: a visit-count-dependent depreciation factor and an
 *  M0 * k^rho appreciation cap) and PS2 (their Section 3.3: infeasibility-probability-adaptive
 *  factors) are faithful refinements that additionally require global search state — chiefly the
 *  adaptive cap M0 (their Eq. 5), which is computed from the sample-best feasible objective across
 *  visited solutions. That global state is the deferred `SearchStateSnapshot` seam; those sequences
 *  drop into [PenaltySequence] once it is populated. This sequence needs only per-solution memory.
 *
 *  @param appreciationFactor a: the growth factor applied when S is greater than 0; must be greater than 1
 *  @param depreciationFactor d: the decay factor applied when S is at most 0; must be in (0, 1)
 *  @param initialLambda the starting penalty value; must be positive and finite
 */
class AppreciateDepreciateSequence(
    val appreciationFactor: Double,
    val depreciationFactor: Double,
    override val initialLambda: Double = 1.0
) : PenaltySequence {

    init {
        require(appreciationFactor > 1.0) {
            "The appreciation factor a must be > 1; was $appreciationFactor"
        }
        require(depreciationFactor > 0.0 && depreciationFactor < 1.0) {
            "The depreciation factor d must be in (0, 1); was $depreciationFactor"
        }
        require(initialLambda > 0.0 && initialLambda.isFinite()) {
            "The initial lambda must be > 0 and finite; was $initialLambda"
        }
    }

    override fun update(
        priorLambda: Double,
        standardizedMeasure: Double,
        visitCount: Int,
        iteration: Int
    ): Double = if (standardizedMeasure > 0.0) {
        minOf(priorLambda * appreciationFactor, Double.MAX_VALUE)
    } else {
        priorLambda * depreciationFactor
    }
}
