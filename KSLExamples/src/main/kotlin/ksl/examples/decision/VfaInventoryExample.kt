package ksl.examples.decision

import ksl.modeling.decision.*
import ksl.modeling.decision.descriptor.DecisionSurfaceDescriptor
import ksl.modeling.decision.descriptor.TerminationSource
import kotlin.math.exp
import kotlin.math.max

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
 * over the feasible actions.
 *
 * **This class is the §8.2.11 rewrite.** The first version inlined four things inside one
 * `action` method: the enumeration loop, the feasibility filter, the argmin, and the
 * empty-set fallback. All four are model-independent and now live in [LookaheadPolicy] and
 * [ExhaustiveSearch], leaving three overrides that are nothing but this problem's economics:
 *
 * ```
 *   contribution   C(s, a)     the ordering cost
 *   postDecision   S^x(s, a)   x + a, before demand arrives
 *   value          V̄           the newsvendor cost over the protection interval
 * ```
 *
 * Each is a pure function of arrays and can be unit-tested without a simulation. The
 * purchase cost `c` is deliberately absent: every unit demanded is eventually bought, so
 * `c·a` is policy-invariant in the long run and including it would bias against ordering.
 */
class NewsvendorVfaPolicy(
    private val orderCost: Double = 32.0,
    private val holdingRate: Double = 1.0,
    private val shortageRate: Double = 5.0,
    private val positionIndex: Int = 0,
    private val expectedDemandIndex: Int = 1,
    /** `R / (L + R)`: turns demand-over-the-protection-interval into demand-per-period. */
    private val reviewFraction: Double = 5.0 / 7.0,
    /**
     * Whether the contribution charges the setup cost **amortized over the order it buys**
     * rather than once against a single period.
     *
     * With it off this is a MYOPIC rule: one setup weighed against one period's holding and
     * shortage, which over-penalises ordering and is the textbook reason myopic policies are
     * suboptimal under a fixed ordering cost. With it on, `K · muPerPeriod / a` is the setup
     * cost per period that an order of size `a` implies.
     */
    private val amortizeSetup: Boolean = true
) : LookaheadPolicy(ExhaustiveSearch) {

    override fun configure(surface: DecisionSurfaceDescriptor) {
        require(surface.levers.size == 1) {
            "NewsvendorVfaPolicy orders one quantity; the element declares ${surface.levers.size}."
        }
        require(surface.observations.size > maxOf(positionIndex, expectedDemandIndex)) {
            "NewsvendorVfaPolicy reads observations $positionIndex and $expectedDemandIndex, " +
                "but the element declares only ${surface.observations.size}."
        }
    }

    /** `C(s, a)` — the ordering cost. Purchase cost is policy-invariant and omitted. */
    override fun contribution(
        observation: DoubleArray, action: DoubleArray, ctx: DecisionContext
    ): Double {
        val a = action[0]
        if (a <= 0.0) return 0.0
        val mu = max(observation[expectedDemandIndex], 0.0)
        return if (amortizeSetup) orderCost * (mu * reviewFraction) / a else orderCost
    }

    /** `S^x(s, a)` — order `a` onto position `x` and the position is `x + a`, pre-demand. */
    override fun postDecision(observation: DoubleArray, action: DoubleArray): DoubleArray {
        val next = observation.copyOf()
        next[positionIndex] = observation[positionIndex] + action[0]
        return next
    }

    /** `V̄` — newsvendor cost of covering demand over the lead time plus the review period. */
    override fun value(postDecision: DoubleArray, ctx: DecisionContext): Double {
        val y = postDecision[positionIndex]
        val mu = max(postDecision[expectedDemandIndex], 0.0)
        val leftover = expectedLeftover(y, mu)
        val short = mu - y + leftover
        return holdingRate * leftover + shortageRate * max(short, 0.0)
    }

    override fun toString(): String =
        if (amortizeSetup) "newsvendor VFA (amortized setup)" else "newsvendor VFA (myopic)"
}

/**
 * A policy that wants to **learn** rather than evaluate, written against the interface the
 * design provides for it. It records nothing useful — the point is the counters.
 *
 * [ManagedPolicyIfc] declares exactly the three hooks an adaptive rule needs: reset per
 * episode, observe each completed transition, finish the episode. The interface exists, a policy
 * can implement it, and the epoch loop now calls it — which is what
 * `VfaInventoryTest.theLearningHooksAreCalledWithOneEpisodePerReplication` measures. It counted
 * zeros until M1 steps 7b and 7c landed.
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
