package ksl.simopt.solvers.algorithms.isc

import ksl.simopt.evaluator.Solution
import ksl.utilities.random.rng.RNStreamIfc

/**
 *  Linear-ranking selection-probability assignment for the ISC global phase (§A.5). Members are
 *  ranked best-first by shared fitness; rank `i` (1 = best, `N` = worst) receives the Baker linear
 *  rank probability `p_i = (1/N)(η − 2(η−1)(i−1)/(N−1))`, where the selection pressure `η ∈ [1,2]` is
 *  the expected number of offspring of the best individual. These probabilities are then made
 *  **group-average**: every member of a noise-aware [FitnessGroup] receives the average rank
 *  probability of that group, so statistically indistinguishable members are selected with equal
 *  probability. The probabilities sum to one.
 *
 *  @param eta the selection pressure `η`; must be in [1,2]
 */
class LinearRankingSelection(
    var eta: Double = DEFAULT_ETA
) {

    init {
        require(eta in 1.0..2.0) { "eta must be in [1,2]" }
    }

    /**
     *  Returns each solution paired with its group-averaged linear-rank selection probability, given
     *  the noise-aware [groups] (best group first). The returned probabilities sum to one.
     */
    fun selectionProbabilities(groups: List<FitnessGroup>): List<Pair<Solution, Double>> {
        val ordered = groups.flatMap { it.members }
        val n = ordered.size
        if (n == 0) return emptyList()
        if (n == 1) return listOf(ordered.first().solution to 1.0)
        val rankProb = DoubleArray(n) { i ->
            (1.0 / n) * (eta - 2.0 * (eta - 1.0) * i / (n - 1.0))
        }
        val result = ArrayList<Pair<Solution, Double>>(n)
        var index = 0
        for (group in groups) {
            val size = group.members.size
            var sum = 0.0
            for (k in 0 until size) sum += rankProb[index + k]
            val avg = sum / size
            for (member in group.members) result.add(member.solution to avg)
            index += size
        }
        return result
    }

    companion object {
        /** Default selection pressure `η`. */
        const val DEFAULT_ETA: Double = 1.5
    }
}

/**
 *  Stochastic Universal Sampling (§A.5): draws individuals proportionally to their selection
 *  probabilities using a single random start and `n` equally-spaced pointers, giving lower-variance
 *  sampling than independent roulette spins. A run is reproducible for a fixed random stream.
 */
class StochasticUniversalSampling {

    /**
     *  Samples [n] individuals from the (item, probability) pairs in [weighted] using SUS driven by
     *  [rnStream]. Probabilities need not sum to one; they are normalized internally.
     */
    fun sample(
        weighted: List<Pair<Solution, Double>>,
        n: Int,
        rnStream: RNStreamIfc
    ): List<Solution> {
        require(n >= 0) { "the number to sample must be >= 0" }
        if (n == 0 || weighted.isEmpty()) return emptyList()
        val total = weighted.sumOf { it.second.coerceAtLeast(0.0) }
        if (total <= 0.0) {
            // Degenerate weights: sample uniformly with replacement.
            return List(n) { weighted[rnStream.randInt(0, weighted.size - 1)].first }
        }
        val step = total / n
        val start = rnStream.randU01() * step
        val result = ArrayList<Solution>(n)
        var cumulative = 0.0
        var i = 0
        for (pointerIndex in 0 until n) {
            val pointer = start + pointerIndex * step
            while (i < weighted.size - 1 && cumulative + weighted[i].second.coerceAtLeast(0.0) < pointer) {
                cumulative += weighted[i].second.coerceAtLeast(0.0)
                i++
            }
            result.add(weighted[i].first)
        }
        return result
    }
}
