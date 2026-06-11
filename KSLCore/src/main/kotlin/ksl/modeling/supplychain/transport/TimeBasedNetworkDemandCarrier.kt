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
 * Demand carrier that maps each (filler, sender) edge to its own
 * transport-time distribution. A single demand filler can serve many
 * senders; a sender can be served by multiple fillers — but each
 * (filler, sender) pair has at most one configured time.
 *
 * Demands whose (filler, sender) pair has no configured time are
 * either rejected with [NoCarrierOptionException] or, if
 * [immediateTransportFlag] is true, shipped and delivered immediately
 * (zero simulated time).
 *
 * @param parent the parent model element
 * @param name optional model-element name
 *
 * See `sc.transportlayer.TimeBasedNetworkDemandCarrier`
 */
open class TimeBasedNetworkDemandCarrier @JvmOverloads constructor(
    parent: ModelElement,
    name: String? = null,
) : DemandCarrierAbstract(parent, name) {

    private val myTransportTimes:
        MutableMap<DemandFillerIfc, MutableMap<DemandSenderIfc, RandomVariable>> =
        linkedMapOf()

    /** Shared zero-delay random variable reused across (filler, sender) edges. */
    private val myNoDelayRV: RandomVariable =
        RandomVariable(this, ConstantRV.ZERO)

    /**
     * When true, demands whose (filler, sender) pair has no configured
     * transport time are shipped and delivered immediately (zero
     * simulated time) instead of throwing [NoCarrierOptionException].
     * Default false.
     */
    var immediateTransportFlag: Boolean = false

    private val myNumInTransit: TWResponse =
        TWResponse(this, name = "${this.name} : #In Transit")

    private val myTransitTime: Response =
        Response(this, name = "${this.name} : Transit Time")

    /** Read-only view of the amount-in-transit response. */
    val numInTransitResponse: TWResponseCIfc get() = myNumInTransit

    /** Read-only view of the per-shipment transit-time response. */
    val transitTimeResponse: ResponseCIfc get() = myTransitTime

    // Per-(filler, sender) shipment counter. A counter is created the
    // first time an edge is registered via setTransportTime; subsequent
    // shipments on that edge increment it.
    private val myShipmentCounters:
        MutableMap<DemandFillerIfc, MutableMap<DemandSenderIfc, Counter>> =
        linkedMapOf()

    // Phase-1 (cost redesign) per-edge weight and cube accumulators,
    // keyed by (filler, sender) like the shipment counter above.
    private val myLoadWeightCounters:
        MutableMap<DemandFillerIfc, MutableMap<DemandSenderIfc, Counter>> =
        linkedMapOf()
    private val myLoadCubeCounters:
        MutableMap<DemandFillerIfc, MutableMap<DemandSenderIfc, Counter>> =
        linkedMapOf()

    /**
     * Per-edge demand-shipment counter, or null if no transport time
     * has been registered for the ([filler], [sender]) pair. Counts
     * individual demands shipped on that edge via this carrier in the
     * current replication.
     */
    fun getDemandShipmentCounter(
        filler: DemandFillerIfc,
        sender: DemandSenderIfc,
    ): CounterCIfc? = myShipmentCounters[filler]?.get(sender)

    /**
     * Per-edge demand-shipment count, or 0 if the ([filler], [sender])
     * pair is unregistered.
     */
    fun getNumberOfDemandShipments(
        filler: DemandFillerIfc,
        sender: DemandSenderIfc,
    ): Double = getDemandShipmentCounter(filler, sender)?.value ?: 0.0

    /**
     * Per-edge cumulative weight of all demands shipped on the
     * ([filler], [sender]) edge in the current replication (post-warmup),
     * or null if the edge is unregistered.  Sum of `demand.weight`
     * across every `transportDemand(demand)` routed on this edge.
     */
    fun totalLoadWeightAccumulator(
        filler: DemandFillerIfc,
        sender: DemandSenderIfc,
    ): CounterCIfc? = myLoadWeightCounters[filler]?.get(sender)

    /**
     * Per-edge cumulative cube of all demands shipped on the
     * ([filler], [sender]) edge in the current replication (post-warmup),
     * or null if the edge is unregistered.
     */
    fun totalLoadCubeAccumulator(
        filler: DemandFillerIfc,
        sender: DemandSenderIfc,
    ): CounterCIfc? = myLoadCubeCounters[filler]?.get(sender)

    private val deliveryAction = DeliveryAction()

    /**
     * Register a zero-delay transport edge between [filler] and
     * [sender]. The shared [myNoDelayRV] is reused for efficiency.
     */
    fun setTransportTime(filler: DemandFillerIfc, sender: DemandSenderIfc) {
        val edges = myTransportTimes.getOrPut(filler) { linkedMapOf() }
        // Reuse the shared no-delay RV for any (filler,sender) pair
        // that resolves to zero — matches Java's pooling.
        if (sender !in edges) {
            edges[sender] = myNoDelayRV
        }
        registerShipmentCounter(filler, sender)
    }

    /**
     * Register a transport-time distribution on the (filler, sender)
     * edge. If a previous edge used the shared zero-delay RV, it is
     * replaced with a fresh [RandomVariable] wrapping [distribution].
     */
    fun setTransportTime(
        filler: DemandFillerIfc,
        sender: DemandSenderIfc,
        distribution: RVariableIfc,
    ) {
        val edges = myTransportTimes.getOrPut(filler) { linkedMapOf() }
        val existing = edges[sender]
        if (existing != null) {
            if (existing === myNoDelayRV) {
                edges[sender] = RandomVariable(this, distribution)
            } else {
                existing.initialRandomSource = distribution
            }
        } else {
            edges[sender] = RandomVariable(this, distribution)
        }
        registerShipmentCounter(filler, sender)
    }

    /**
     * Lazy-create the per-edge shipment counter on first registration.
     * KSL forbids new ModelElements after simulate() starts, so all
     * counter creation must happen at construction time via these
     * setTransportTime entry points.
     */
    private fun registerShipmentCounter(
        filler: DemandFillerIfc,
        sender: DemandSenderIfc,
    ) {
        val perFiller = myShipmentCounters.getOrPut(filler) { linkedMapOf() }
        perFiller.getOrPut(sender) {
            Counter(this, name = "${this.name}:#Shipments:${filler.name}:${sender.name}")
        }
        val perFillerWeight = myLoadWeightCounters.getOrPut(filler) { linkedMapOf() }
        perFillerWeight.getOrPut(sender) {
            Counter(this, name = "${this.name}:LoadWeight:${filler.name}:${sender.name}")
        }
        val perFillerCube = myLoadCubeCounters.getOrPut(filler) { linkedMapOf() }
        perFillerCube.getOrPut(sender) {
            Counter(this, name = "${this.name}:LoadCube:${filler.name}:${sender.name}")
        }
    }

    /**
     * Attach [sender] to every currently registered filler with the
     * shared zero-delay edge.
     */
    fun attachDemandSenderToAllFillers(sender: DemandSenderIfc) {
        for (filler in myTransportTimes.keys.toList()) {
            setTransportTime(filler, sender)
        }
    }

    override fun canShip(demand: SupplyChainModel.Demand): Boolean {
        val filler = demand.filler ?: return false
        val sender = demand.demandSender ?: return false
        return myTransportTimes[filler]?.containsKey(sender) == true
    }

    override fun transportDemand(demand: SupplyChainModel.Demand) {
        val filler = demand.filler
            ?: error("the filler on the demand was null")
        val sender = demand.demandSender
            ?: error("the sender on the demand was null")

        myNumInTransit.increment(demand.amountFilled.toDouble())
        myShipmentCounters[filler]?.get(sender)?.increment()
        myLoadWeightCounters[filler]?.get(sender)?.increment(demand.weight)
        myLoadCubeCounters[filler]?.get(sender)?.increment(demand.cube)
        val edges = myTransportTimes[filler]
        if (edges == null) {
            handleImmediateOrThrow(demand, "filler is not registered")
            return
        }
        val rv = edges[sender]
        if (rv == null) {
            handleImmediateOrThrow(demand, "sender is not valid for this carrier")
            return
        }
        val t = rv.value
        if (t == 0.0) {
            demand.ship()
            myNumInTransit.decrement(demand.amountFilled.toDouble())
            myTransitTime.value = 0.0
            demand.deliver()
        } else {
            deliveryAction.schedule(t, message = demand)
            demand.ship()
        }
    }

    private fun handleImmediateOrThrow(
        demand: SupplyChainModel.Demand,
        message: String,
    ) {
        if (!immediateTransportFlag) {
            throw NoCarrierOptionException(message)
        }
        demand.ship()
        myNumInTransit.decrement(demand.amountFilled.toDouble())
        myTransitTime.value = 0.0
        demand.deliver()
    }

    private inner class DeliveryAction : EventAction<SupplyChainModel.Demand>() {
        override fun action(event: KSLEvent<SupplyChainModel.Demand>) {
            val d = event.message!!
            myNumInTransit.decrement(d.amountFilled.toDouble())
            myTransitTime.value = time - d.timeShipped
            d.deliver()
        }
    }
}
