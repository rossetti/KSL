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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Demand rate patterns for [SsInventory]. Both have a mean rate of 1.0 per unit time over
 * a 100-unit cycle, so the two models see the same total demand and differ only in when it
 * arrives. That is what makes the comparison in §8.2.6 clean.
 */
object DemandRates {
    /** Flat. Equivalent to a stationary Poisson process at rate 1. */
    val stationary: PiecewiseConstantRateFunction
        get() = PiecewiseConstantRateFunction(doubleArrayOf(100.0), doubleArrayOf(1.0))

    /** A quiet stretch, a sharp season at six times the rate, then quiet again. */
    val seasonal: PiecewiseConstantRateFunction
        get() = PiecewiseConstantRateFunction(
            doubleArrayOf(40.0, 20.0, 40.0), doubleArrayOf(0.5, 3.0, 0.5))
}

/**
 * A periodic-review inventory system, built from scratch — it uses no KSL inventory
 * package, only `ModelElement`, `TWResponse`, and `Counter`.
 *
 * This is the textbook Markov decision process. The state at a review epoch is the
 * **inventory position** — on hand, less backorders, plus what is already on order — and
 * it is a genuine sufficient statistic rather than a proxy for one. The action is the
 * **order quantity**. Cost has four components, and between them they exercise all three
 * of the reward kinds in §4.2.5:
 *
 * ```
 *   K per order placed          COUNTER_TOTAL     orderCount
 *   c per unit ordered          COUNTER_TOTAL     unitsOrdered
 *   h per unit of stock, per unit time            TIME_INTEGRAL   onHand
 *   p per unit backordered, per unit time         TIME_INTEGRAL   backorder
 * ```
 *
 * Demand is unit-sized and arrives in a Poisson stream. Unmet demand is backordered and
 * filled from the next replenishment. An order placed at a review epoch arrives one lead
 * time later.
 *
 * The decision element declares one lever — the order quantity — and one observation, the
 * inventory position. What that reveals about the design is the subject of §8.2.
 */
class SsInventory(
    parent: ModelElement,
    initialOnHand: Int = 60,
    private val leadTime: Double = 2.0,
    private val reviewPeriod: Double = 5.0,
    /**
     *  Which wiring the review uses. Both are kept because the contrast is the lesson.
     *
     *  `true` — a [ksl.modeling.decision.PeriodicDecisionElement], which owns the element *and* the
     *  event that reviews it. One construction, the interval checked where it is given, a call site
     *  that is clean because the reviewer changes nothing itself, and a searchable period. This is
     *  what a model that reviews on a period should use.
     *
     *  `false` — the general mechanism underneath: declare the element, then attach a caller. It is
     *  what you write when a period is *not* what you want — a review at a stockout, or at a point
     *  inside a process — and seeing it here is what makes the composite legible as a convenience
     *  over a door rather than as the only way in.
     *
     *  The two produce the same trajectory; `SsInventoryBenchmarkTest` asserts it.
     */
    private val composedReview: Boolean = true,
    private val rateFunction: PiecewiseConstantRateFunction = DemandRates.stationary,
    maxOrder: Int = 200,
    /**
     * Whether to declare the order-quantity lever as a SETTING rather than a TRANSACTION.
     *
     * An order quantity is a transaction: there is no "current order quantity", and the
     * closest thing — the size of the last order — is only meaningful if you believe an
     * order is a setting. Before §8.2.3 the declaration could not say which it was, and
     * §4.1.2.3's advice to always supply a reader pushed a modeler toward the wrong one.
     * It can now be said, and this flag exists to declare it **wrong on purpose** so the
     * consequence stays measurable (§8.2.2).
     */
    declareOrderAsSetting: Boolean = false,
    /**
     * Where to record this inventory's decisions, or `null` to record nothing. This is the
     * *declared* form of capture (§4.10.3), which is one of two: a caller holding a built model
     * can instead attach a sink to `review` from outside, or capture every decision element at
     * once with `DecisionCapture`. `OverheadBenchmarkTest` uses this one to measure what capture
     * costs per epoch.
     */
    private val decisionSink: ((RunProvenance) -> TransitionSink)? = null,
    name: String? = null
) : ModelElement(parent, name) {

    /** The size of the most recent order. Only meaningful if you believe an order is a setting. */
    private var lastOrderQuantity: Double = 0.0

    private val myOnHand = TWResponse(
        this, name = "${this.name}:OnHand", initialValue = initialOnHand.toDouble())
    private val myBackorder = TWResponse(this, name = "${this.name}:BackOrder")
    private val myOnOrder = TWResponse(this, name = "${this.name}:OnOrder")
    private val myOrderCount = Counter(this, name = "${this.name}:OrderCount")
    private val myUnitsOrdered = Counter(this, name = "${this.name}:UnitsOrdered")

    /** On hand, less what is owed, plus what is already coming. The MDP state. */
    val inventoryPosition: Double
        get() = myOnHand.value - myBackorder.value + myOnOrder.value

    val onHand: Double get() = myOnHand.value

    // ---- Demand -----------------------------------------------------------------
    // A non-homogeneous Poisson process, from ksl.modeling.nhpp. The rate function repeats
    // when its range is covered, so a 100-unit pattern cycles for the whole replication.
    private val myDemandGenerator = NHPPEventGenerator(
        this, this::demandArrival, rateFunction, streamNum = 11)

    private val myDemandCount = Counter(this, name = "${this.name}:DemandCount")

    private fun demandArrival(generator: EventGeneratorIfc) {
        myDemandCount.increment()
        if (myOnHand.value >= 1.0) myOnHand.decrement(1.0) else myBackorder.increment(1.0)
    }

    // ---- What a rule may know about demand ---------------------------------------
    /** Lead time plus review period: the interval an order placed now has to cover. */
    val protectionInterval: Double get() = leadTime + reviewPeriod

    private val cycleLength: Double get() = rateFunction.timeRangeUpperLimit
    private val ratePerCycle: Double get() = rateFunction.cumulativeRateRangeUpperLimit

    /** The cumulative rate at absolute time [t], unwrapping the repeated cycle. */
    private fun cumulativeRateAt(t: Double): Double {
        val cycles = floor(t / cycleLength)
        val within = t - cycles * cycleLength
        // The rate function's range is closed below and open above, so a time landing
        // exactly on the cycle boundary belongs to the next cycle's start.
        return if (within >= cycleLength) (cycles + 1.0) * ratePerCycle
        else cycles * ratePerCycle + rateFunction.cumulativeRate(within)
    }

    /**
     * Expected demand between now and now plus the protection interval. This is
     * ANTICIPATIVE: the rate function is known, so a rule can see the season coming
     * rather than inferring it after it starts.
     */
    val expectedDemandOverProtection: Double
        get() = cumulativeRateAt(time + protectionInterval) - cumulativeRateAt(time)

    /** The instantaneous demand rate. Known for the same reason. */
    val currentDemandRate: Double get() = rateFunction.rate(time % cycleLength)

    /**
     * Demand observed since the previous review — a REACTIVE signal, and one the design
     * cannot express today. This is exactly `INTERVAL_DELTA` over [myDemandCount]
     * (§4.2.4.1), hand-rolled in the model because the observation kind does not exist.
     */
    private var demandCountAtLastReview: Double = 0.0
    val demandSinceLastReview: Double
        get() = myDemandCount.value - demandCountAtLastReview

    // ---- Replenishment ----------------------------------------------------------
    private inner class OrderArrival : EventAction<Int>() {
        override fun action(event: KSLEvent<Int>) {
            var q = (event.message ?: 0).toDouble()
            myOnOrder.decrement(q)
            val filled = min(q, myBackorder.value)
            if (filled > 0.0) {
                myBackorder.decrement(filled)
                q -= filled
            }
            if (q > 0.0) myOnHand.increment(q)
        }
    }
    private val orderArrival = OrderArrival()

    /**
     * Place an order for [quantity] units. **This is a transaction, not a setting.**
     * Ordering zero is not "leave the order where it is" — it is a decision to order
     * nothing, and ordering the same quantity twice places two orders.
     */
    private fun placeOrder(quantity: Int) {
        lastOrderQuantity = quantity.toDouble()
        demandCountAtLastReview = myDemandCount.value
        if (quantity <= 0) return
        myOrderCount.increment()
        myUnitsOrdered.increment(quantity.toDouble())
        myOnOrder.increment(quantity.toDouble())
        orderArrival.schedule(leadTime, message = quantity)
    }

    // ---- The decision -----------------------------------------------------------
    /**
     *  §4.1.9. The name is qualified with the subsystem's own, exactly as every other child of
     *  this class is (`${this.name}:OnHand`, `${this.name}:OrderCount`, …). It was the bare
     *  literal "Review" until a multi-element interrogation found that two `SsInventory`
     *  instances in one model then collide on it — KSL requires element names to be unique
     *  model-wide — so this class could not be used twice. The DSL reads as though the name were
     *  local, which is the trap: `decisionElement("Review")` looks like a label, while
     *  `TWResponse(this, name = …)` visibly demands an identifier.
     */
    private val declaration: DecisionElementBuilder.() -> Unit = {
        // Declaration order is the vector order (§4.2.3).
        observe("$name:Position") { inventoryPosition }                         // 0
        observe("$name:ExpectedDemand") { expectedDemandOverProtection }        // 1
        observe("$name:DemandSinceReview") { demandSinceLastReview }            // 2
        // Placing an order is a TRANSACTION. Doing nothing is ordering zero — an action
        // with a declared amount, not an abstention (§8.2.3). Declaring it as a SETTING is
        // the mistake the old design encouraged, and `declareOrderAsSetting` reproduces it.
        lever(
            this@SsInventory, limits = 0..maxOrder, alias = "$name:OrderQty",
            neutral = if (declareOrderAsSetting) Neutral.Current { lastOrderQuantity }
                      else Neutral.Value(0.0)
        ) { q -> placeOrder(q.toInt()) }
        decisionSink?.let { factory -> captureTo(factory) }
        policy = NeutralPolicy
    }

    /**
     *  The reviewer, when this instance is wired the packaged way.
     *
     *  It is named apart from the element and hands the element the name it always had, so that the
     *  two wirings are indistinguishable from outside: same element name, same control key for the
     *  cap, same provenance in a stored trajectory. Only the review period's key differs, because
     *  under the composite the period belongs to the reviewer -- which is where it belongs.
     */
    private val reviews: PeriodicDecisionElement? =
        if (composedReview) {
            PeriodicDecisionElement(
                this, reviewPeriod, name = "${this.name}:Reviewer",
                elementName = "${this.name}:Review", declaration = declaration
            )
        } else null

    val review: DecisionElement =
        reviews?.element ?: decisionElement("${this.name}:Review", declaration).reviewEvery(this, reviewPeriod)

    override fun initialize() {
        lastOrderQuantity = 0.0
        demandCountAtLastReview = 0.0
    }

    override fun warmUp() {
        demandCountAtLastReview = 0.0
    }
}

/**
 * The classic (s, S) rule. When the inventory position falls to the reorder point [s] or
 * below, order enough to bring it up to [bigS]; otherwise order nothing.
 *
 * **[positionIndex] exists because the design has no way for a rule to say which
 * observation it wants.** This rule needs one number — the inventory position — out of a
 * positional vector whose other entries belong to other rules. It cannot ask for it by
 * meaning, so whoever wires a model to this rule must supply the index by hand, and a
 * wrong index compiles, binds, and produces plausible nonsense. See §8.2.6.
 *
 * `configure` can therefore only check that the vector is long enough. Checking
 * `observations.size == 1`, which an earlier version did, broke the moment the element
 * declared an observation for a different rule.
 */
class SsPolicy(
    private val s: Int,
    private val bigS: Int,
    private val positionIndex: Int = 0
) : ShapeAwarePolicyIfc {

    init { require(s < bigS) { "The reorder point s=$s must be below the order-up-to level S=$bigS" } }

    override fun configure(surface: DecisionSurfaceDescriptor) {
        require(surface.levers.size == 1) {
            "SsPolicy orders one quantity; the element declares ${surface.levers.size} levers."
        }
        require(positionIndex < surface.observations.size) {
            "SsPolicy was told to read observation $positionIndex, but the element declares " +
                "only ${surface.observations.size}: ${surface.observations.map { it.name }}"
        }
    }

    override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
        val position = observation[positionIndex]
        val order = if (position <= s) bigS - position else 0.0
        val bounds = ctx.leverBounds[0]
        return doubleArrayOf(max(0.0, order).coerceIn(bounds.start, bounds.endInclusive))
    }

    override fun toString(): String = "(s=$s, S=$bigS)"
}

/**
 * A dynamic (s, S) rule: the same policy form, with the two parameters recomputed at every
 * review from the demand the next protection interval is expected to bring.
 *
 * ```
 *   s = a * mu           safety scales with the demand to be covered
 *   Q = b * sqrt(mu)     order size scales as EOQ does, with the square root of the rate
 *   S = s + Q
 * ```
 *
 * where `mu` is the expected demand over the lead time plus the review period. With a
 * constant rate this reduces to a fixed (s, S) — which is what makes it a fair comparison
 * against the best static rule rather than a different kind of animal.
 *
 * It is **anticipative**: `mu` comes from the model's known rate function, so the rule sees
 * the season coming. A reactive variant reading observed demand instead is a one-word
 * change at the call site, and §8.2.6 measures both.
 */
class DynamicSsPolicy(
    private val a: Double,
    private val b: Double,
    private val positionIndex: Int = 0,
    private val demandIndex: Int = 1
) : ShapeAwarePolicyIfc {

    override fun configure(surface: DecisionSurfaceDescriptor) {
        require(surface.levers.size == 1) {
            "DynamicSsPolicy orders one quantity; the element declares ${surface.levers.size} levers."
        }
        val n = surface.observations.size
        require(positionIndex < n && demandIndex < n) {
            "DynamicSsPolicy reads observations $positionIndex and $demandIndex, but the " +
                "element declares only $n: ${surface.observations.map { it.name }}"
        }
    }

    override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
        val position = observation[positionIndex]
        val mu = max(observation[demandIndex], 0.0)
        val s = a * mu
        val bigS = s + b * sqrt(mu)
        // The lever's domain is INTEGER, so the rule must round. The declaration catches
        // this if it forgets — which it did, on the first run.
        val order = if (position <= s) Math.rint(bigS - position) else 0.0
        val bounds = ctx.leverBounds[0]
        return doubleArrayOf(max(0.0, order).coerceIn(bounds.start, bounds.endInclusive))
    }

    override fun toString(): String = "dynamic(a=%.2f, b=%.2f)".format(a, b)
}

/**
 * Order nothing, ever. The do-nothing reference arm every benchmark needs (§4.1.10).
 *
 * **This class no longer needs to exist**, and that is the point of §8.2.3. It was written by
 * hand because `HoldCurrentPolicy` could not run on a transactional model at all — the
 * Level-2 baseline of §6.2 was unavailable for the canonical MDP example, and every
 * transactional model would have had to write its own arm. With a declared neutral,
 * `NeutralPolicy` does this generically. It is kept only so the benchmarks that name it keep
 * comparing what they compared before; `SsInventoryBenchmarkTest` asserts the two agree.
 */
object OrderNothingPolicy : PolicyIfc {
    override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray =
        doubleArrayOf(0.0)
}

/**
 * Average cost per unit time, assembled from the model's own responses. Doing this by
 * hand is what a reward estimand (§4.2.5) automates — M1 step 7b built it — and note that all
 * three reward kinds appear here.
 */
data class InventoryCost(
    val orderSetup: Double, val purchase: Double,
    val holding: Double, val shortage: Double
) {
    val total: Double get() = orderSetup + purchase + holding + shortage
}

fun costOf(
    model: Model, prefix: String,
    K: Double = 32.0, c: Double = 3.0, h: Double = 1.0, p: Double = 5.0
): InventoryCost {
    val horizon = model.lengthOfReplication - model.lengthOfReplicationWarmUp
    fun counter(n: String) = model.counters.first { it.name == "$prefix:$n" }.acrossReplicationStatistic.average
    fun response(n: String) = model.responses.first { it.name == "$prefix:$n" }.acrossReplicationStatistic.average
    return InventoryCost(
        orderSetup = K * counter("OrderCount") / horizon,
        purchase = c * counter("UnitsOrdered") / horizon,
        holding = h * response("OnHand"),
        shortage = p * response("BackOrder")
    )
}
