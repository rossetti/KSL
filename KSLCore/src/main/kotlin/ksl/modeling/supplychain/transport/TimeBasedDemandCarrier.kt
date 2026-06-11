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
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * Demand carrier that delays each shipment by a per-[DemandSenderIfc]
 * random transport time. Senders without a configured time are either
 * rejected with [NoCarrierOptionException] or, if
 * [immediateTransportFlag] is true, shipped and delivered immediately
 * (zero simulated time).
 *
 * Statistics: [numInTransitResponse] (time-weighted by
 * [SupplyChainModel.Demand.amountFilled]) and [transitTimeResponse]
 * (per-shipment transit time) are updated for every shipment.
 *
 * @param parent the parent model element
 * @param name optional model-element name
 *
 * See `sc.transportlayer.TimeBasedDemandCarrier`
 */
open class TimeBasedDemandCarrier @JvmOverloads constructor(
    parent: ModelElement,
    name: String? = null,
) : DemandCarrierAbstract(parent, name) {

    // LinkedHashMap so iteration order is deterministic (porting plan §4.3).
    private val myTransportTimes: MutableMap<DemandSenderIfc, RandomVariable> =
        linkedMapOf()

    /**
     * When true, demands whose sender has no configured transport time
     * are shipped and delivered immediately (zero simulated time)
     * instead of throwing [NoCarrierOptionException]. Default false.
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

    // Per-destination shipment counter. A counter is created the first
    // time a destination is registered via setTransportTime; subsequent
    // shipments to that destination increment it.
    private val myShipmentCounters: MutableMap<DemandSenderIfc, Counter> =
        linkedMapOf()

    // Phase-1 (cost redesign) per-destination weight and cube
    // accumulators.  Counter (rather than TWResponse) because these
    // are cumulative totals over the post-warmup window — KSL's
    // Counter.warmUp resets via resetCounter(0.0, false), matching
    // the desired "since-warmup total" semantics.
    private val myLoadWeightCounters: MutableMap<DemandSenderIfc, Counter> =
        linkedMapOf()
    private val myLoadCubeCounters: MutableMap<DemandSenderIfc, Counter> =
        linkedMapOf()

    /**
     * Per-destination demand-shipment counter, or null if no transport
     * time has been registered for [sender]. Counts individual demands
     * shipped to [sender] via this carrier in the current replication.
     *
     * Named `DemandShipment` (rather than `Shipment`) to disambiguate
     * from [TimeBasedLoadCarrier.getShipmentCounter], which counts
     * load-level shipments at a different granularity.  A load carrier
     * overrides this to return its load-level counter, so the per-edge
     * cost calculators read the right shipment count under formation.
     */
    open fun getDemandShipmentCounter(sender: DemandSenderIfc): CounterCIfc? =
        myShipmentCounters[sender]

    /**
     * Per-destination demand-shipment count, or 0 if [sender] is
     * unregistered.
     */
    open fun getNumberOfDemandShipments(sender: DemandSenderIfc): Double =
        myShipmentCounters[sender]?.value ?: 0.0

    /**
     * Per-destination cumulative weight of all shipments sent to
     * [sender] in the current replication (post-warmup), or null if
     * [sender] is unregistered.  In this base class, the shipment
     * unit is a single demand, so the accumulator sums
     * `demand.weight` across every `transportDemand(demand)` call.
     * Subclasses ([TimeBasedLoadCarrier]) override to attribute
     * load-level weight instead.
     */
    open fun totalLoadWeightAccumulator(sender: DemandSenderIfc): CounterCIfc? =
        myLoadWeightCounters[sender]

    /**
     * Per-destination cumulative cube of all shipments sent to
     * [sender] in the current replication (post-warmup), or null if
     * [sender] is unregistered.
     */
    open fun totalLoadCubeAccumulator(sender: DemandSenderIfc): CounterCIfc? =
        myLoadCubeCounters[sender]

    private val deliveryAction = DeliveryAction()

    override fun canShip(demand: SupplyChainModel.Demand): Boolean =
        demand.demandSender?.let { it in myTransportTimes } == true

    override fun transportDemand(demand: SupplyChainModel.Demand) {
        val sender = demand.demandSender
            ?: error("the sender on the demand was null")

        myNumInTransit.increment(demand.amountFilled.toDouble())
        myShipmentCounters[sender]?.increment()
        myLoadWeightCounters[sender]?.increment(demand.weight)
        myLoadCubeCounters[sender]?.increment(demand.cube)
        val rv = myTransportTimes[sender]
        if (rv == null) {
            if (!immediateTransportFlag) {
                throw NoCarrierOptionException(
                    "the sender is not valid for this carrier",
                )
            }
            demand.ship()
            myNumInTransit.decrement(demand.amountFilled.toDouble())
            myTransitTime.value = 0.0
            demand.deliver()
        } else {
            val t = rv.value
            deliveryAction.schedule(t, message = demand)
            demand.ship()
        }
    }

    /**
     * Register (or replace) the transport-time distribution used for
     * [sender]. The supplied [distribution] is wrapped in a new
     * [RandomVariable] (per porting plan §4.1) the first time the
     * sender is added; subsequent calls swap the underlying source.
     */
    fun setTransportTime(
        sender: DemandSenderIfc,
        distribution: RVariableIfc,
    ) {
        val existing = myTransportTimes[sender]
        if (existing != null) {
            existing.initialRandomSource = distribution
        } else {
            myTransportTimes[sender] = RandomVariable(this, distribution)
        }
        registerDestination(sender)
    }

    /**
     * Create the per-destination shipment / weight / cube counters for
     * [sender] if they do not yet exist.  KSL forbids new ModelElements
     * after the executive starts, so this is a construction-time
     * operation.  Call it for any destination that may receive a
     * shipment under [immediateTransportFlag] *without* a configured
     * transport time — otherwise the per-destination counters never get
     * created and the increments in [transportDemand] silently no-op
     * (audit finding D).  [setTransportTime] already calls this.
     */
    fun registerDestination(sender: DemandSenderIfc) {
        myShipmentCounters.getOrPut(sender) {
            Counter(this, name = "${this.name}:#Shipments:${sender.name}")
        }
        myLoadWeightCounters.getOrPut(sender) {
            Counter(this, name = "${this.name}:LoadWeight:${sender.name}")
        }
        myLoadCubeCounters.getOrPut(sender) {
            Counter(this, name = "${this.name}:LoadCube:${sender.name}")
        }
    }

    /** True if a transport time has been configured for [sender]. */
    fun contains(sender: DemandSenderIfc): Boolean =
        sender in myTransportTimes

    /**
     * Lookup hook for subclasses that need to schedule shipments on the
     * same per-sender distributions (e.g. [TimeBasedLoadCarrier]
     * dispatching load shipments).
     */
    protected fun transportTimeFor(sender: DemandSenderIfc): RandomVariable? =
        myTransportTimes[sender]

    /**
     * Record that a shipment carrying [amountInTransit] units has
     * departed: increments this carrier's in-transit response.  A hook
     * for subclasses (e.g. [TimeBasedLoadCarrier]) that dispatch through
     * their own path but should still feed the inherited
     * [numInTransitResponse] / [transitTimeResponse].  Pair every call
     * with a later [recordShipmentArrival] of the same amount.
     */
    protected fun recordShipmentDeparture(amountInTransit: Double) {
        myNumInTransit.increment(amountInTransit)
    }

    /**
     * Record that a shipment carrying [amountInTransit] units has
     * arrived after [transitTime] in transit: decrements the in-transit
     * response and observes the transit time.  Pairs with
     * [recordShipmentDeparture].
     */
    protected fun recordShipmentArrival(amountInTransit: Double, transitTime: Double) {
        myNumInTransit.decrement(amountInTransit)
        myTransitTime.value = transitTime
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
