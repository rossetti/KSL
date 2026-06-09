package ksl.modeling.supplychain.inventory

import ksl.modeling.supplychain.*

import ksl.modeling.elements.EventGenerator
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * Generates orders at scheduled intervals and routes them to an order
 * filler. The order's content is produced by an [OrderCreatorIfc]; the
 * filler is located via an [OrderFillerFinderIfc] or set directly via
 * [orderFiller].
 *
 * @param supplyChainModel the supply-chain model whose order/demand
 *        factories are used
 * @param orderCreator strategy that builds the order at each event
 * @param timeUntilFirstRV time until the first generation event
 * @param timeBtwEventsRV time between subsequent generation events
 * @param maxNumberOfEvents maximum number of events to generate
 * @param timeOfTheLastEvent simulation time at which to stop generating
 * @param name optional model-element name
 *
 * @see sc.inventorylayer.OrderGenerator
 */
open class OrderGenerator @JvmOverloads constructor(
    val supplyChainModel: SupplyChainModel,
    var orderCreator: OrderCreatorIfc? = null,
    timeUntilFirstRV: RVariableIfc,
    timeBtwEventsRV: RVariableIfc,
    maxNumberOfEvents: Long = Long.MAX_VALUE,
    timeOfTheLastEvent: Double = Double.POSITIVE_INFINITY,
    name: String? = null,
) : EventGenerator(
    supplyChainModel, null, timeUntilFirstRV, timeBtwEventsRV,
    maxNumberOfEvents, timeOfTheLastEvent, name,
), OrderSenderIfc {

    /** Direct filler override; if set, used instead of [orderFillerFinder]. */
    var orderFiller: OrderFillerIfc? = null

    /** Finder used to locate a filler for each generated order. */
    var orderFillerFinder: OrderFillerFinderIfc? = null

    override fun mightRequest(type: ItemType): Boolean =
        orderCreator?.createsType(type) ?: false

    private val orderListener =
        OrderStateChangeListener { o, _, to ->
            when (to.stateId) {
                OrderStateId.Received -> orderReceived(o)
                OrderStateId.Rejected -> orderRejected(o)
                OrderStateId.Delivered -> orderDelivered(o)
                else -> {}
            }
        }

    /** Called on each generator event; if a creator is set, builds, fills, sends. */
    final override fun generate() {
        val creator = orderCreator ?: return
        val order = creator.createOrder() ?: return
        setFillerForOrder(order)
        order.addStateChangeListener(orderListener)
        sendOrder(order)
    }

    protected open fun setFillerForOrder(order: SupplyChainModel.Order) {
        val filler = orderFiller
            ?: orderFillerFinder?.findOrderFiller(order)
            ?: error("no order filler found for order ${order.name}")
        order.setFiller(filler)
    }

    protected open fun sendOrder(order: SupplyChainModel.Order) {
        order.orderSender = this
        order.sent()
        order.filler!!.receive(order)
    }

    /**
     * Called when this generator's order reaches the
     * [SupplyChainModel.orderReceived] state. Default: asks the filler
     * to fill the order.
     */
    protected open fun orderReceived(order: SupplyChainModel.Order) {
        order.filler!!.fill(order)
    }

    /**
     * Called when this generator's order is rejected. Default: throws
     * [IllegalStateException]. Subclasses may override to handle.
     */
    protected open fun orderRejected(order: SupplyChainModel.Order) {
        error("Order ${order.name} was rejected")
    }

    /** Called when this generator's order is delivered. Default: no-op. */
    protected open fun orderDelivered(order: SupplyChainModel.Order) {
        // override to react
    }
}
