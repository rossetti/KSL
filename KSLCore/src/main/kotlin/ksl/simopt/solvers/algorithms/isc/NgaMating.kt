package ksl.simopt.solvers.algorithms.isc

import ksl.simopt.evaluator.Solution
import ksl.utilities.random.rng.RNStreamIfc

/**
 *  Mating-restriction strategy for the ISC global phase: given an individual chosen to reproduce,
 *  pick a partner. Restricting mates to the same niche preserves the niche structure during
 *  recombination.
 */
fun interface MatingRestrictionIfc {

    /**
     *  Selects a mate for [individual] from [pool], possibly using the [niches] structure.
     *
     *  @param individual the individual seeking a mate
     *  @param pool the candidate partners (typically the selected parent pool)
     *  @param niches the current niche structure
     *  @param compareSolutions best-first comparison (negative when the first is better)
     *  @param rnStream the random stream
     */
    fun selectMate(
        individual: Solution,
        pool: List<Solution>,
        niches: NicheResult,
        compareSolutions: (Solution, Solution) -> Int,
        rnStream: RNStreamIfc
    ): Solution
}

/**
 *  Dynamic-inbreeding mating (Algorithm 4, §A.7): sample [m] candidate partners from the pool and
 *  prefer the best candidate drawn from the **same niche** as the individual; if none of the sampled
 *  candidates share the individual's niche, fall back to the geometrically **closest** candidate.
 *  This keeps recombination local to a niche while still allowing occasional cross-niche mating.
 *
 *  @param m the number of candidate partners sampled per mating; must be at least 1
 */
class DynamicInbreeding(
    var m: Int = DEFAULT_M
) : MatingRestrictionIfc {

    init {
        require(m >= 1) { "m must be at least 1" }
    }

    override fun selectMate(
        individual: Solution,
        pool: List<Solution>,
        niches: NicheResult,
        compareSolutions: (Solution, Solution) -> Int,
        rnStream: RNStreamIfc
    ): Solution {
        require(pool.isNotEmpty()) { "the mating pool must not be empty" }
        val candidates = List(m) { pool[rnStream.randInt(0, pool.size - 1)] }
        val nicheMembers = nicheMembersOf(individual, niches)
        val sameNiche = candidates.filter { it.inputMap in nicheMembers && it.inputMap != individual.inputMap }
        if (sameNiche.isNotEmpty()) {
            return sameNiche.minWithOrNull { a, b -> compareSolutions(a, b) }!!
        }
        val center = individual.inputMap.inputValues
        return candidates.minByOrNull { euclideanDistance(center, it.inputMap.inputValues) }!!
    }

    private fun nicheMembersOf(individual: Solution, niches: NicheResult): Set<Any> {
        for (niche in niches.niches) {
            if (niche.members.any { it.inputMap == individual.inputMap }) {
                return niche.members.map { it.inputMap as Any }.toSet()
            }
        }
        return emptySet()
    }

    companion object {
        /** Default number of sampled candidate partners. */
        const val DEFAULT_M: Int = 10
    }
}
