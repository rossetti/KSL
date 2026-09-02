package ksl.examples.decision

import ksl.modeling.decision.*
import ksl.modeling.decision.descriptor.DecisionSurfaceDescriptor
import ksl.modeling.elements.EventGeneratorIfc
import ksl.modeling.nhpp.NHPPEventGenerator
import ksl.modeling.nhpp.PiecewiseConstantRateFunction
import ksl.modeling.variable.Counter
import ksl.modeling.variable.TWResponse
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import kotlin.math.max
import kotlin.math.min

/**
 * A depot allocating scarce stock across three regions — the exercise §4.4.6 was written
 * for, and the first model here whose **feasible action set genuinely depends on state**.
 *
 * Every review period a truck loads. The decision is how many units go to each region.
 * Three constraints, and all three move:
 *
 * ```
 *   ship[i]  <=  backlog[i]          you cannot send what nobody has asked for
 *   sum(ship) <= onHand              you cannot ship what you do not have
 *   sum(ship) <= truckCapacity       the only constant among them
 * ```
 *
 * Regions differ in shortage cost, so the allocation is not symmetric: when stock is short
 * the expensive region should be served first. That is what makes a greedy rule beat a
 * proportional one, and what makes the feasible set load-bearing rather than decorative.
 *
 * [stateDependentDeclaration] selects which design the element is built against:
 *
 *  - `true`  — §4.4.6. The model declares `bounds = { … }` per lever and a state-dependent
 *              `atMost`, so the element owns its own conservation law.
 *  - `false` — the design as it stood before §4.4.6. Only the envelope can be declared, so
 *              the constraints must be re-derived inside every policy, and the element
 *              cannot check them.
 */
class ShipmentDepot(
    parent: ModelElement,
    initialStock: Int = 20,
    reviewPeriod: Double = 10.0,
    // The truck is the ENVELOPE and is deliberately slack: the binding constraint is meant
    // to be stock, which is the state-dependent one. Resupply arrives in lumps at 1.833
    // per unit time against demand of 1.8, so the system is barely stable and stock is
    // genuinely scarce at most reviews — which is what makes the allocation a decision.
    private val truckCapacity: Int = 100,
    private val resupplyQuantity: Int = 55,
    private val resupplyPeriod: Double = 30.0,
    val stateDependentDeclaration: Boolean = true,
    name: String? = null
) : ModelElement(parent, name) {

    /** Shortage cost per unit per unit time. The reason allocation is not symmetric. */
    val shortageRates = doubleArrayOf(9.0, 3.0, 1.0)
    private val regionNames = listOf("North", "Central", "South")

    private val myOnHand = TWResponse(
        this, name = "${this.name}:OnHand", initialValue = initialStock.toDouble())

    private val myBacklog = regionNames.map { r ->
        TWResponse(this, name = "${this.name}:$r:Backlog")
    }
    private val myShipped = regionNames.map { r ->
        Counter(this, name = "${this.name}:$r:Shipped")
    }
    private val myUnmet = Counter(this, name = "${this.name}:UnmetAtRunEnd")

    val onHand: Double get() = myOnHand.value
    fun backlog(i: Int): Double = myBacklog[i].value

    /** The total that could be shipped right now: never more than stock or the truck. */
    val shippableNow: Double get() = min(myOnHand.value, truckCapacity.toDouble())

    // ---- Demand: each region has its own arrival stream --------------------------
    private val demandGenerators = regionNames.indices.map { i ->
        NHPPEventGenerator(
            this, { _: EventGeneratorIfc -> myBacklog[i].increment(1.0) },
            PiecewiseConstantRateFunction(doubleArrayOf(100.0), doubleArrayOf(0.6)),
            streamNum = 21 + i, name = "${this.name}:${regionNames[i]}:Demand"
        )
    }

    // ---- Resupply ----------------------------------------------------------------
    private inner class Resupply : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            myOnHand.increment(resupplyQuantity.toDouble())
            schedule(resupplyPeriod)
        }
    }
    private val resupply = Resupply()

    /**
     * Ship [qty] units to region [i]. Defensive clamping is present ONLY so that the
     * `stateDependentDeclaration = false` arm cannot corrupt the model — see
     * [overShipmentsAbsorbed], which counts how often it fires. Under §4.4.6 the element
     * rejects an infeasible action before this is ever called and the counter stays zero.
     */
    private fun ship(i: Int, qty: Int) {
        if (qty <= 0) return
        val actual = minOf(qty.toDouble(), myBacklog[i].value, myOnHand.value)
        if (actual < qty.toDouble()) overShipmentsAbsorbed++
        if (actual <= 0.0) return
        myOnHand.decrement(actual)
        myBacklog[i].decrement(actual)
        myShipped[i].increment(actual)
    }

    /** How many times the model had to defend itself against its own decision element. */
    var overShipmentsAbsorbed: Int = 0
        private set

    // ---- The decision ------------------------------------------------------------

    /**
     *  The depot allocates on a period, so it says so in one construction.
     *
     *  `PeriodicDecisionElement` owns the element **and** the event that reviews it. The interval is
     *  checked where it is given, so a depot that never allocates cannot be built; the review runs in
     *  an event of its own and changes nothing itself, so the state the rule reads is consistent
     *  without this model having to be careful about where it calls from; and the period is a
     *  `@KSLControl` on a named type, so a study can search it.
     *
     *  A model that allocated on a *condition* rather than a period would drop this and call
     *  `allocation.decide(reason)` at the point the condition holds — the composite is a convenience
     *  over that door, not a different way in.
     */
    private val reviews = PeriodicDecisionElement(this, reviewPeriod, name = "Allocation") {
        // 0..2 the backlogs, 3 the stock. A policy needs all four under either design;
        // under the old one it needs them to RE-DERIVE the feasible set for itself.
        for (i in regionNames.indices) {
            observe("${regionNames[i]}:Backlog") { backlog(i) }
        }
        observe("Stock") { onHand }

        val refs = regionNames.indices.map { i ->
            if (stateDependentDeclaration) {
                // §4.4.6: the envelope is the truck; 𝒳(s) is what this region is owed.
                lever(
                    this@ShipmentDepot, limits = 0..truckCapacity,
                    // Shipping is a TRANSACTION: there is no "current shipment", and two
                    // dispatches of the same size are two dispatches. Doing nothing is
                    // shipping zero, which is an action (§8.2.3).
                    neutral = Neutral.Value(0.0),
                    alias = "Ship:${regionNames[i]}",
                    bounds = { 0.0..backlog(i) }
                ) { q -> ship(i, q.toInt()) }
            } else {
                lever(
                    this@ShipmentDepot, limits = 0..truckCapacity,
                    neutral = Neutral.Value(0.0),
                    alias = "Ship:${regionNames[i]}"
                ) { q -> ship(i, q.toInt()) }
            }
        }

        if (stateDependentDeclaration) {
            // The conservation law, declared by the model that owns it.
            atMost(*refs.toTypedArray(), envelope = truckCapacity.toDouble()) { shippableNow }
        } else {
            // The best that can be said without §4.4.6: a constant that is only ever an
            // upper bound on the truth, so the element cannot enforce the real constraint.
            atMost(*refs.toTypedArray(), total = truckCapacity.toDouble())
        }

        policy = NeutralPolicy
    }

    /** The element itself: assign the rule here, narrow a lever here, attach a sink here. */
    val allocation: DecisionElement get() = reviews.element

    /**
     *  How often the depot allocates.
     *
     *  Read from the reviewer rather than stored beside it, so that a study which moves the period
     *  through the control cannot leave this reporting the value it was built with.
     */
    val reviewPeriod: Double get() = reviews.interval

    override fun initialize() {
        overShipmentsAbsorbed = 0
        resupply.schedule(resupplyPeriod)
    }

    override fun replicationEnded() {
        myUnmet.increment(myBacklog.sumOf { it.value })
    }
}

/**
 * The do-nothing arm §4.1.10 requires. Ship nothing, ever.
 *
 * Like `OrderNothingPolicy`, this is a hand-written stand-in for a baseline the library
 * could not supply before §8.2.3, and `NeutralPolicy` now supplies it generically for these
 * transactional levers. Kept so the benchmark keeps naming what it named.
 */
object ShipNothing : PolicyIfc {
    override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray =
        DoubleArray(ctx.leverNames.size)
}

/**
 * Serve the most expensive region first, up to what it is owed, while stock lasts.
 *
 * This is a cost function approximation in Powell's sense (§2.1): a small optimization
 * solved at every epoch, subject to the constraints in force. **It is the policy class
 * §4.4.6 exists to enable**, and it is written twice below so the two designs can be
 * compared on identical logic.
 *
 * [useFeasibleSet] true — asks the context what is available (§4.4.6.2).
 * [useFeasibleSet] false — re-derives it from observations, as the old design required.
 */
class GreedyByShortageCost(
    private val rates: DoubleArray,
    private val useFeasibleSet: Boolean
) : ShapeAwarePolicyIfc {

    private lateinit var order: List<Int>

    override fun configure(surface: DecisionSurfaceDescriptor) {
        require(surface.levers.size == rates.size) {
            "GreedyByShortageCost has ${rates.size} rates but the element declares " +
                "${surface.levers.size} levers."
        }
        // Expensive-first, computed once from the declared shape rather than per epoch.
        order = rates.indices.sortedByDescending { rates[it] }
    }

    override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
        val n = ctx.leverNames.size
        val plan = DoubleArray(n)

        var remaining = if (useFeasibleSet) {
            // The total the element will allow. Under §4.4.6 this is the real constraint.
            ctx.budgetTotal(0) ?: Double.MAX_VALUE
        } else {
            // Re-derived: observation n is the stock, and the truck cap is a literal the
            // policy has to be told because the declaration cannot express the real bound.
            min(observation[n], 100.0)
        }

        for (i in order) {
            val want = if (useFeasibleSet) {
                ctx.actions.bounds(i).endInclusive          // what this region is owed
            } else {
                observation[i]                              // the same number, re-derived
            }
            val give = max(0.0, minOf(want, remaining))
            plan[i] = Math.rint(give)
            remaining -= plan[i]
        }
        return plan
    }
}

/**
 * Allocate the shippable total in proportion to backlog — a policy function approximation,
 * and the natural first thing to try. It ignores the differing shortage costs.
 */
class ProportionalShipping(private val useFeasibleSet: Boolean) : PolicyIfc {
    override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
        val n = ctx.leverNames.size
        val total = if (useFeasibleSet) (ctx.budgetTotal(0) ?: 0.0) else min(observation[n], 100.0)
        // Per-lever caps. The clinic version of this rule had none, because there the only
        // constraint was a fixed budget and the per-lever limits were slack. Here they bind,
        // and omitting them makes the rule infeasible whenever stock exceeds total backlog —
        // which §4.4.6 rejects rather than letting the model absorb.
        val cap = DoubleArray(n) {
            if (useFeasibleSet) ctx.actions.bounds(it).endInclusive else max(observation[it], 0.0)
        }
        val backlogs = DoubleArray(n) { max(observation[it], 0.0) }
        val sum = backlogs.sum()
        if (sum <= 0.0 || total <= 0.0) return DoubleArray(n)

        val ideal = DoubleArray(n) { min(total * backlogs[it] / sum, cap[it]) }
        val plan = DoubleArray(n) { Math.floor(ideal[it]) }
        var remaining = total - plan.sum()
        while (remaining >= 1.0) {
            var best = -1
            var gap = Double.NEGATIVE_INFINITY
            for (i in 0 until n) {
                if (plan[i] + 1.0 > cap[i]) continue
                val g = ideal[i] - plan[i]
                if (g > gap) { gap = g; best = i }
            }
            if (best < 0) break
            plan[best] += 1.0
            remaining -= 1.0
        }
        return plan
    }
}

/** Average cost per unit time: shortage held against each region at its own rate. */
fun shipmentCost(model: Model, prefix: String, rates: DoubleArray): Double {
    val names = listOf("North", "Central", "South")
    var total = 0.0
    for ((i, r) in names.withIndex()) {
        val avg = model.responses.first { it.name == "$prefix:$r:Backlog" }
            .acrossReplicationStatistic.average
        total += rates[i] * avg
    }
    return total
}
