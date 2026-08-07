package ksl.examples.decision

import ksl.modeling.decision.*
import ksl.modeling.decision.descriptor.DecisionSurfaceDescriptor
import ksl.modeling.decision.descriptor.TerminationSource
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Does the design let a modeler write a value-function policy — enumerate the actions
 * themselves, and supply their own `V̄` — using only the interfaces that exist?
 *
 * This file answers that by doing it, on the (s, S) inventory model of [SsInventory].
 * §4.4.6.4 in the proposal reports what it cost.
 */

/**
 * `E[(y − D)⁺]` for `D ~ Poisson(mu)` — the expected leftover stock when a post-order
 * position of `y` meets demand `D`. Everything else in the value term follows from it,
 * because `(D − y)⁺ − (y − D)⁺ = D − y`, so `E[(D − y)⁺] = mu − y + E[(y − D)⁺]`.
 */
internal fun expectedLeftover(y: Double, mu: Double): Double {
    if (y <= 0.0) return 0.0
    var p = exp(-mu)          // Poisson pmf, built up recursively
    var acc = y * p           // d = 0 term
    var d = 1
    while (d <= y) {
        p *= mu / d
        acc += (y - d) * p
        d++
    }
    return acc
}

/**
 * A value-function policy in Powell's sense: choose the action minimising
 *
 * ```
 *   immediate ordering cost  +  V̄(post-decision state)
 * ```
 *
 * over the feasible actions. Three parts, and each tests something different about the
 * design:
 *
 *  1. **Enumeration.** The set of order quantities comes from `ctx.feasibleBounds(0)`,
 *     filtered by `ctx.isFeasible`. Nothing in the design enumerates for you (§4.4.6.2's
 *     `feasibleActions` is specified and not built), so this rule does it itself.
 *
 *  2. **The post-decision state.** For inventory it is one line: order `a` on top of
 *     position `x` gives `y = x + a`, before any demand arrives. The design supplies no
 *     generic post-decision transition, but a modeler who knows their model needs no help.
 *
 *  3. **`V̄` itself.** Here it is the newsvendor cost of covering demand over the lead time
 *     plus the review period — the textbook approximation, computed rather than learned.
 *     [value] is the single method a learned or fitted `V̄` would replace.
 *
 * The purchase cost `c` is deliberately absent from the objective: every unit demanded is
 * eventually bought, so `c·a` is policy-invariant in the long run and including it would
 * bias the rule against ordering.
 */
class NewsvendorVfaPolicy(
    private val orderCost: Double = 32.0,
    private val holdingRate: Double = 1.0,
    private val shortageRate: Double = 5.0,
    private val positionIndex: Int = 0,
    private val expectedDemandIndex: Int = 1,
    /**
     * Fraction of the protection interval that is one review period, `R / (L + R)`. Used
     * only when [amortizeSetup] is on, to turn demand-over-the-protection-interval into
     * demand-per-period.
     */
    private val reviewFraction: Double = 5.0 / 7.0,
    /**
     * Whether `V̄` charges the setup cost **amortized over the order it buys** rather than
     * once against a single period.
     *
     * With it off this is a MYOPIC rule: it weighs one setup against one period's holding
     * and shortage, which over-penalises ordering and is the textbook reason myopic
     * policies are suboptimal under a fixed ordering cost. With it on, `K · muPerPeriod / a`
     * is the setup cost per period that an order of size `a` implies — one continuation
     * term, and the difference is measured in `VfaInventoryTest`.
     */
    private val amortizeSetup: Boolean = true,
    /** Cap on how many candidate actions to score. Enumeration is the policy's problem. */
    private val maxCandidates: Int = 400
) : ShapeAwarePolicyIfc {

    /** Counted so the exercise can report what enumeration actually costs. */
    var feasibilityChecks: Long = 0L
        private set
    var candidatesScored: Long = 0L
        private set

    override fun configure(surface: DecisionSurfaceDescriptor) {
        require(surface.levers.size == 1) {
            "NewsvendorVfaPolicy orders one quantity; the element declares ${surface.levers.size}."
        }
        require(surface.observations.size > maxOf(positionIndex, expectedDemandIndex)) {
            "NewsvendorVfaPolicy reads observations $positionIndex and $expectedDemandIndex, " +
                "but the element declares only ${surface.observations.size}."
        }
    }

    /** `V̄` at the post-decision position [y]: expected holding plus shortage over the interval. */
    private fun value(y: Double, mu: Double): Double {
        val leftover = expectedLeftover(y, mu)
        val short = mu - y + leftover
        return holdingRate * leftover + shortageRate * max(short, 0.0)
    }

    override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
        val x = observation[positionIndex]
        val mu = max(observation[expectedDemandIndex], 0.0)

        // 1 — enumerate. The design gives bounds and a membership test; the loop is ours.
        val range = ctx.feasibleBounds(0)
        val lo = Math.ceil(range.start).toInt()
        val hi = min(Math.floor(range.endInclusive), (lo + maxCandidates).toDouble()).toInt()

        var bestAction = 0.0
        var bestCost = Double.MAX_VALUE
        val probe = DoubleArray(1)

        for (a in lo..hi) {
            probe[0] = a.toDouble()
            feasibilityChecks++
            if (!ctx.isFeasible(probe)) continue
            candidatesScored++
            // 2 — the post-decision state, and 3 — V̄ evaluated at it.
            val setup = when {
                a <= 0 -> 0.0
                amortizeSetup -> orderCost * (mu * reviewFraction) / a
                else -> orderCost
            }
            val cost = setup + value(x + a, mu)
            if (cost < bestCost) {
                bestCost = cost
                bestAction = a.toDouble()
            }
        }
        return doubleArrayOf(bestAction)
    }

    override fun toString(): String =
        if (amortizeSetup) "newsvendor VFA (amortized setup)" else "newsvendor VFA (myopic)"
}

/**
 * A policy that wants to **learn** rather than evaluate, written against the interface the
 * design provides for it. It records nothing useful — the point is the counters.
 *
 * [ManagedPolicyIfc] declares exactly the three hooks an adaptive rule needs: reset per
 * episode, observe each completed transition, finish the episode. The interface exists and
 * a policy can implement it. Whether anything ever calls it is what
 * `VfaInventoryTest.theLearningHooksAreDeclaredButNeverCalled` measures.
 */
class LearningProbePolicy(
    private val inner: PolicyIfc
) : ManagedPolicyIfc {

    var episodesStarted = 0
        private set
    var transitionsSeen = 0
        private set
    var episodesEnded = 0
        private set
    var closes = 0
        private set
    var actionsTaken = 0
        private set

    override fun beforeEpisode(episodeIndex: Int) { episodesStarted++ }
    override fun onTransition(record: TransitionRecord) { transitionsSeen++ }
    override fun afterEpisode(episodeIndex: Int, source: TerminationSource) { episodesEnded++ }
    override fun close() { closes++ }

    override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
        actionsTaken++
        return inner.action(observation, ctx)
    }
}
