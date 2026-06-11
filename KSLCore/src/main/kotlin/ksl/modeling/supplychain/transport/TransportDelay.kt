package ksl.modeling.supplychain.transport

import ksl.modeling.supplychain.*

import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * Models a transport delay: when [startDelay] is called for a demand,
 * the demand is placed in SHIPPED, a delay is scheduled, and at the
 * end of the delay the demand is placed in DELIVERED. Per-shipment
 * statistics — transit time, amount in transit, number of shipments —
 * are collected automatically.
 *
 * @param parent the parent model element
 * @param transportTime caller-supplied transport-time distribution.
 *        Defaults to [ConstantRV.ZERO] for zero-delay transport.
 * @param name optional model-element name
 *
 * See `sc.transportlayer.TransportDelay`
 */
open class TransportDelay @JvmOverloads constructor(
    parent: ModelElement,
    transportTime: RVariableIfc = ConstantRV.ZERO,
    name: String? = null,
) : ModelElement(parent, name) {

    private val myTransportTime: RandomVariable =
        RandomVariable(this, transportTime, "${this.name} : RV")

    private val myTimeInTransit: Response =
        Response(this, name = "${this.name} : Transit Time")

    private val myAmtInTransit: TWResponse =
        TWResponse(this, name = "${this.name} : Amount In Transit")

    private val myNumShipments: Counter =
        Counter(this, name = "${this.name} : #Shipments")

    /** Read-only view of the transit-time response. */
    val transitTimeResponse: ResponseCIfc get() = myTimeInTransit

    /** Read-only view of the amount-in-transit response. */
    val numInTransitResponse: TWResponseCIfc get() = myAmtInTransit

    /** Read-only view of the shipment counter. */
    val numShipmentsCounter: CounterCIfc get() = myNumShipments

    private val deliveryAction = DeliveryAction()

    /** Swap the underlying transport-time source between replications. */
    fun setInitialTransportTime(source: RVariableIfc) {
        myTransportTime.initialRandomSource = source
    }

    /** Sample a single transport-time value. */
    fun sampleTransportDelay(): Double = myTransportTime.value

    /**
     * Place [demand] in transit. Increments the in-transit counter,
     * transitions the demand to SHIPPED, and schedules the delivery
     * event at the sampled delay.
     */
    open fun startDelay(demand: SupplyChainModel.Demand) {
        myAmtInTransit.increment(demand.amountFilled.toDouble())
        val t = sampleTransportDelay()
        deliveryAction.schedule(t, message = demand)
        demand.ship()
    }

    private inner class DeliveryAction : EventAction<SupplyChainModel.Demand>() {
        override fun action(event: KSLEvent<SupplyChainModel.Demand>) {
            val d = event.message!!
            myAmtInTransit.decrement(d.amountFilled.toDouble())
            myNumShipments.increment()
            myTimeInTransit.value = time - d.timeShipped
            d.deliver()
        }
    }

    override fun toString(): String = name
}
