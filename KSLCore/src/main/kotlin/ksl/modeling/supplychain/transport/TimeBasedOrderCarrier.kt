package ksl.modeling.supplychain.transport

import ksl.modeling.supplychain.*

import ksl.modeling.variable.RandomVariable
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * Order carrier that delays each shipment by a per-[OrderSenderIfc]
 * random transport time. Senders without a configured time are either
 * rejected with [NoCarrierOptionException] or, if
 * [immediateTransportFlag] is true, shipped and delivered immediately
 * (zero simulated time).
 *
 * Java has no built-in in-transit statistics on this class — the port
 * preserves that.
 *
 * @param parent the parent model element
 * @param name optional model-element name
 *
 * See `sc.transportlayer.TimeBasedOrderCarrier`
 */
open class TimeBasedOrderCarrier @JvmOverloads constructor(
    parent: ModelElement,
    name: String? = null,
) : ModelElement(parent, name), OrderCarrierIfc {

    private val myTransportTimes: MutableMap<OrderSenderIfc, RandomVariable> =
        linkedMapOf()

    /**
     * When true, orders whose sender has no configured transport time
     * are shipped and delivered immediately (zero simulated time)
     * instead of throwing [NoCarrierOptionException]. Default false.
     */
    var immediateTransportFlag: Boolean = false

    private val deliveryAction = DeliveryAction()

    /** True if a transport time has been configured for [sender]. */
    fun contains(sender: OrderSenderIfc): Boolean =
        sender in myTransportTimes

    override fun canShip(order: SupplyChainModel.Order): Boolean =
        order.orderSender?.let { it in myTransportTimes } == true

    override fun transportOrder(order: SupplyChainModel.Order) {
        val sender = order.orderSender
            ?: error("the sender on the order was null")

        val rv = myTransportTimes[sender]
        if (rv == null) {
            if (!immediateTransportFlag) {
                throw NoCarrierOptionException(
                    "the sender is not valid for this carrier",
                )
            }
            order.ship()
            order.deliver()
        } else {
            val t = rv.value
            deliveryAction.schedule(t, message = order)
            order.ship()
        }
    }

    /**
     * Register (or replace) the transport-time distribution used for
     * [sender]. The supplied [distribution] is wrapped in a new
     * [RandomVariable] (per porting plan §4.1) the first time the
     * sender is added; subsequent calls swap the underlying source.
     */
    fun setTransportTime(
        sender: OrderSenderIfc,
        distribution: RVariableIfc,
    ) {
        val existing = myTransportTimes[sender]
        if (existing != null) {
            existing.initialRandomSource = distribution
        } else {
            myTransportTimes[sender] = RandomVariable(this, distribution)
        }
    }

    private inner class DeliveryAction : EventAction<SupplyChainModel.Order>() {
        override fun action(event: KSLEvent<SupplyChainModel.Order>) {
            event.message!!.deliver()
        }
    }
}
