package ksl.modeling.decision

import ksl.modeling.decision.descriptor.DecisionSurfaceDescriptor
import ksl.modeling.decision.descriptor.LeverDomain
import ksl.utilities.GetValueIfc

/**
 *  The feasible action set 𝒳(s) as an OBJECT rather than as scattered members of the
 *  decision context (§4.4.6.5).
 *
 *  An earlier form of §4.4.6 put `feasibleBounds`, `isFeasible` and `violations` directly
 *  on [DecisionContext] and left `feasibleActions` unbuilt. That correctly identified that
 *  the set was missing and then failed to name it, so every rule that wanted to search
 *  𝒳(s) rebuilt the loop, the filter and the argmin for itself. Naming it makes those
 *  reusable and testable without a simulation.
 *
 *  All members are pure and epoch-scoped: an instance is valid only during the `action`
 *  call that received the context carrying it.
 */
interface ActionSet {

    /** How many levers an action vector has. */
    val leverCount: Int

    /**
     *  How many actions the set contains, or **null** when that is not a useful question —
     *  any `CONTINUOUS` lever, or a discrete product beyond [ENUMERATION_CEILING].
     *
     *  A rule that intends to enumerate must consult this first. Its whole purpose is to
     *  let a policy refuse in `configure` rather than discover at the first epoch that the
     *  set it planned to walk has ten million members.
     */
    val size: Long?

    /** Bounds in force for this lever at this epoch: envelope ∩ narrowed ∩ 𝒳(s) (§4.3.3). */
    fun bounds(leverIndex: Int): ClosedFloatingPointRange<Double>

    /** Membership. The same predicate the element applies when the action arrives. */
    operator fun contains(action: DoubleArray): Boolean

    /** Why not. Empty exactly when [contains] is true. */
    fun violations(action: DoubleArray): List<String>

    /**
     *  Every action in the set. Throws when [size] is null, because a rule that reaches
     *  here without checking has made an error the library should not paper over.
     */
    fun asSequence(): Sequence<DoubleArray>

    /**
     *  Up to [count] feasible actions drawn at random — the only way to search a set that
     *  [asSequence] cannot walk, which on a model with several state-dependent levers is
     *  the usual case rather than the exception (§8.2.10: 97% of epochs).
     *
     *  [uniform] must yield U(0, 1) and should come from a `RandomVariable` owned by the
     *  policy, so that draws reset per replication and honour the model's stream options
     *  (§4.5.6, D.12). The set does not own randomness and must not.
     *
     *  The default strategy is rejection, so **the yielded count may be fewer than
     *  [count]** — with a tight equality constraint it may be zero. [acceptanceRate] is
     *  how a caller finds that out. A constraint-aware sampler may be substituted per
     *  constraint kind without changing this signature.
     */
    fun sample(uniform: GetValueIfc, count: Int, maxAttempts: Int = count * 20): Sequence<DoubleArray>

    /** Accepted draws over attempted draws, since this set was created. NaN before any. */
    val acceptanceRate: Double

    companion object {
        /** Above this, [size] reports null rather than a number nobody should walk. */
        const val ENUMERATION_CEILING: Long = 1_000_000L
    }
}

/**
 *  How a rule searches [ActionSet] for its best member. Model-independent, which is why it
 *  belongs to the library: the loop is the same for every value-function and cost-function
 *  policy ever written, and only the scoring differs.
 */
fun interface ActionSearch {
    /**
     *  The action minimising [score], or **null** when the set is empty.
     *  [score] is called at most once per candidate and must be pure.
     */
    fun best(actions: ActionSet, score: (DoubleArray) -> Double): DoubleArray?
}

/** Score every action. Requires a finite [ActionSet.size]. */
object ExhaustiveSearch : ActionSearch {
    override fun best(actions: ActionSet, score: (DoubleArray) -> Double): DoubleArray? {
        checkNotNull(actions.size) {
            "ExhaustiveSearch needs an enumerable action set. This one reports no size — " +
                "it has a continuous lever, or more than ${ActionSet.ENUMERATION_CEILING} members. " +
                "Use GridSearch, or refuse in configure()."
        }
        var best: DoubleArray? = null
        var bestScore = Double.MAX_VALUE
        for (a in actions.asSequence()) {
            val s = score(a)
            if (s < bestScore) { bestScore = s; best = a.copyOf() }
        }
        return best
    }
}

/**
 *  Score a regular grid over each lever's feasible range, keeping only feasible points.
 *  The fallback when the set is continuous or too large to walk.
 */
class GridSearch(private val pointsPerLever: Int = 11) : ActionSearch {

    init { require(pointsPerLever >= 2) { "A grid needs at least 2 points per lever." } }

    override fun best(actions: ActionSet, score: (DoubleArray) -> Double): DoubleArray? {
        val n = actions.leverCount
        val axes = (0 until n).map { i ->
            val r = actions.bounds(i)
            if (r.isEmpty()) return null
            if (r.start == r.endInclusive) doubleArrayOf(r.start)
            else DoubleArray(pointsPerLever) { k ->
                r.start + (r.endInclusive - r.start) * k / (pointsPerLever - 1)
            }
        }
        var best: DoubleArray? = null
        var bestScore = Double.MAX_VALUE
        val current = DoubleArray(n)

        fun walk(i: Int) {
            if (i == n) {
                if (current in actions) {
                    val s = score(current)
                    if (s < bestScore) { bestScore = s; best = current.copyOf() }
                }
                return
            }
            for (v in axes[i]) { current[i] = v; walk(i + 1) }
        }
        walk(0)
        return best
    }
}

/**
 *  Score [candidates] actions drawn at random from the set.
 *
 *  This is the strategy for an action space that is neither small enough to enumerate nor
 *  low-dimensional enough to grid — which the declaration surface admits directly: several
 *  levers with state-dependent bounds produce sets of a million and more (§8.2.10).
 *
 *  [uniform] should be a `RandomVariable` the policy owns, per §4.5.6. Because sampling is
 *  by rejection, a tight constraint can starve it; a caller that must know should read
 *  [ActionSet.acceptanceRate].
 */
class SampledSearch(
    private val candidates: Int = 200,
    private val uniform: GetValueIfc
) : ActionSearch {

    init { require(candidates >= 1) { "A sampled search needs at least one candidate." } }

    override fun best(actions: ActionSet, score: (DoubleArray) -> Double): DoubleArray? {
        var best: DoubleArray? = null
        var bestScore = Double.MAX_VALUE
        for (a in actions.sample(uniform, candidates)) {
            val s = score(a)
            if (s < bestScore) { bestScore = s; best = a.copyOf() }
        }
        return best
    }
}

/**
 *  An approximation of downstream value, evaluated at a post-decision state — the V̄ of
 *  approximate dynamic programming (§4.5.5).
 *
 *  This is the piece only the modeler can supply, and separating it is the point: it can be
 *  unit-tested with no simulation, swapped between a computed form and a fitted one without
 *  touching the policy that uses it, and shared between policies.
 *
 *  **Not `ValueFunctionIfc`**, which this was called until a review noticed that
 *  `ksl.utilities.moda.ValueFunctionIfc` already exists — also a `fun interface`, also with a
 *  `value(...)` method, and used by `MODAModel` and `PDFModeler`. The two mean different
 *  things: MODA's maps a criterion's score onto a common value scale, and this one estimates
 *  cost-to-go. A study scoring decision policies on several criteria would import both, and
 *  the collision would surface as an import alias in user code rather than in the library.
 */
fun interface ValueApproximationIfc {
    /** The estimated cost-to-go from [postDecision]. */
    fun value(postDecision: DoubleArray): Double
}

/**
 *  A value function that improves from observed experience.
 *
 *  The seam exists so that a [LookaheadPolicy] holding one of these becomes a learning rule by
 *  forwarding `ManagedPolicyIfc.onTransition` to [update] — one class rather than a new concept.
 *
 *  The epoch loop delivers the transitions and the rewards it needs: M1 steps 7b and 7c wired
 *  §4.10.2's steps 2 and 4, and §8.2.9 measures the hooks firing. **No shipped class forwards them
 *  to [update]** — that adapter is the one piece still unwritten, and writing it is not the same
 *  problem as choosing a fitting algorithm, which is out of scope (§1.2).
 */
interface LearnableValueApproximationIfc : ValueApproximationIfc {
    /** Fold one observation of realised cost-to-go into the estimate. */
    fun update(postDecision: DoubleArray, observedCostToGo: Double)
    /** Forget everything. Called once per episode — one episode per replication (§4.6.3). */
    fun reset()
}

/**
 *  The skeleton shared by every policy that **chooses among available actions** rather than
 *  constructing one directly: value-function approximations, and cost-function
 *  approximations that score candidates.
 *
 *  A modeler supplies the three model-specific pieces and nothing else:
 *
 *  ```kotlin
 *  class MyRule : LookaheadPolicy(ExhaustiveSearch) {
 *      override fun contribution(obs, action, ctx) = ...   // C(s, a), the immediate cost
 *      override fun postDecision(obs, action)      = ...   // S^x(s, a)
 *      override fun value(postDecision, ctx)       = ...   // V̄
 *  }
 *  ```
 *
 *  The enumeration, the feasibility filter, the argmin and the empty-set fallback come from
 *  the library. Contrast §8.2.9's first VFA, which inlined all four inside one `action`
 *  method — the reason §8.2.11 argues the design was one abstraction where the problem has
 *  five.
 *
 *  **Not every policy fits this shape, and that is deliberate.** A rule that *constructs* an
 *  action — the greedy allocator of §8.2.8 sorts regions and fills them — implements plain
 *  [PolicyIfc] instead. Two shapes, because there are two ways to decide.
 */
abstract class LookaheadPolicy(
    protected val search: ActionSearch = ExhaustiveSearch
) : ShapeAwarePolicyIfc {

    /** `C(s, a)`: the cost incurred by taking [action] now. */
    protected abstract fun contribution(
        observation: DoubleArray, action: DoubleArray, ctx: DecisionContext): Double

    /**
     *  `S^x(s, a)`: the state immediately after the decision and before the exogenous
     *  information. Defaults to the observation unchanged, which is right for a rule whose
     *  action does not move the observed state.
     */
    protected open fun postDecision(observation: DoubleArray, action: DoubleArray): DoubleArray =
        observation

    /** `V̄`: estimated cost-to-go from the post-decision state. */
    protected abstract fun value(postDecision: DoubleArray, ctx: DecisionContext): Double

    /**
     *  What to do when 𝒳(s) is empty (§4.4.6.3). Zeros by default; §8.2.3's declared
     *  neutral value is what should replace this once it exists.
     */
    protected open fun whenNothingIsFeasible(ctx: DecisionContext): DoubleArray =
        DoubleArray(ctx.leverNames.size)

    override fun configure(surface: DecisionSurfaceDescriptor) {}

    final override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray =
        search.best(ctx.actions) { a ->
            contribution(observation, a, ctx) + value(postDecision(observation, a), ctx)
        } ?: whenNothingIsFeasible(ctx)
}

/**
 *  The element's [ActionSet]. Enumeration is over the integer and categorical levers only;
 *  a continuous lever makes [size] null, which is what tells a rule to use [GridSearch].
 *
 *  [live] is the owning context's epoch-scope check (§4.5.3, G.9 row 6). 𝒳(s) is a function of
 *  the state, so a set retained past its epoch answers about a state that has moved on — the
 *  same hazard as a retained context, and it would survive a guard placed only on the
 *  context's `actions` getter.
 */
internal class ElementActionSet(
    private val element: DecisionElement,
    private val live: (String) -> Unit = {}
) : ActionSet {

    override val leverCount: Int get() = element.myLeverDecls.size

    override fun bounds(leverIndex: Int): ClosedFloatingPointRange<Double> {
        live("bounds")
        return element.myLeverDecls[leverIndex].feasibleRange()
    }

    override operator fun contains(action: DoubleArray): Boolean {
        live("contains")
        return element.binding.prepare(action) is PreparedAction.Ready
    }

    override fun violations(action: DoubleArray): List<String> {
        live("violations")
        return (element.binding.prepare(action) as? PreparedAction.Invalid)?.violations ?: emptyList()
    }

    /** Per-lever candidate counts, or null if any lever is continuous or the product is huge. */
    private fun axisCounts(): LongArray? {
        val counts = LongArray(leverCount)
        var product = 1L
        for ((i, d) in element.myLeverDecls.withIndex()) {
            if (d.domain == LeverDomain.CONTINUOUS) return null
            val r = d.feasibleRange()
            if (r.isEmpty()) { counts[i] = 0L; product = 0L; continue }
            val lo = Math.ceil(r.start).toLong()
            val hi = Math.floor(r.endInclusive).toLong()
            val c = if (hi < lo) 0L else hi - lo + 1L
            counts[i] = c
            if (c == 0L) { product = 0L } else {
                if (product > ActionSet.ENUMERATION_CEILING / c) return null
                product *= c
            }
        }
        return counts
    }

    override val size: Long?
        get() {
            live("size")
            val counts = axisCounts() ?: return null
            var p = 1L
            for (c in counts) p *= c
            return p
        }

    private var myDraws = 0L
    private var myAccepted = 0L

    override val acceptanceRate: Double
        get() = if (myDraws == 0L) Double.NaN else myAccepted.toDouble() / myDraws

    override fun sample(uniform: GetValueIfc, count: Int, maxAttempts: Int): Sequence<DoubleArray> {
        live("sample")
        val n = leverCount
        val ranges = (0 until n).map { bounds(it) }
        val integral = element.myLeverDecls.map { it.domain != LeverDomain.CONTINUOUS }
        return sequence {
            if (ranges.any { it.isEmpty() }) return@sequence
            var yielded = 0
            var attempts = 0
            val candidate = DoubleArray(n)

            // Seed with the lower-bound corner. Under a SumAtMost constraint it is feasible
            // whenever the set is non-empty, and it is exactly the point uniform rejection
            // is least likely to find when a joint total is tight relative to the boxes —
            // measured starvation without it, on the shipment depot.
            for (i in 0 until n) {
                candidate[i] = if (integral[i]) Math.ceil(ranges[i].start) else ranges[i].start
            }
            myDraws++
            if (candidate in this@ElementActionSet) {
                myAccepted++; yielded++
                yield(candidate.copyOf())
            }
            while (yielded < count && attempts < maxAttempts) {
                attempts++
                myDraws++
                for (i in 0 until n) {
                    val r = ranges[i]
                    val u = uniform.value
                    var v = r.start + u * (r.endInclusive - r.start)
                    if (integral[i]) v = Math.rint(v)
                    candidate[i] = v
                }
                if (candidate in this@ElementActionSet) {
                    myAccepted++
                    yielded++
                    yield(candidate.copyOf())
                }
            }
        }
    }

    override fun asSequence(): Sequence<DoubleArray> {
        live("asSequence")
        val counts = axisCounts()
            ?: throw IllegalStateException(
                "This action set is not enumerable — it has a continuous lever, or more than " +
                    "${ActionSet.ENUMERATION_CEILING} members. Check ActionSet.size first."
            )
        val lows = DoubleArray(leverCount) { Math.ceil(element.myLeverDecls[it].feasibleRange().start) }
        return sequence {
            if (counts.any { it == 0L }) return@sequence
            val idx = IntArray(counts.size)
            val current = DoubleArray(counts.size)
            while (true) {
                for (i in idx.indices) current[i] = lows[i] + idx[i]
                if (current in this@ElementActionSet) yield(current.copyOf())
                var k = counts.size - 1
                while (k >= 0) {
                    idx[k]++
                    if (idx[k] < counts[k]) break
                    idx[k] = 0
                    k--
                }
                if (k < 0) break
            }
        }
    }
}
