package ksl.simopt.problem

import ksl.simopt.evaluator.EstimatedResponse
import ksl.simopt.evaluator.Solution
import kotlin.math.sqrt

/**
 *  Park and Kim (2015) Penalty-Function-with-Memory state for one (design point, constraint): the
 *  number of visits, the cumulative standardized measure of violation (the sum of the per-visit
 *  measures), and the current penalty-sequence value. The snapshot is immutable; a new one is
 *  produced at each visit and carried on the [Solution], so it persists across visits via the
 *  solution cache.
 *
 *  @param visitCount the number of visits accumulated so far
 *  @param cumulativeZeta the running sum of the per-visit standardized measures (Park and Kim 2015, Section 3.1)
 *  @param lambda the current penalty-sequence value for this solution and constraint
 */
class ParkKimMemory(
    val visitCount: Int,
    val cumulativeZeta: Double,
    val lambda: Double
) : PenaltyMemory {

    /**
     *  The sample-mean standardized measure of violation S = (1 / visitCount) * cumulativeZeta
     *  (Park and Kim 2015, Section 3.1); 0 when there have been no visits. A positive value indicates
     *  apparent infeasibility, magnified as more observations accumulate.
     */
    val standardizedMeasure: Double
        get() = if (visitCount == 0) 0.0 else cumulativeZeta / visitCount
}

/**
 *  The Park and Kim (2015) Penalty Function with Memory (PFM) for a stochastic (response) constraint.
 *
 *  Unlike the memoryless [DynamicPolynomialPenalty], PFM accumulates a per-solution memory across
 *  visits ([ParkKimMemory]): a standardized measure of violation S that magnifies infeasibility as
 *  observations accumulate (Park and Kim 2015, Section 3.1), and a penalty sequence that appreciates
 *  for solutions that look infeasible and depreciates for those that look feasible (the [sequence]).
 *  The ranking-time contribution is lambda * [S]+ (their Section 3.1). The memory is folded at
 *  evaluation time by the evaluator via [foldVisit] and carried on the [Solution]; [penalty] only
 *  reads it, so the penalized objective stays a pure function of the solution.
 *
 *  Scope: this uses Park and Kim's foundational appreciation/depreciation sequence (their Eq. 4; see
 *  [AppreciateDepreciateSequence]). Their convergence-optimized sequences PS1 (Figure 3) and PS2
 *  (Section 3.3) are NOT implemented here — they require global search state (chiefly the adaptive
 *  cap M0 of their Eq. 5) and drop into [sequence] without engine changes. Citation note: the
 *  standardized measure, its visit-mean S, and the penalized objective are the inline definitions of
 *  Park and Kim's Section 3.1 (the same quantities are numbered Eq. 2-3 in the Han et al. 2021
 *  improved-PFM paper, whose numbering earlier drafts of this code used).
 *
 *  Graceful degradation (no growing-replication schedule is required): with fewer than two accumulated
 *  visits the memory is a single noisy measure, so [penalty] uses [fallback] (a memoryless polynomial
 *  penalty). PFM engages once a design point has been re-sampled (two or more visits). If the
 *  evaluation regime never re-samples a design point, PFM therefore behaves exactly as [fallback].
 *
 *  PFM is meant for response (stochastic) constraints, whose observations can be standardized. If it
 *  is bound to a deterministic constraint (which produces no observations), no memory ever
 *  accumulates and [penalty] simply uses [fallback].
 *
 *  @param sequence the penalty-sequence rule (Park and Kim Eq. 4 appreciation/depreciation, or a
 *   future sequence such as PS2+)
 *  @param fallback the memoryless penalty used until memory accumulates, and whenever memory is absent
 *  @param constraint the bound constraint, or null for an unbound default template
 */
class ParkKimPenalty(
    val sequence: PenaltySequence,
    val fallback: PenaltyFunction = DynamicPolynomialPenalty(),
    constraint: PenalizableConstraint? = null,
) : PenaltyFunction(constraint) {

    override val usesMemory: Boolean
        get() = true

    override fun boundTo(c: PenalizableConstraint): PenaltyFunction =
        ParkKimPenalty(sequence, fallback.boundTo(c), c)

    override fun foldVisit(
        newObservations: EstimatedResponse,
        prior: PenaltyMemory?,
        iteration: Int
    ): PenaltyMemory {
        val prev = (prior as? ParkKimMemory) ?: ParkKimMemory(0, 0.0, sequence.initialLambda)
        // The standardized measure needs a stochastic constraint's direction-adjusted gap. If this
        // penalty is (mis)bound to a deterministic constraint there are no observations to
        // standardize, so carry the prior memory forward unchanged.
        val rc = constraint as? ResponseConstraint ?: return prev
        val deltaN = newObservations.count.toInt()
        // A visit with no new observations is not a visit: deltaN = 0 gives a 0 measure (the
        // sampled-but-not-simulated generalization is Han et al. 2021, improved PFM).
        if (deltaN <= 0) return prev
        // zeta = sqrt(deltaN) * (Hbar_new - q), direction-adjusted so a positive value means infeasible
        // (Park and Kim 2015, Section 3.1). difference() supplies the direction-adjusted gap.
        val zeta = sqrt(deltaN.toDouble()) * rc.difference(newObservations.average)
        val visits = prev.visitCount + 1
        val cumulative = prev.cumulativeZeta + zeta
        val s = cumulative / visits // S (Section 3.1)
        val lambda = sequence.update(prev.lambda, s, visits, iteration)
        return ParkKimMemory(visits, cumulative, lambda)
    }

    override fun penalty(solution: Solution): Double {
        val memory = solution.penaltyMemory[constraint!!.key] as? ParkKimMemory
        // Graceful degradation: with no accumulated memory (zero or one visit) the standardized
        // measure is too noisy to trust, so use the memoryless fallback.
        if (memory == null || memory.visitCount <= 1) return fallback.penalty(solution)
        val s = memory.standardizedMeasure
        if (s <= 0.0) return 0.0 // [S]+ = 0 means the solution looks feasible on average: no penalty
        return minOf(memory.lambda * s, Double.MAX_VALUE) // lambda * [S]+ (Section 3.1)
    }
}
