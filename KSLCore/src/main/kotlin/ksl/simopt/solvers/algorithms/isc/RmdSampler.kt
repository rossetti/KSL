package ksl.simopt.solvers.algorithms.isc

import ksl.simopt.problem.ProblemDefinition
import ksl.utilities.random.rng.RNStreamIfc
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 *  A coordinate-direction (RMD — random multidimensional) sampler that draws points approximately
 *  uniformly from a [MostPromisingArea]. Starting from a point known to lie in the MPA, the sampler
 *  repeatedly picks a random coordinate and resamples that coordinate from its feasible interval,
 *  holding the others fixed — a Gibbs-style walk over the polytope. After a warm-up of such moves the
 *  current point is returned.
 *
 *  The feasible interval for the chosen axis is the intersection of:
 *  - the box bounds `[lower_j, upper_j]` from the problem, and
 *  - the per-axis bound implied by every half-space `a · x <= b` in the MPA (the original linear
 *    constraints and the active halfway hyperplanes), holding the other coordinates fixed.
 *
 *  When the input has positive granularity the axis is resampled on its integer grid
 *  `lower_j + m * g_j`; with zero granularity it is resampled continuously. After each proposed move
 *  the full point is checked with [ProblemDefinition.isInputFeasible] so that functional constraints
 *  not expressible as half-spaces are respected: an infeasible proposal is rejected and the walk
 *  *stays* at the current value for that step. A run is reproducible for a fixed [rnStream].
 *
 *  @param problemDefinition the problem supplying box bounds, granularities, and the feasibility test
 *  @param rnStream the random stream driving axis selection and resampling
 *  @param defaultWarmUp the default number of coordinate moves per [sample] call
 */
class RmdSampler(
    val problemDefinition: ProblemDefinition,
    val rnStream: RNStreamIfc,
    val defaultWarmUp: Int = DEFAULT_WARM_UP
) {

    init {
        require(defaultWarmUp >= 1) { "defaultWarmUp must be at least 1" }
    }

    private val lower: DoubleArray = problemDefinition.inputLowerBounds
    private val upper: DoubleArray = problemDefinition.inputUpperBounds
    private val granularity: DoubleArray = problemDefinition.inputGranularities
    private val dimension: Int = problemDefinition.inputSize

    /**
     *  Draws a point from [mpa] by running a coordinate-direction walk for [warmUp] moves beginning at
     *  [start]. [start] must already lie in the MPA; the returned point also lies in the MPA. The
     *  walk uses [halfSpaces] for the per-axis interval — pass the MPA's active half-spaces to skip
     *  redundant ones, or [MostPromisingArea.allHalfSpaces] for the unpruned set.
     *
     *  @param mpa the most-promising area to sample within
     *  @param start the starting point (in the problem's input order); copied, not mutated
     *  @param halfSpaces the half-spaces defining the MPA used for per-axis bounds; defaults to all
     *  @param warmUp the number of coordinate moves to perform; defaults to [defaultWarmUp]
     */
    fun sample(
        mpa: MostPromisingArea,
        start: DoubleArray,
        halfSpaces: List<HalfSpace> = mpa.allHalfSpaces,
        warmUp: Int = defaultWarmUp
    ): DoubleArray {
        require(start.size == dimension) { "start point dimension ${start.size} != problem input size $dimension" }
        val x = start.copyOf()
        repeat(warmUp) {
            val j = rnStream.randInt(0, dimension - 1)
            val (lo, hi) = axisInterval(x, j, halfSpaces)
            if (hi < lo) return@repeat // empty interval: stay
            val candidate = resampleAxis(j, lo, hi)
            val previous = x[j]
            x[j] = candidate
            if (!problemDefinition.isInputFeasible(x)) {
                x[j] = previous // reject functionally-infeasible proposal: stay
            }
        }
        return x
    }

    /**
     *  The feasible closed interval `[lo, hi]` for coordinate [j] at point [x], intersecting the box
     *  bounds with every half-space bound implied along axis [j] while the other coordinates are held
     *  fixed. An empty interval is signalled by `hi < lo`.
     */
    internal fun axisInterval(x: DoubleArray, j: Int, halfSpaces: List<HalfSpace>): Pair<Double, Double> {
        var lo = lower[j]
        var hi = upper[j]
        for (h in halfSpaces) {
            val aj = h.a[j]
            if (aj == 0.0) continue
            // a_j * x_j <= b - sum_{k != j} a_k x_k
            var rest = 0.0
            for (k in h.a.indices) if (k != j) rest += h.a[k] * x[k]
            val bound = (h.b - rest) / aj
            if (aj > 0.0) hi = min(hi, bound) else lo = max(lo, bound)
        }
        return lo to hi
    }

    /**
     *  Resamples coordinate [j] uniformly within `[lo, hi]`. With positive granularity the value is
     *  drawn from the integer grid `lower_j + m * g_j` restricted to the interval; with zero
     *  granularity it is drawn continuously.
     */
    private fun resampleAxis(j: Int, lo: Double, hi: Double): Double {
        val g = granularity[j]
        if (g <= 0.0) {
            return lo + rnStream.randU01() * (hi - lo)
        }
        val base = lower[j]
        val mLo = ceil((lo - base) / g - GRID_TOL).toLong()
        val mHi = floor((hi - base) / g + GRID_TOL).toLong()
        if (mHi < mLo) {
            // No grid point in the interval; snap to the nearest grid point clamped to the interval.
            val snapped = base + Math.round((lo - base) / g) * g
            return min(hi, max(lo, snapped))
        }
        val m = mLo + (rnStream.randU01() * (mHi - mLo + 1)).toLong().coerceAtMost(mHi - mLo)
        val value = base + m * g
        return min(upper[j], max(lower[j], value))
    }

    companion object {
        /** Default number of coordinate-direction moves per draw (RMD warm-up). */
        const val DEFAULT_WARM_UP: Int = 20

        /** Relative slack used when snapping interval endpoints to the integer grid. */
        private const val GRID_TOL: Double = 1.0e-9
    }
}
