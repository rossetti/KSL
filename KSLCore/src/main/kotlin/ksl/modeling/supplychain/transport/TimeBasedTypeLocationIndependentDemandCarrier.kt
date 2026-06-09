package ksl.modeling.supplychain.transport

import ksl.modeling.supplychain.*

import ksl.modeling.variable.RandomVariable
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * Demand carrier that delays each shipment by a transport time keyed
 * on both the demand's [DemandSenderIfc] and its [ItemType]. Senders
 * (or sender/type pairs) without a configured time are either rejected
 * with [NoCarrierOptionException] or, if [immediateTransportFlag] is
 * true, shipped and delivered immediately (zero simulated time).
 *
 * Java has no built-in transit statistics on this class — the port
 * preserves that (the Java source marks them as TODO).
 *
 * @param parent the parent model element
 * @param name optional model-element name
 *
 * @see sc.transportlayer.TimeBasedTypeLocationIndependentDemandCarrier
 */
open class TimeBasedTypeLocationIndependentDemandCarrier
@JvmOverloads constructor(
    parent: ModelElement,
    name: String? = null,
) : DemandCarrierAbstract(parent, name) {

    private val myTransportTimes:
        MutableMap<DemandSenderIfc, MutableMap<ItemType, RandomVariable>> =
        linkedMapOf()

    /**
     * When true, demands whose (sender, item-type) pair has no
     * configured transport time are shipped and delivered immediately
     * (zero simulated time) instead of throwing
     * [NoCarrierOptionException]. Default false.
     */
    var immediateTransportFlag: Boolean = false

    private val deliveryAction = DeliveryAction()

    /** True if any transport time has been configured for [sender]. */
    fun contains(sender: DemandSenderIfc): Boolean =
        sender in myTransportTimes

    /** True if a transport time has been configured for the pair. */
    fun contains(sender: DemandSenderIfc, type: ItemType): Boolean =
        myTransportTimes[sender]?.containsKey(type) == true

    /**
     * Diverges from the Java parity: the Java [canShip] crashes with
     * an NPE when the sender is unknown (it dereferences a null map
     * from `myTransportTimes.get(sender)`). The Kotlin port returns
     * false in that case.
     */
    override fun canShip(demand: SupplyChainModel.Demand): Boolean {
        if (demand.filler == null) return false
        val sender = demand.demandSender ?: return false
        return contains(sender, demand.itemType)
    }

    override fun transportDemand(demand: SupplyChainModel.Demand) {
        val sender = demand.demandSender
            ?: error("the sender on the demand was null")

        val rv = myTransportTimes[sender]?.get(demand.itemType)
        if (rv == null) {
            if (!immediateTransportFlag) {
                throw NoCarrierOptionException(
                    "the sender is not valid for this carrier",
                )
            }
            demand.ship()
            demand.deliver()
        } else {
            val t = rv.value
            deliveryAction.schedule(t, message = demand)
            demand.ship()
        }
    }

    /**
     * Register (or replace) the transport-time distribution used for
     * the [sender]/[type] pair. The supplied [distribution] is wrapped
     * in a new [RandomVariable] (per porting plan §4.1) the first time
     * the pair is added; subsequent calls swap the underlying source.
     */
    fun setTransportTime(
        sender: DemandSenderIfc,
        type: ItemType,
        distribution: RVariableIfc,
    ) {
        val byType = myTransportTimes.getOrPut(sender) { linkedMapOf() }
        val existing = byType[type]
        if (existing != null) {
            existing.initialRandomSource = distribution
        } else {
            byType[type] = RandomVariable(this, distribution)
        }
    }

    private inner class DeliveryAction : EventAction<SupplyChainModel.Demand>() {
        override fun action(event: KSLEvent<SupplyChainModel.Demand>) {
            event.message!!.deliver()
        }
    }
}
