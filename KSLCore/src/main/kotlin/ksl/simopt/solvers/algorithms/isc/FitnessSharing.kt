package ksl.simopt.solvers.algorithms.isc

import ksl.simopt.evaluator.Solution
import ksl.simopt.evaluator.SolutionScorer

/**
 *  A population member's shared (niche-discounted) fitness.
 *
 *  @param solution the population member
 *  @param sharedFitness the fitness after niche sharing (smaller is better, as for the penalized
 *  objective); crowded niches are penalized
 *  @param sharedVariance the member's objective-estimate variance scaled by the same niche size
 *  @param nicheSize the size `m` of the niche (or non-niched group) the member belongs to
 */
data class SharedFitness(
    val solution: Solution,
    val sharedFitness: Double,
    val sharedVariance: Double,
    val nicheSize: Int
)

/**
 *  Fitness sharing for the ISC global phase (§A.4). The raw fitness `f` (the penalized objective,
 *  minimized) is discounted by the niche size `m` so that members in crowded niches become less
 *  attractive: `f_sh = f / m` when `f < 0` and `f_sh = f · m` when `f ≥ 0` (both move `f` toward a
 *  *worse* — larger — value as `m` grows). The objective-estimate variance is scaled by the same `m`.
 *  Members not assigned to any niche form a single non-niched group whose size plays the role of `m`
 *  (triangular sharing).
 */
class FitnessSharing {

    /**
     *  Computes the shared fitness of every member of [population] given the identified [niches].
     */
    fun share(
        population: List<Solution>,
        niches: NicheResult,
        scorer: SolutionScorer
    ): List<SharedFitness> {
        // Map each niche member to its niche size.
        val sizeByInput = HashMap<Any, Int>()
        for (niche in niches.niches) {
            for (member in niche.members) {
                sizeByInput[member.inputMap] = niche.size
            }
        }
        val nonNiched = population.filter { it.inputMap !in sizeByInput }
        val nonNichedSize = nonNiched.size.coerceAtLeast(1)
        return population.map { s ->
            val m = sizeByInput[s.inputMap] ?: nonNichedSize
            val f = scorer.score(s)
            val shared = if (f < 0.0) f / m else f * m
            val variance = scaledVariance(s, m)
            SharedFitness(s, shared, variance, m)
        }
    }

    private fun scaledVariance(s: Solution, m: Int): Double {
        val v = s.estimatedObjFnc.variance
        val base = if (v.isNaN() || v <= 0.0) 0.0 else v
        return base * m
    }
}
