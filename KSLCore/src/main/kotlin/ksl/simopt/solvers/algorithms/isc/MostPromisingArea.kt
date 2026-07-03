package ksl.simopt.solvers.algorithms.isc

import ksl.simopt.problem.ProblemDefinition

/**
 *  The COMPASS *most-promising-area* (MPA) around a current best (center) point `x*`. The MPA is the
 *  set of feasible points that are at least as close to `x*` as to every other point visited so far.
 *  Geometrically it is the intersection of:
 *
 *  1. the original problem's linear constraints (carried as [originalHalfSpaces]), and
 *  2. one *halfway hyperplane* per visited point `y != x*`: the half-space of points no farther from
 *     `x*` than from `y`. For `y` this half-space is `a · x <= b` with `a = y - x*` and
 *     `b = (|y|^2 - |x*|^2) / 2`, which contains `x*`.
 *
 *  A point lies in the MPA when it satisfies every halfway hyperplane **and** is input-feasible for
 *  the problem (box bounds, integer granularity, linear and functional constraints). The RMD sampler
 *  walks within this region; the redundancy checker can prune halfway hyperplanes that never bind.
 *
 *  @param problemDefinition the problem whose feasible region the MPA refines
 *  @param center the current best point `x*`, in the problem's input order
 *  @param visited the set of previously visited points (each in the problem's input order); the
 *  [center] itself, if present, contributes no constraint and is ignored
 */
class MostPromisingArea(
    val problemDefinition: ProblemDefinition,
    val center: DoubleArray,
    val visited: List<DoubleArray>
) {

    /** The original problem's linear constraints expressed uniformly as `a · x <= b` half-spaces. */
    val originalHalfSpaces: List<HalfSpace> =
        problemDefinition.linearConstraints.map { HalfSpace.fromLinearConstraint(it, problemDefinition.inputNames) }

    /** One halfway hyperplane per distinct visited point, each oriented to contain [center]. */
    val halfwayHalfSpaces: List<HalfSpace> = buildHalfwayHalfSpaces()

    private fun buildHalfwayHalfSpaces(): List<HalfSpace> {
        val result = ArrayList<HalfSpace>(visited.size)
        var centerSq = 0.0
        for (c in center) centerSq += c * c
        for (y in visited) {
            if (isCenter(y)) continue
            val a = DoubleArray(center.size) { y[it] - center[it] }
            var ySq = 0.0
            for (v in y) ySq += v * v
            val b = (ySq - centerSq) / 2.0
            result.add(HalfSpace(a, b))
        }
        return result
    }

    private fun isCenter(y: DoubleArray): Boolean {
        if (y.size != center.size) return false
        for (i in y.indices) if (y[i] != center[i]) return false
        return true
    }

    /** All half-spaces that define the MPA: the original constraints plus the halfway hyperplanes. */
    val allHalfSpaces: List<HalfSpace>
        get() = originalHalfSpaces + halfwayHalfSpaces

    /**
     *  True if [x] lies in the most-promising area: it satisfies every halfway hyperplane and is
     *  input-feasible for the problem (which already enforces the box bounds and original linear and
     *  functional constraints).
     */
    fun contains(x: DoubleArray, tol: Double = HalfSpace.defaultTolerance): Boolean {
        for (h in halfwayHalfSpaces) if (!h.isSatisfied(x, tol)) return false
        return problemDefinition.isInputFeasible(x)
    }

    /**
     *  Returns the halfway hyperplanes that are *not* redundant given the problem's original
     *  half-spaces together with the remaining halfway hyperplanes — i.e. the ones that can actually
     *  bind on the MPA. Used to keep the RMD sampler's per-axis interval computation lean.
     *
     *  @param checker the redundancy strategy to apply
     */
    fun activeHalfwayHalfSpaces(checker: RedundantConstraintChecker): List<HalfSpace> {
        if (halfwayHalfSpaces.isEmpty()) return emptyList()
        val active = ArrayList<HalfSpace>(halfwayHalfSpaces.size)
        for (i in halfwayHalfSpaces.indices) {
            val target = halfwayHalfSpaces[i]
            val others = ArrayList<HalfSpace>(originalHalfSpaces.size + halfwayHalfSpaces.size - 1)
            others.addAll(originalHalfSpaces)
            for (k in halfwayHalfSpaces.indices) if (k != i) others.add(halfwayHalfSpaces[k])
            if (!checker.isRedundant(target, others)) active.add(target)
        }
        return active
    }
}
