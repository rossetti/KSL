package ksl.modeling.supplychain.inventory

import ksl.modeling.supplychain.*

import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.simulation.ModelElement

/**
 * Abstract base for a warehouse that fills incoming orders by routing
 * each demand on the order to a per-item-type inventory. Replenishment
 * orders are sent outward via [replenishmentOrderFiller] or
 * [replenishmentOrderFillerFinder].
 *
 * Concrete subclasses implement [fill] (queue or process the order),
 * [orderFilled] (post-fill shipping), and [replenishmentOrderDelivered]
 * (cascade delivery to demands on a replenishment order).
 *
 * @see sc.inventorylayer.WarehouseAbstract
 */
abstract class WarehouseAbstract @JvmOverloads constructor(
    parent: ModelElement,
    initialAvailability: Boolean = true,
    name: String? = null,
) : OrderFillerAbstract(parent, initialAvailability, name), OrderSenderIfc {

    /** Whether outgoing replenishment orders allow backlogging. */
    var permitBackLogging: Boolean = true

    /** Optional shipper for filled orders. */
    var orderShipper: OrderCarrierIfc? = null

    /** Optional hook for individual filled demands inside an order. */
    var demandOnOrderFilledHook: ((SupplyChainModel.Demand) -> Unit)? = null

    /**
     * Optional replenishment requester. If set, [requestReplenishment]
     * delegates entirely to it; otherwise the default behavior is to
     * build a single-demand order and dispatch.
     */
    var replenishmentRequester: ReplenishmentRequesterIfc? = null

    /** Direct order filler for replenishment orders. */
    var replenishmentOrderFiller: OrderFillerIfc? = null

    /** Finder for replenishment order fillers. */
    var replenishmentOrderFillerFinder: OrderFillerFinderIfc? = null

    private val myCountReplenishmentOrders: Counter =
        Counter(this, name = "${this.name}#ReplenishmentOrders")

    /** Read-only view of the replenishment-order counter. */
    val replenishmentOrderCounter: CounterCIfc get() = myCountReplenishmentOrders

    private val orderFilledListener =
        OrderStateChangeListener { o, _, to ->
            if (to.stateId === OrderStateId.Filled) orderFilled(o)
        }

    private val replenishmentOrderListener =
        OrderStateChangeListener { o, _, to ->
            when (to.stateId) {
                OrderStateId.Received -> replenishmentOrderReceived(o)
                OrderStateId.Rejected -> replenishmentOrderRejected(o)
                OrderStateId.Delivered -> replenishmentOrderDelivered(o)
                else -> {}
            }
        }

    /** Hooks the warehouse's filled-order listener onto [order]. */
    protected fun attachOrderFilledListener(order: SupplyChainModel.Order) {
        order.addStateChangeListener(orderFilledListener)
    }

    /**
     * Registers a per-demand listener on every demand of [order] that
     * fires when the demand reaches FILLED. Replaces Java's
     * `order.setDemandOnOrderFilledListener`.
     */
    protected fun attachDemandOnOrderFilledListeners(order: SupplyChainModel.Order) {
        for (d in order.demands) {
            d.addStateChangeListener { demand, _, to ->
                if (to.stateId === DemandStateId.Filled) {
                    val hook = demandOnOrderFilledHook
                    if (hook != null) hook(demand) else demandOnOrderFilled(demand)
                }
            }
        }
    }

    override fun canFillItemType(demand: SupplyChainModel.Demand): Boolean {
        val finder = demandFillerFinder ?: return false
        val filler = finder.findDemandFiller(demand) ?: return false
        return filler.canFillItemType(demand)
    }

    override fun negotiate(order: SupplyChainModel.Order): OrderMessageIfc {
        val om = OrderMessage(this, time)
        val finder = demandFillerFinder
        for (d in order.demands) {
            val filler = finder?.findDemandFiller(d)
            om.add(filler?.negotiate(d))
        }
        return om
    }

    abstract override fun fill(order: SupplyChainModel.Order)

    /** Default rejection behavior for replenishment orders is to throw. */
    protected open fun replenishmentOrderRejected(order: SupplyChainModel.Order) {
        error("$name: replenishment order ${order.name} was rejected")
    }

    /** Subclass hook fired when an incoming order reaches FILLED. */
    protected abstract fun orderFilled(order: SupplyChainModel.Order)

    /** Subclass hook fired when an outgoing replenishment order is delivered. */
    protected abstract fun replenishmentOrderDelivered(order: SupplyChainModel.Order)

    /**
     * Default hook for individual filled demands inside an order.
     * Overridden by subclasses or via [demandOnOrderFilledHook].
     */
    protected open fun demandOnOrderFilled(demand: SupplyChainModel.Demand) {}

    /**
     * Used as a [ReplenishmentRequesterIfc] for held inventories.
     * Packages the single replenishment [demand] into an order and
     * dispatches it through the configured replenishment filler/finder.
     */
    open fun requestReplenishment(
        inventory: Inventory,
        demand: SupplyChainModel.Demand,
    ) {
        val custom = replenishmentRequester
        if (custom != null) {
            custom.requestReplenishment(inventory, demand)
            return
        }
        val sc = findEnclosingSupplyChainModel()
        val order = sc.createOrder(allowBackLogging = permitBackLogging)
        demand.setAllowBackLogging(permitBackLogging)
        order.addDemand(demand)
        setFillerForReplenishmentOrder(order)
        order.addStateChangeListener(replenishmentOrderListener)
        sendReplenishmentOrder(order)
    }

    private fun sendReplenishmentOrder(order: SupplyChainModel.Order) {
        myCountReplenishmentOrders.increment()
        order.orderSender = this
        order.sent()
        order.filler!!.receive(order)
    }

    private fun setFillerForReplenishmentOrder(order: SupplyChainModel.Order) {
        val filler = replenishmentOrderFiller
            ?: replenishmentOrderFillerFinder?.findOrderFiller(order)
            ?: error("$name: no replenishment order filler for ${order.name}")
        order.setFiller(filler)
    }

    /** Default behavior on a received replenishment order is to ask the filler to fill it. */
    protected open fun replenishmentOrderReceived(order: SupplyChainModel.Order) {
        order.filler!!.fill(order)
    }

    private fun findEnclosingSupplyChainModel(): SupplyChainModel {
        var p: ModelElement? = this.myParentModelElement
        while (p != null) {
            if (p is SupplyChainModel) return p
            p = p.myParentModelElement
        }
        error("$name is not parented under a SupplyChainModel")
    }
}
