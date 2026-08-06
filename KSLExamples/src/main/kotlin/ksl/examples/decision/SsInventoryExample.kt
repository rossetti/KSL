package ksl.examples.decision

import ksl.modeling.decision.*
import ksl.modeling.decision.descriptor.DecisionSurfaceDescriptor
import ksl.modeling.variable.Counter
import ksl.modeling.variable.TWResponse
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.RVariableIfc
import kotlin.math.max
import kotlin.math.min

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
    private val demandTBA: RVariableIfc = ExponentialRV(1.0, streamNum = 11),
    maxOrder: Int = 200,
    /**
     * Whether the order-quantity lever declares a `read`. There is no natural answer:
     * an order quantity has no "current value", so the closest thing is the size of the
     * last order placed. Supplying it is what §8.2.3 is about — it makes the lever look
     * like a setting to machinery that assumes settings.
     */
    leverHasReader: Boolean = false,
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
    private inner class DemandArrival : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            if (myOnHand.value >= 1.0) myOnHand.decrement(1.0) else myBackorder.increment(1.0)
            schedule(demandTBA.value)
        }
    }
    private val demandArrival = DemandArrival()

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
        if (quantity <= 0) return
        myOrderCount.increment()
        myUnitsOrdered.increment(quantity.toDouble())
        myOnOrder.increment(quantity.toDouble())
        orderArrival.schedule(leadTime, message = quantity)
    }

    // ---- The decision -----------------------------------------------------------
    val review: DecisionElement = decisionElement("Review") {
        observe("$name:Position") { inventoryPosition }
        lever(
            this@SsInventory, limits = 0..maxOrder, alias = "$name:OrderQty",
            read = if (leverHasReader) ({ lastOrderQuantity }) else null
        ) { q -> placeOrder(q.toInt()) }
        every(reviewPeriod)
        policy = OrderNothingPolicy
    }

    override fun initialize() {
        lastOrderQuantity = 0.0
        demandArrival.schedule(demandTBA.value)
    }
}

/**
 * The classic (s, S) rule. When the inventory position falls to the reorder point [s] or
 * below, order enough to bring it up to [bigS]; otherwise order nothing.
 *
 * `configure` checks what cannot change afterwards — one lever, one observation — and
 * checks `s < S`, which is a property of the rule and not of the element.
 */
class SsPolicy(private val s: Int, private val bigS: Int) : ShapeAwarePolicyIfc {

    init { require(s < bigS) { "The reorder point s=$s must be below the order-up-to level S=$bigS" } }

    override fun configure(surface: DecisionSurfaceDescriptor) {
        require(surface.levers.size == 1) {
            "SsPolicy orders one quantity; the element declares ${surface.levers.size} levers."
        }
        require(surface.observations.size == 1) {
            "SsPolicy reads one inventory position; the element declares " +
                "${surface.observations.size} observations."
        }
    }

    override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
        val position = observation[0]
        val order = if (position <= s) bigS - position else 0.0
        val bounds = ctx.leverBounds[0]
        return doubleArrayOf(max(0.0, order).coerceIn(bounds.start, bounds.endInclusive))
    }

    override fun toString(): String = "(s=$s, S=$bigS)"
}

/** Order nothing, ever. The do-nothing reference arm every benchmark needs (§4.1.10). */
object OrderNothingPolicy : PolicyIfc {
    override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray =
        doubleArrayOf(0.0)
}

/**
 * Average cost per unit time, assembled from the model's own responses. Doing this by
 * hand is what a reward estimand (§4.2.5, M2) would automate — and note that all three
 * reward kinds appear here.
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
