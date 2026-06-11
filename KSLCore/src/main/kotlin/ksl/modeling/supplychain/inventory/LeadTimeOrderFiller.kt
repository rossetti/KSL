package ksl.modeling.supplychain.inventory

import ksl.modeling.supplychain.*

import ksl.modeling.variable.TWResponse
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * Concrete [OrderFillerAbstract] that fills incoming orders by routing
 * each demand on the order through an internal [LeadTimeDemandFiller].
 * Filled orders are shipped via [orderShipper] (if set) or immediately
 * transition through ship -> deliver.
 *
 * @param parent the parent model element (typically a [SupplyChainModel])
 * @param initialAvailability availability at the start of each replication
 * @param name optional model-element name
 *
 * See `sc.inventorylayer.LeadTimeOrderFiller`
 */
open class LeadTimeOrderFiller @JvmOverloads constructor(
    parent: ModelElement,
    initialAvailability: Boolean = true,
    name: String? = null,
) : OrderFillerAbstract(parent, initialAvailability, name) {

    /** Optional shipper for filled orders; null → zero-delay ship+deliver. */
    var orderShipper: OrderCarrierIfc? = null

    /** Time-weighted "number of orders currently in process" statistic. */
    protected val myNumOnOrder: TWResponse =
        TWResponse(this, name = "${this.name}#OnOrder")

    /** Inner demand-filler used for every demand on every incoming order. */
    protected val myLeadTimeDemandFiller: InnerDemandFiller =
        InnerDemandFiller(this, "${this.name}InnerLTDF")

    init {
        // Every demand on incoming orders is routed to the inner filler.
        demandFillerFinder = myLeadTimeDemandFiller
    }

    /** Register a lead-time distribution; delegates to the inner filler. */
    fun addLeadTime(itemType: ItemType, distribution: RVariableIfc) {
        myLeadTimeDemandFiller.addLeadTime(itemType, distribution)
    }

    override fun canFillItemType(demand: SupplyChainModel.Demand): Boolean =
        myLeadTimeDemandFiller.canFillItemType(demand)

    override fun fill(order: SupplyChainModel.Order) {
        myNumOnOrder.increment()
        order.addStateChangeListener(orderFilledListener)
        placeOrderInProcessState(order)
        askOrderToBeginProcessingDemands(order)
    }

    /** Java's negotiate is a TODO stub returning null; preserved. */
    override fun negotiate(order: SupplyChainModel.Order): OrderMessageIfc? = null

    override fun toString(): String =
        "$name Number on Order: ${myNumOnOrder.value.toInt()}"

    /**
     * Fires when an order reaches the [SupplyChainModel.orderFilled] state.
     * Decrements [myNumOnOrder] and either hands off to [orderShipper] or
     * transitions through ship -> deliver with no delay.
     */
    private val orderFilledListener =
        OrderStateChangeListener { o, _, to ->
            if (to.stateId === OrderStateId.Filled) {
                myNumOnOrder.decrement()
                val shipper = orderShipper
                if (shipper != null) {
                    shipper.transportOrder(o)
                } else {
                    o.ship()
                    o.deliver()
                }
            }
        }

    /**
     * Internal demand-filler used to route demands on incoming orders.
     * Extends [LeadTimeDemandFiller] and also serves as the
     * [DemandFillerFinderIfc] (returns `this` for every demand).
     *
     * Diverges from the parent's [LeadTimeDemandFiller.fillDemand] in one
     * way: does not register the parent's `filledListener`. The order,
     * not the filler, handles post-fill behavior via the order-level
     * filled listener.
     *
     * (The Java `InnerDemandFiller` also skipped incrementing the
     * parent's "in process" counters — a Java oversight that JSL tolerated
     * because its `TimeWeighted` accepted negative values. KSL's
     * `TWResponse` rejects negative values, so the counters are
     * incremented here to keep them balanced with the decrement that
     * runs inside the inherited [LeadTimeAction.action].)
     */
    protected open inner class InnerDemandFiller(
        parent: ModelElement,
        name: String,
    ) : LeadTimeDemandFiller(parent, name), DemandFillerFinderIfc {

        override fun fillDemand(demand: SupplyChainModel.Demand) {
            demand.process(this)
            val type = demand.itemType
            val rv = myLeadTimes[type]
                ?: error("no lead time configured for $type on ${this.name}")
            val leadTime = rv.value
            // TWResponse-balance note (see docs/supply-chain-architecture.md §7):
            // these increments are NOT cosmetic — the inherited LeadTimeAction
            // does matching decrements when the lead-time event fires. JSL's
            // TimeWeighted tolerated the asymmetry; KSL's TWResponse rejects
            // it with [0, ∞) domain enforcement. Do not "simplify" by removing
            // either side of the pair: the counts are real (number-in-process)
            // and must be balanced.
            myAggregateNumInProcess.increment()
            myNumInProcessByType[type]?.increment()
            leadTimeEventAction.schedule(leadTime, message = demand)
        }

        override fun findDemandFiller(
            demand: SupplyChainModel.Demand,
        ): DemandFillerIfc = this
    }
}
