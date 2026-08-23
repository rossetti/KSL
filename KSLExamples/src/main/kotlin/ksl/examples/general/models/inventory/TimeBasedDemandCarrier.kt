package ksl.examples.general.models.inventory

import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.RandomVariableCIfc
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * A [DemandCarrierIfc] [ModelElement] that ships filled demand to its destination after a random,
 * per-destination shipping time. Each [InventoryReceiverIfc] destination is registered with its own
 * shipping-time random variable; [transport] schedules delivery that far into the future. A
 * destination with no registered time is delivered immediately when [immediateDeliveryAllowed] is
 * true. Used as the DC-to-base transport link in [TwoEchelonModel].
 *
 * Each shipping time is wrapped in a [RandomVariable] rather than held as the supplied
 * [RVariableIfc]. That wrapping is what makes the carrier reproducible, and it is not optional:
 * a [RandomVariable] re-homes its source onto the owning model's own stream provider and is reset
 * by the model between replications and runs, while a bare [RVariableIfc] keeps the stream it was
 * constructed with -- one drawn from the global default provider and shared by every model built
 * in the JVM. Held bare, this carrier's stream is never rewound and is drawn from concurrently by
 * every model running at once, so the same design yields a slightly different answer on every run.
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

    private val myShippingTimes = mutableMapOf<InventoryReceiverIfc, RandomVariable>()
    val shippingTimes: Map<InventoryReceiverIfc, RandomVariableCIfc>
        get() = myShippingTimes

    init {
        if (shippingTimesMap != null) {
            for ((demandReceiver, shippingTime) in shippingTimesMap) {
                addShippingTime(demandReceiver, shippingTime)
            }
        }
    }

    fun addShippingTime(demandReceiver: InventoryReceiverIfc, shippingTime: RVariableIfc) {
        myShippingTimes[demandReceiver] = RandomVariable(this, shippingTime)
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