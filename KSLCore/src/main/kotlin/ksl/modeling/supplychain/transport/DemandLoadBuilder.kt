package ksl.modeling.supplychain.transport

import ksl.modeling.supplychain.*

import ksl.modeling.queue.Queue
import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.simulation.ModelElement

/**
 * Holds incoming demands in a queue and packages them into
 * [SupplyChainModel.DemandLoad]s. Automatic load formation is
 * driven by [loadFormingOption]; the [ALWAYS][LoadFormingOption.ALWAYS]
 * default forms a single-demand load per arriving demand.
 *
 * Custom strategies hook in via [loadFormingRule] (replaces the
 * built-in option) and [loadFormedListener] (fires after each
 * automatically formed load).
 *
 * Optional **per-item on-hand tracking**: when [itemTypes] is
 * non-empty, the builder creates one [TWResponse] per item type and
 * tracks the time-weighted average number of units of that item
 * sitting in the demand queue.  Read each via
 * [unitsOnHandResponse]; consumed by the network cost model to
 * compute `shipmentBuildingHoldingCost`.  When [itemTypes] is empty
 * (the default), no per-item TWResponses are created and the cost
 * line stays at 0 — opt-in.
 *
 * **RULE-mode caveat**: a custom [DemandLoadFormingRuleIfc] that
 * removes demands from [demandQueue] directly (rather than via
 * [formLoadByCount] / [formLoadByWeight] / [formLoadByCube] /
 * [formLoadAlways]) will not decrement the per-item TWResponses,
 * leaving them stale.  RULE-mode users who care about
 * `shipmentBuildingHoldingCost` accounting must use the built-in
 * form helpers or maintain their own stats.  Documented limitation,
 * not a guard.
 *
 * @param supplyChainModel model that owns the created
 *        [SupplyChainModel.DemandLoad]s
 * @param parent parent model element; defaults to [supplyChainModel]
 * @param name optional model-element name
 * @param itemTypes item types this builder may carry; required for
 *        per-item on-hand TWResponse tracking.  Pre-create them at
 *        construction time because KSL forbids adding stats
 *        elements once the simulation is running.
 *
 * @see sc.transportlayer.DemandLoadBuilder
 */
open class DemandLoadBuilder @JvmOverloads constructor(
    val supplyChainModel: SupplyChainModel,
    parent: ModelElement = supplyChainModel,
    name: String? = null,
    itemTypes: Collection<ItemType> = emptyList(),
) : ModelElement(parent, name) {

    /**
     * Strategy used when a new demand arrives and the builder is asked
     * to (possibly) form a load.
     */
    enum class LoadFormingOption {
        /** Hold demands; do not auto-form. */
        NONE,

        /** Form once [countLimit] demands are queued. */
        COUNT,

        /** Form once [minWeightLimit]..[maxWeightLimit] thresholds are met. */
        WEIGHT,

        /** Form once [minCubeLimit]..[maxCubeLimit] thresholds are met. */
        CUBE,

        /** Defer to the user-supplied [loadFormingRule]. */
        RULE,

        /** Form an immediate single-demand load on every arrival. */
        ALWAYS,
    }

    var loadFormingOption: LoadFormingOption = LoadFormingOption.ALWAYS

    var minWeightLimit: Double = 1.0
        private set
    var maxWeightLimit: Double = 1.0
        private set

    var minCubeLimit: Double = 1.0
        private set
    var maxCubeLimit: Double = 1.0
        private set

    var countLimit: Int = 1
        set(value) {
            require(value > 0) { "countLimit must be > 0" }
            field = value
        }

    private val myTotalWeight: TWResponse =
        TWResponse(this, name = "${this.name}Weight")

    private val myTotalCube: TWResponse =
        TWResponse(this, name = "${this.name}Cube")

    /**
     * Phase-1 (cost redesign): cumulative count of loads this builder
     * has formed in the current replication (post-warmup).
     * Incremented every time a `formLoad*` path enqueues a new load
     * into [loadQueue].  Drives the per-builder per-load attribution
     * the cost model uses to size the shipment-builder holding-cost
     * line.
     */
    private val myLoadsShippedCounter: Counter =
        Counter(this, name = "${this.name}#LoadsFormed")

    /** Read-only view of the loads-formed Counter. */
    val loadsShippedAccumulator: CounterCIfc get() = myLoadsShippedCounter

    /** Read-only view of total weight waiting in the demand queue. */
    val totalWeightResponse: TWResponseCIfc get() = myTotalWeight

    /** Read-only view of total cube waiting in the demand queue. */
    val totalCubeResponse: TWResponseCIfc get() = myTotalCube

    /**
     * Per-item time-weighted on-hand counts.  Empty when this builder
     * was constructed without `itemTypes` — the cost line that reads
     * these stays at 0 in that case.
     */
    private val myUnitsOnHandByItem:
        MutableMap<ItemType, TWResponse> = linkedMapOf<ItemType, TWResponse>().apply {
            for (item in itemTypes) {
                put(item, TWResponse(this@DemandLoadBuilder,
                    name = "${this@DemandLoadBuilder.name}UnitsOnHand:${item.name}"))
            }
        }

    /**
     * Time-weighted on-hand units of [item] sitting in the demand
     * queue, or null if this builder was not constructed with
     * per-item tracking for [item].
     */
    fun unitsOnHandResponse(item: ItemType): TWResponseCIfc? =
        myUnitsOnHandByItem[item]

    /** Item types this builder is tracking on-hand units for. */
    val trackedItemTypes: Set<ItemType> get() = myUnitsOnHandByItem.keys

    private val myDemandQueue: Queue<SupplyChainModel.Demand> =
        Queue(this, name = "${this.name}DemandQ")

    private val myLoadQueue: Queue<SupplyChainModel.DemandLoad> =
        Queue(this, name = "${this.name}LoadQ")

    /** Read-only view of the waiting-demand queue. */
    val demandQueue: Queue<SupplyChainModel.Demand> get() = myDemandQueue

    /** Read-only view of the formed-load queue. */
    val loadQueue: Queue<SupplyChainModel.DemandLoad> get() = myLoadQueue

    /** Discipline applied to [demandQueue] at the start of each replication. */
    fun setDemandQueueInitialDiscipline(discipline: Queue.Discipline) {
        myDemandQueue.initialDiscipline = discipline
    }

    /** Discipline applied to [loadQueue] at the start of each replication. */
    fun setLoadQueueInitialDiscipline(discipline: Queue.Discipline) {
        myLoadQueue.initialDiscipline = discipline
    }

    /** Optional fire-on-form callback. */
    var loadFormedListener: DemandLoadFormedListenerIfc? = null

    /**
     * Optional user-supplied rule. Setting a non-null rule switches
     * [loadFormingOption] to [LoadFormingOption.RULE]; setting null
     * reverts to [LoadFormingOption.NONE] (Java parity).
     */
    var loadFormingRule: DemandLoadFormingRuleIfc? = null
        set(value) {
            field = value
            loadFormingOption = if (value != null)
                LoadFormingOption.RULE
            else
                LoadFormingOption.NONE
        }

    /**
     * Accept [demand] into the waiting queue and try to form a load
     * (unless [loadFormingOption] is [LoadFormingOption.NONE]).
     */
    open fun receiveDemand(demand: SupplyChainModel.Demand) {
        myTotalWeight.increment(demand.weight)
        myTotalCube.increment(demand.cube)
        myUnitsOnHandByItem[demand.itemType]?.increment(demand.amountFilled.toDouble())
        myDemandQueue.enqueue(demand)
        if (loadFormingOption != LoadFormingOption.NONE) {
            if (formLoad()) {
                loadFormedListener?.loadFormed(this)
            }
        }
    }

    /** Form a load using the current [loadFormingOption]. */
    fun formLoad(): Boolean = formLoad(loadFormingOption)

    /** Form a load using the supplied [option]. */
    fun formLoad(option: LoadFormingOption): Boolean = when (option) {
        LoadFormingOption.ALWAYS -> formLoadAlways()
        LoadFormingOption.RULE -> loadFormingRule?.formLoad(this) ?: false
        LoadFormingOption.COUNT -> formLoadByCount(countLimit)
        LoadFormingOption.CUBE -> formLoadByCube(minCubeLimit, maxCubeLimit)
        LoadFormingOption.WEIGHT -> formLoadByWeight(minWeightLimit, maxWeightLimit)
        LoadFormingOption.NONE -> false
    }

    private fun formLoadAlways(): Boolean {
        if (myDemandQueue.isEmpty) return false
        val load = supplyChainModel.createDemandLoad()
        val d = myDemandQueue.removeNext()!!
        myTotalWeight.decrement(d.weight)
        myTotalCube.decrement(d.cube)
        myUnitsOnHandByItem[d.itemType]?.decrement(d.amountFilled.toDouble())
        load.addDemand(d)
        myLoadQueue.enqueue(load)
        myLoadsShippedCounter.increment()
        return true
    }

    /**
     * Form a load from the first [numDemands] entries in the demand
     * queue, when at least that many are present.
     */
    fun formLoadByCount(numDemands: Int): Boolean {
        require(numDemands > 0) { "numDemands must be > 0" }
        if (myDemandQueue.size < numDemands) return false
        val load = supplyChainModel.createDemandLoad()
        repeat(numDemands) {
            val d = myDemandQueue.removeNext()!!
            myTotalWeight.decrement(d.weight)
            myTotalCube.decrement(d.cube)
            myUnitsOnHandByItem[d.itemType]?.decrement(d.amountFilled.toDouble())
            load.addDemand(d)
        }
        myLoadQueue.enqueue(load)
        myLoadsShippedCounter.increment()
        return true
    }

    /**
     * Form a load by accumulating demands whose total weight stays
     * within `[`[minWeight], [maxWeight]`]`.
     */
    fun formLoadByWeight(minWeight: Double, maxWeight: Double): Boolean {
        require(minWeight > 0) { "minWeight must be > 0" }
        require(maxWeight >= minWeight) {
            "maxWeight must be >= minWeight"
        }
        if (myDemandQueue.isEmpty) return false
        if (myTotalWeight.value < minWeight) return false

        val load = supplyChainModel.createDemandLoad()
        val n = myDemandQueue.size
        for (i in 0 until n) {
            val d = myDemandQueue.peekAt(i) ?: continue
            if (d.weight + load.weight <= maxWeight) {
                load.addDemand(d)
            }
        }
        removeInBoundDemands(load.demands.toList())
        myLoadQueue.enqueue(load)
        myLoadsShippedCounter.increment()
        return true
    }

    /**
     * Form a load by accumulating demands whose total cube stays
     * within `[`[minCube], [maxCube]`]`.
     */
    fun formLoadByCube(minCube: Double, maxCube: Double): Boolean {
        require(minCube > 0) { "minCube must be > 0" }
        require(maxCube >= minCube) {
            "maxCube must be >= minCube"
        }
        if (myDemandQueue.isEmpty) return false
        if (myTotalCube.value < minCube) return false

        val load = supplyChainModel.createDemandLoad()
        val n = myDemandQueue.size
        for (i in 0 until n) {
            val d = myDemandQueue.peekAt(i) ?: continue
            if (d.cube + load.cube <= maxCube) {
                load.addDemand(d)
            }
        }
        removeInBoundDemands(load.demands.toList())
        myLoadQueue.enqueue(load)
        myLoadsShippedCounter.increment()
        return true
    }

    private fun removeInBoundDemands(
        demands: Collection<SupplyChainModel.Demand>,
    ) {
        for (d in demands) {
            if (myDemandQueue.remove(d)) {
                myTotalWeight.decrement(d.weight)
                myTotalCube.decrement(d.cube)
                myUnitsOnHandByItem[d.itemType]?.decrement(d.amountFilled.toDouble())
            }
        }
    }

    /** Update the cube limits used by [LoadFormingOption.CUBE]. */
    fun setCubeFormingLimits(minCube: Double, maxCube: Double) {
        require(minCube > 0) { "minCube must be > 0" }
        require(maxCube >= minCube) { "maxCube must be >= minCube" }
        minCubeLimit = minCube
        maxCubeLimit = maxCube
    }

    /** Update the weight limits used by [LoadFormingOption.WEIGHT]. */
    fun setWeightFormingLimits(minWeight: Double, maxWeight: Double) {
        require(minWeight > 0) { "minWeight must be > 0" }
        require(maxWeight >= minWeight) { "maxWeight must be >= minWeight" }
        minWeightLimit = minWeight
        maxWeightLimit = maxWeight
    }
}
