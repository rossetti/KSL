package ksl.simopt.solvers.algorithms.isc

import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.ProblemDefinition

/**
 *  Niche identification for the ISC global phase (Algorithm 2). Operating over the **population**
 *  (not all of the feasible region), it finds the *niche centers* `L`: population members that are at
 *  least as good as every one of their *active neighbors*. A member `j` is an active neighbor of `i`
 *  when the COMPASS halfway hyperplane separating `i` from `j` actively bounds `i`'s most-promising
 *  area — i.e. it is **not** redundant given `i`'s other halfway hyperplanes and the problem's
 *  original linear constraints. This is exactly the active-set structure the COMPASS pruner uses, so
 *  it is reused here via the same [RedundantConstraintChecker].
 *
 *  From the centers it derives the niche radius `r = ½ · min‖x_i − x_j‖` over the centers and the
 *  niche count `q = |L|`, then assigns each population member to the nearest center within `r`.
 *
 *  Because identification runs over the population (size `m_G`), the brute-force redundancy checker is
 *  tractable; the cost is `O(m_G²)` redundancy tests, each over `O(m_G)` half-spaces.
 *
 *  @param checker the redundancy strategy used to decide which neighbors are active
 */
class NicheIdentifier(
    val checker: RedundantConstraintChecker = BruteForceRedundancyChecker()
) {

    /**
     *  Identifies the niches in [population] for [problemDefinition]. [compareSolutions] orders
     *  solutions best-first (negative when the first argument is better), matching the solver's
     *  [ksl.simopt.solvers.Solver.compare].
     */
    fun identify(
        population: List<Solution>,
        problemDefinition: ProblemDefinition,
        compareSolutions: (Solution, Solution) -> Int
    ): NicheResult {
        if (population.isEmpty()) return NicheResult(emptyList(), 0.0, 0)
        if (population.size == 1) {
            val only = population.first()
            return NicheResult(listOf(Niche(only, listOf(only))), 0.0, 1)
        }
        val points = population.map { it.inputMap.inputValues }
        val originalHalfSpaces = problemDefinition.linearConstraints
            .map { HalfSpace.fromLinearConstraint(it, problemDefinition.inputNames) }

        val isCenter = BooleanArray(population.size)
        for (i in population.indices) {
            isCenter[i] = beatsAllActiveNeighbors(i, population, points, originalHalfSpaces, compareSolutions)
        }
        val centerIndices = population.indices.filter { isCenter[it] }
        val centers = centerIndices.map { population[it] }

        val radius = nicheRadius(centerIndices.map { points[it] })
        val niches = assignMembersToNiches(centers, population, points, radius, compareSolutions)
        return NicheResult(niches, radius, centers.size)
    }

    /**
     *  True if member [i] is at least as good as every active neighbor. The active neighbors are the
     *  members whose halfway hyperplane (relative to `i`) is non-redundant given the original
     *  constraints and the other members' halfway hyperplanes.
     */
    private fun beatsAllActiveNeighbors(
        i: Int,
        population: List<Solution>,
        points: List<DoubleArray>,
        originalHalfSpaces: List<HalfSpace>,
        compareSolutions: (Solution, Solution) -> Int
    ): Boolean {
        val center = points[i]
        // Build the halfway half-space for each distinct other member, tracking its member index.
        val neighborIndices = ArrayList<Int>()
        val halfSpaces = ArrayList<HalfSpace>()
        var centerSq = 0.0
        for (c in center) centerSq += c * c
        for (j in population.indices) {
            if (j == i) continue
            val y = points[j]
            if (y.contentEquals(center)) continue // duplicate point contributes no separating plane
            val a = DoubleArray(center.size) { y[it] - center[it] }
            var ySq = 0.0
            for (v in y) ySq += v * v
            val b = (ySq - centerSq) / 2.0
            neighborIndices.add(j)
            halfSpaces.add(HalfSpace(a, b))
        }
        if (halfSpaces.isEmpty()) return true
        for (k in halfSpaces.indices) {
            val others = ArrayList<HalfSpace>(originalHalfSpaces.size + halfSpaces.size - 1)
            others.addAll(originalHalfSpaces)
            for (m in halfSpaces.indices) if (m != k) others.add(halfSpaces[m])
            val isActive = !checker.isRedundant(halfSpaces[k], others)
            if (isActive) {
                val j = neighborIndices[k]
                // i must be at least as good as this active neighbor to be a center.
                if (compareSolutions(population[i], population[j]) > 0) return false
            }
        }
        return true
    }

    private fun nicheRadius(centers: List<DoubleArray>): Double {
        if (centers.size < 2) return 0.0
        var minDist = Double.POSITIVE_INFINITY
        for (a in centers.indices) {
            for (b in a + 1 until centers.size) {
                val d = euclideanDistance(centers[a], centers[b])
                if (d < minDist) minDist = d
            }
        }
        return if (minDist.isFinite()) 0.5 * minDist else 0.0
    }

    private fun assignMembersToNiches(
        centers: List<Solution>,
        population: List<Solution>,
        points: List<DoubleArray>,
        radius: Double,
        compareSolutions: (Solution, Solution) -> Int
    ): List<Niche> {
        if (centers.isEmpty()) return emptyList()
        val centerPoints = centers.map { it.inputMap.inputValues }
        val buckets = Array(centers.size) { ArrayList<Solution>() }
        for (idx in population.indices) {
            // Find nearest center; assign if within the radius (or if radius is 0, assign to nearest).
            var best = -1
            var bestDist = Double.POSITIVE_INFINITY
            for (c in centers.indices) {
                val d = euclideanDistance(points[idx], centerPoints[c])
                if (d < bestDist) {
                    bestDist = d
                    best = c
                }
            }
            if (best >= 0 && (radius <= 0.0 || bestDist <= radius || centers.size == 1)) {
                buckets[best].add(population[idx])
            }
        }
        return centers.indices.map { c ->
            val members = buckets[c].ifEmpty { listOf(centers[c]) }
                .sortedWith { a, b -> compareSolutions(a, b) }
            Niche(members.first(), members)
        }
    }
}
