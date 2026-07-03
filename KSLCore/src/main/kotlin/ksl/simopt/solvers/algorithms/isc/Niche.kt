package ksl.simopt.solvers.algorithms.isc

import ksl.simopt.evaluator.Solution
import kotlin.math.sqrt

/**
 *  The Euclidean distance between two points expressed in the problem's input order.
 */
internal fun euclideanDistance(a: DoubleArray, b: DoubleArray): Double {
    var s = 0.0
    for (i in a.indices) {
        val d = a[i] - b[i]
        s += d * d
    }
    return sqrt(s)
}

/**
 *  A niche discovered by the Industrial Strength COMPASS global phase: a center "seed" solution and
 *  the population members clustered around it (within the niche radius). The center is the best
 *  solution in the niche and is used as the start for a COMPASS local search.
 *
 *  @param center the niche center (best solution in the niche)
 *  @param members the population members assigned to this niche, including the center
 */
data class Niche(
    val center: Solution,
    val members: List<Solution>
) {
    /** The number of population members assigned to this niche (the niche size `m`). */
    val size: Int get() = members.size
}

/**
 *  The result of niche identification (ISC Algorithm 2): the identified niches, the niche radius
 *  `r`, and the niche count `q = |L|`.
 *
 *  @param niches the identified niches (each with a center and its surrounding members)
 *  @param radius the niche radius `r` (half the minimum pairwise distance among centers)
 *  @param count the number of niches `q`
 */
data class NicheResult(
    val niches: List<Niche>,
    val radius: Double,
    val count: Int
)
