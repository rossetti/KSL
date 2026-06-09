package ksl.modeling.supplychain.transport

import ksl.modeling.supplychain.*

import ksl.modeling.variable.AggregateCounter
import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement

/**
 * Demand carrier that buffers per-destination demands in
 * [DemandLoadBuilder]s and ships them as [SupplyChainModel.DemandLoad]s.
 * Inherits per-destination random transport times from
 * [TimeBasedDemandCarrier]; load formation strategy is delegated to the
 * builders.
 *
 * When [reactToLoadBuildersFlag] is true, the carrier ships every load
 * a builder forms automatically; otherwise loads sit in the builder's
 * load queue and must be drained explicitly via [shipLoads] (or by
 * scheduling a periodic shipping event).
 *
 * @param supplyChainModel model that owns the created loads (passed
 *        through when building new [DemandLoadBuilder]s)
 * @param parent parent model element; defaults to [supplyChainModel]
 * @param name optional model-element name
 *
 * @see sc.transportlayer.TimeBasedLoadCarrier
 */
open class TimeBasedLoadCarrier @JvmOverloads constructor(
    val supplyChainModel: SupplyChainModel,
    parent: ModelElement = supplyChainModel,
    name: String? = null,
) : TimeBasedDemandCarrier(parent, name) {

    private val myLoadBuilders:
        MutableMap<DemandSenderIfc, DemandLoadBuilder> = linkedMapOf()

    private val myShipmentCounters:
        MutableMap<DemandSenderIfc, Counter> = linkedMapOf()

    private val myShipmentCounter: AggregateCounter =
        AggregateCounter(this, name = "${this.name} Total Shipments")

    /** Read-only view of the aggregate shipment counter. */
    val shipmentCounter: CounterCIfc get() = myShipmentCounter

    // Phase-1 (cost redesign) per-destination weight and cube
    // accumulators.  Distinct from the parent's per-demand
    // accumulators: a load carrier ships LOADS, so these accumulate
    // load.weight / load.cube (sum of constituent demand weights /
    // cubes) at each shipFromLoadBuilder dispatch.
    private val myLoadWeightCounters:
        MutableMap<DemandSenderIfc, Counter> = linkedMapOf()
    private val myLoadCubeCounters:
        MutableMap<DemandSenderIfc, Counter> = linkedMapOf()

    /**
     * If true, every load formed automatically by an attached
     * [DemandLoadBuilder] is shipped immediately via [loadFormed].
     * Default false (loads sit in the builder's outgoing queue until
     * drained).
     */
    var reactToLoadBuildersFlag: Boolean = false

    private val deliveryAction = DeliveryAction()
    private val shipAction = ShipAction()

    private val loadBuiltListener =
        DemandLoadFormedListenerIfc { builder ->
            if (reactToLoadBuildersFlag) loadFormed(builder)
        }

    /**
     * Buffer the demand by handing it to its destination's load
     * builder. Throws if [demand]'s sender has no builder.
     */
    override fun transportDemand(demand: SupplyChainModel.Demand) {
        val sender = demand.demandSender
            ?: error("the sender on the demand was null")
        val builder = myLoadBuilders[sender]
            ?: throw IllegalArgumentException(
                "no load builder for the demand's sender",
            )
        builder.receiveDemand(demand)
    }

    /** Create and assign a default load builder for [destination]. */
    fun assignLoadBuilder(destination: DemandSenderIfc): DemandLoadBuilder =
        assignLoadBuilder(destination, name = null)

    /**
     * Create and assign a load builder for [destination], with the
     * supplied model-element name and (optional) per-item on-hand
     * tracking.
     */
    @JvmOverloads
    fun assignLoadBuilder(
        destination: DemandSenderIfc,
        name: String?,
        itemTypes: Collection<ItemType> = emptyList(),
    ): DemandLoadBuilder {
        val builder = DemandLoadBuilder(supplyChainModel, this, name, itemTypes)
        assignLoadBuilder(destination, builder)
        return builder
    }

    /**
     * Attach a pre-built [loadBuilder] for [destination]. Wires up the
     * built-in [DemandLoadFormedListenerIfc] so the carrier can react
     * to formed loads, and creates the per-destination shipment counter.
     */
    fun assignLoadBuilder(
        destination: DemandSenderIfc,
        loadBuilder: DemandLoadBuilder,
    ) {
        require(destination !in myLoadBuilders) {
            "destination has already been assigned a load builder"
        }
        myLoadBuilders[destination] = loadBuilder
        loadBuilder.loadFormedListener = loadBuiltListener

        val c = Counter(this, name = "${destination.name} #Shipments")
        myShipmentCounters[destination] = c
        myShipmentCounter.observe(c)

        myLoadWeightCounters[destination] =
            Counter(this, name = "${destination.name} LoadWeight")
        myLoadCubeCounters[destination] =
            Counter(this, name = "${destination.name} LoadCube")
    }

    /**
     * Per-destination cumulative weight of all LOADS shipped to
     * [sender] in the current replication (post-warmup), or
     * null if [sender] has no assigned load builder.  Each
     * dispatched load contributes `load.weight` (the sum of its
     * constituent demand weights).  Overrides the parent's
     * per-demand accumulator — the load carrier's shipment unit is
     * a load, not a demand.
     */
    override fun totalLoadWeightAccumulator(sender: DemandSenderIfc): CounterCIfc? =
        myLoadWeightCounters[sender]

    /**
     * Per-destination cumulative cube of all LOADS shipped to
     * [sender] in the current replication (post-warmup), or
     * null if [sender] has no assigned load builder.
     */
    override fun totalLoadCubeAccumulator(sender: DemandSenderIfc): CounterCIfc? =
        myLoadCubeCounters[sender]

    /** Per-destination shipment counter, or null if not assigned. */
    fun getShipmentCounter(destination: DemandSenderIfc): CounterCIfc? =
        myShipmentCounters[destination]

    /**
     * Per-destination shipment counter for the cost model.  A load
     * carrier's shipment unit is a **load**, so this returns the count
     * of loads dispatched to [sender] — the same counter as
     * [getShipmentCounter] — overriding the parent's per-demand counter,
     * which a load carrier never increments.  This is what lets the
     * per-edge [ksl.modeling.supplychain.cost.EdgeOutboundCostCalculator]
     * / [ksl.modeling.supplychain.cost.EdgeInboundCostCalculator] see
     * load formation (Loading / Shipping / Unloading per dispatched
     * load), mirroring the [totalLoadWeightAccumulator] /
     * [totalLoadCubeAccumulator] overrides.
     */
    override fun getDemandShipmentCounter(sender: DemandSenderIfc): CounterCIfc? =
        myShipmentCounters[sender]

    /** Per-destination count of loads dispatched to [sender], or 0. */
    override fun getNumberOfDemandShipments(sender: DemandSenderIfc): Double =
        myShipmentCounters[sender]?.value ?: 0.0

    /** Returns the load builder previously assigned to [destination]. */
    fun getLoadBuilder(destination: DemandSenderIfc): DemandLoadBuilder =
        myLoadBuilders[destination]
            ?: throw IllegalArgumentException(
                "no load builder assigned for destination",
            )

    /** True iff [destination] has an assigned load builder. */
    fun containsLoadBuilder(destination: DemandSenderIfc): Boolean =
        destination in myLoadBuilders

    /**
     * Snapshot of all load builders attached to this carrier, in
     * assignment order.  Used by the network cost model to walk
     * per-builder per-item on-hand statistics for the
     * `shipmentBuildingHoldingCost` line.
     */
    fun allLoadBuilders(): Collection<DemandLoadBuilder> =
        myLoadBuilders.values.toList()

    fun formLoadByCount(destination: DemandSenderIfc, numDemands: Int): Boolean =
        getLoadBuilder(destination).formLoadByCount(numDemands)

    fun formLoadByCube(
        destination: DemandSenderIfc,
        minCube: Double,
        maxCube: Double,
    ): Boolean = getLoadBuilder(destination).formLoadByCube(minCube, maxCube)

    fun formLoadByWeight(
        destination: DemandSenderIfc,
        minWeight: Double,
        maxWeight: Double,
    ): Boolean =
        getLoadBuilder(destination).formLoadByWeight(minWeight, maxWeight)

    fun setCountLimit(destination: DemandSenderIfc, countLimit: Int) {
        getLoadBuilder(destination).countLimit = countLimit
    }

    fun setCubeFormingLimits(
        destination: DemandSenderIfc,
        minCube: Double,
        maxCube: Double,
    ) {
        getLoadBuilder(destination).setCubeFormingLimits(minCube, maxCube)
    }

    fun setWeightFormingLimits(
        destination: DemandSenderIfc,
        minWeight: Double,
        maxWeight: Double,
    ) {
        getLoadBuilder(destination).setWeightFormingLimits(minWeight, maxWeight)
    }

    fun setDefaultLoadFormingOption(
        destination: DemandSenderIfc,
        option: DemandLoadBuilder.LoadFormingOption,
    ) {
        getLoadBuilder(destination).loadFormingOption = option
    }

    fun setLoadFormingRule(
        destination: DemandSenderIfc,
        rule: DemandLoadFormingRuleIfc?,
    ) {
        getLoadBuilder(destination).loadFormingRule = rule
    }

    /**
     * Drain every builder's load queue and ship the loads to their
     * destinations.
     */
    fun shipLoads() {
        for (builder in myLoadBuilders.values) {
            shipFromLoadBuilder(builder)
        }
    }

    /**
     * Default reaction to a load formation: ship all queued loads in
     * [builder]. Subclasses may override.
     */
    protected open fun loadFormed(builder: DemandLoadBuilder) {
        shipFromLoadBuilder(builder)
    }

    /** Total units carried by [load] — the sum of its demands' filled amounts. */
    private fun loadAmountInTransit(load: SupplyChainModel.DemandLoad): Double =
        load.demands.sumOf { it.amountFilled }.toDouble()

    /**
     * Drain [builder]'s load queue, scheduling per-destination delivery
     * events from the inherited per-sender transport times. If
     * [immediateTransportFlag] is true and no transport time has been
     * set for the destination, the load ships and delivers in the
     * current instant.
     */
    protected fun shipFromLoadBuilder(builder: DemandLoadBuilder) {
        val loadQueue = builder.loadQueue
        while (loadQueue.isNotEmpty) {
            val load = loadQueue.removeNext() ?: break
            val destination = load.destination
                ?: error("load has no destination")
            val amtInTransit = loadAmountInTransit(load)
            val rv = transportTimeFor(destination)
            if (rv == null) {
                if (!immediateTransportFlag) {
                    throw NoCarrierOptionException(
                        "the destination is not valid for this carrier",
                    )
                }
                recordShipmentDeparture(amtInTransit)
                load.ship()
                myShipmentCounters[destination]?.increment()
                myLoadWeightCounters[destination]?.increment(load.weight)
                myLoadCubeCounters[destination]?.increment(load.cube)
                recordShipmentArrival(amtInTransit, 0.0)
                load.deliver()
            } else {
                val t = rv.value
                recordShipmentDeparture(amtInTransit)
                deliveryAction.schedule(t, message = load)
                load.ship()
            }
        }
    }

    private inner class DeliveryAction :
        EventAction<SupplyChainModel.DemandLoad>() {
        override fun action(event: KSLEvent<SupplyChainModel.DemandLoad>) {
            val load = event.message!!
            val destination = load.destination
            if (destination != null) {
                myShipmentCounters[destination]?.increment()
                myLoadWeightCounters[destination]?.increment(load.weight)
                myLoadCubeCounters[destination]?.increment(load.cube)
            }
            val shipTime = load.demands.firstOrNull()?.timeShipped ?: time
            recordShipmentArrival(loadAmountInTransit(load), time - shipTime)
            load.deliver()
        }
    }

    private inner class ShipAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            shipLoads()
        }
    }
}
