package ksl.examples.general.models.inventory

import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * A [DemandCarrierIfc] [ModelElement] that ships filled demand to its destination after a random,
 * per-destination shipping time. Each [InventoryReceiverIfc] destination is registered with its own
 * shipping-time random variable; [transport] schedules delivery that far into the future. A
 * destination with no registered time is delivered immediately when [immediateDeliveryAllowed] is
 * true. Used as the DC-to-base transport link in [TwoEchelonModel].
 */
class TimeBasedDemandCarrier(
    parent: ModelElement,
    shippingTimesMap: Map<InventoryReceiverIfc, RVariableIfc>? = null,
    name: String? = null
) : ModelElement(parent, name), DemandCarrierIfc {

    constructor(
        parent: ModelElement,
        destination: InventoryReceiverIfc,
        shippingTime: RVariableIfc,
        name: String? = null
    ) : this(
        parent, mapOf(destination to shippingTime), name
    )

    var immediateDeliveryAllowed = true

    private val myShippingTimes = mutableMapOf<InventoryReceiverIfc, RVariableIfc>()
    val shippingTimes: Map<InventoryReceiverIfc, RVariableIfc>
        get() = myShippingTimes

    init {
        if (shippingTimesMap != null) {
            myShippingTimes.putAll(shippingTimesMap)
        }
    }

    fun addShippingTime(demandReceiver: InventoryReceiverIfc, shippingTime: RVariableIfc) {
        myShippingTimes[demandReceiver] = shippingTime
    }

    override fun transport(demand: DemandCreator.Demand) {
        val destination = demand.filledDemandReceiver
        val rv = myShippingTimes[destination]
        if (rv != null) {
            schedule(this::endTransport, rv.value, message = demand)
        } else {
            if (immediateDeliveryAllowed) {
                demand.filledDemandReceiver.receiveInventory(demand)
            } else {
                require(myShippingTimes.containsKey(destination)) { "The carrier does not ship to the destination and immediate fill is not true" }
            }
        }
    }

    private fun endTransport(event: KSLEvent<DemandCreator.Demand>) {
        val demand = event.message!!
        demand.filledDemandReceiver.receiveInventory(demand)
    }

}