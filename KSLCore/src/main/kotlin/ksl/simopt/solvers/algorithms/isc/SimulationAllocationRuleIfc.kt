package ksl.simopt.solvers.algorithms.isc

import ksl.simopt.evaluator.Solution
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 *  Strategy for deciding how many *additional* simulation replications a solution should receive at a
 *  given COMPASS iteration. ISC increases each surviving point's simulation effort as the search
 *  proceeds so that estimation noise shrinks fast enough to guarantee convergence. A rule maps the
 *  current iteration count and a solution's accumulated replications to the number of extra
 *  replications to request now (never negative).
 */
fun interface SimulationAllocationRuleIfc {

    /**
     *  The number of additional replications to allocate to [solution] at iteration [iteration].
     *
     *  @param solution the solution whose effort is being topped up; its current replication count is
     *  read from [Solution.count]
     *  @param iteration the (1-based) COMPASS iteration number
     *  @return a non-negative count of additional replications
     */
    fun additionalReplications(solution: Solution, iteration: Int): Int
}

/**
 *  The deterministic ISC allocation schedule: the *target* number of replications for a solution at
 *  iteration `k` grows as `max(n0, ceil(n0 * (ln k)^(1 + epsilon)))`, and the rule returns the
 *  shortfall between that target and the replications the solution already has. The
 *  slightly-super-logarithmic growth (`epsilon > 0`) matches the COMPASS sample-size schedule that
 *  drives simulation error to zero while keeping total effort modest.
 *
 *  @param initialReplications the floor `n0` on replications per solution (also the target for the
 *  first iterations); defaults to [DEFAULT_INITIAL_REPLICATIONS]
 *  @param epsilon the positive exponent offset controlling growth rate; defaults to [DEFAULT_EPSILON]
 */
class FixedScheduleSAR(
    val initialReplications: Int = DEFAULT_INITIAL_REPLICATIONS,
    val epsilon: Double = DEFAULT_EPSILON
) : SimulationAllocationRuleIfc {

    init {
        require(initialReplications >= 1) { "initialReplications must be at least 1" }
        require(epsilon > 0.0) { "epsilon must be positive" }
    }

    /** The target replication count for a solution at the given (1-based) [iteration]. */
    fun targetReplications(iteration: Int): Int {
        require(iteration >= 1) { "iteration must be at least 1" }
        val lnK = ln(iteration.toDouble())
        if (lnK <= 0.0) return initialReplications // iterations 1 (ln 1 = 0)
        val grown = ceil(initialReplications * lnK.pow(1.0 + epsilon)).toInt()
        return max(initialReplications, grown)
    }

    override fun additionalReplications(solution: Solution, iteration: Int): Int {
        val target = targetReplications(iteration)
        val have = solution.count.toInt()
        return max(0, target - have)
    }

    companion object {
        /** Default replication floor `n0`. */
        const val DEFAULT_INITIAL_REPLICATIONS: Int = 5

        /** Default growth exponent offset. */
        const val DEFAULT_EPSILON: Double = 0.01
    }
}
