package ksl.modeling.supplychain.inventory

import ksl.modeling.supplychain.*

import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.observers.ModelElementObserver
import ksl.simulation.ModelElement

/**
 * Abstract base for objects that hold one [Inventory] per [ItemType]
 * and route incoming demands to the appropriate inventory. Acts as a
 * [DemandFillerIfc] (forwards demands by item type) and a
 * [DemandSenderIfc] (forwards inventory replenishment demands outward).
 *
 * Aggregate statistics across the held inventories are exposed via
 * [AggregateInventoryResponseIfc] (delegated to a member
 * [AggregateInventoryResponse]).
 *
 * @see sc.inventorylayer.InventoryHolderAbstract
 */
abstract class InventoryHolderAbstract @JvmOverloads constructor(
    parent: ModelElement,
    initialAvailability: Boolean = true,
    name: String? = null,
) : DemandFillerAbstract(parent, initialAvailability, name),
    DemandSenderIfc, AggregateInventoryResponseIfc {

    /** Per-item-type inventories. LinkedHashMap per porting plan §4.3. */
    protected val myInventory: MutableMap<ItemType, Inventory> = linkedMapOf()

    private val myAggregateResponse: AggregateInventoryResponse =
        AggregateInventoryResponse(this)

    private val myAggInvAvailability: TWResponse =
        TWResponse(this, name = "${this.name} : Fraction of time total on hand > 0")

    private val aggInvAvailabilityObserver = AggregateAvailabilityObserver()

    // ---- DemandSenderIfc surface ---------------------------------------

    override var demandFiller: DemandFillerIfc? = null
    override var demandFillerFinder: DemandFillerFinderIfc? = null

    /**
     * Used by held inventories internally; defaults to a requester that
     * calls back into this holder. Subclasses may swap it.
     */
    var replenishmentRequester: ReplenishmentRequesterIfc? = HolderReplenishmentRequester()

    init {
        // Observe the aggregate on-hand and flip availability when crossing 0.
        myAggregateResponse.aggregateOnHandInventory
            .attachModelElementObserver(aggInvAvailabilityObserver)
    }

    override fun mightRequest(type: ItemType): Boolean = type in myInventory

    // ---- inventory-management API --------------------------------------

    /** Number of distinct item types held. */
    val numberOfItemTypes: Int get() = myInventory.size

    fun getInventoryInfo(type: ItemType): InventoryIfc? = myInventory[type]

    fun getInventory(type: ItemType): Inventory? = myInventory[type]

    override val itemTypes: Collection<ItemType>
        get() = myInventory.keys.toList()

    /**
     * Add an [Inventory] for its declared [Inventory.itemType]. Subscribes
     * the inventory's responses to this holder's aggregate; sets the
     * inventory's [Inventory.replenishmentRequester] so its replenishment
     * routes through here.
     */
    fun addInventory(inventory: Inventory) {
        val type = inventory.itemType
        require(type !in myInventory) {
            "Holder already contains an inventory for type: ${type.name}"
        }
        inventory.attachAggregateInventoryResponse(this)
        val requester = replenishmentRequester
            ?: error("No replenishment requester set on $name")
        inventory.replenishmentRequester = requester
        myInventory[type] = inventory
    }

    /**
     * Remove the inventory for [type]. The associated [Inventory] model
     * element may optionally be removed from the model.
     */
    @JvmOverloads
    fun removeInventory(type: ItemType, fromModel: Boolean = false): Inventory {
        val inv = myInventory[type]
            ?: error("Holder does not hold ${type.name}")
        myInventory.remove(type)
        inv.detachAggregateInventoryResponse(this)
        inv.replenishmentRequester = null
        if (fromModel) inv.removeFromModel()
        return inv
    }

    @JvmOverloads
    fun addReorderPointReorderQuantityInventory(
        type: ItemType,
        reorderPoint: Int,
        reorderQty: Int,
        initialOnHand: Int = 0,
        name: String? = null,
    ): Inventory {
        val invName = name ?: "${this.name} : ${type.name}"
        val inventory = Inventory.createReorderPointReorderQuantityInventory(
            this, type, reorderPoint, reorderQty, initialOnHand, invName,
        )
        addInventory(inventory)
        return inventory
    }

    @JvmOverloads
    fun addReorderPointOrderUpToLevelInventory(
        type: ItemType,
        reorderPoint: Int,
        orderUpToPoint: Int,
        initialOnHand: Int = 0,
        name: String? = null,
    ): Inventory {
        val invName = name ?: "${this.name} : ${type.name}"
        val inventory = Inventory.createReorderPointOrderUpToLevelInventory(
            this, type, reorderPoint, orderUpToPoint, initialOnHand, invName,
        )
        addInventory(inventory)
        return inventory
    }

    // ---- DemandFillerIfc surface (delegate to per-type inventory) -----

    override fun canFillItemType(demand: SupplyChainModel.Demand): Boolean =
        demand.itemType in myInventory

    override fun canFillItemType(type: ItemType): Boolean = type in myInventory

    override fun negotiate(demand: SupplyChainModel.Demand): DemandMessageIfc? {
        if (!isAvailable) return null
        return myInventory[demand.itemType]?.negotiate(demand)
    }

    override fun determineRequestStatus(demand: SupplyChainModel.Demand): DemandStatusCode {
        val inv = myInventory[demand.itemType] ?: return DemandStatusCode.ItemTypeMismatch
        return inv.determineRequestStatus(demand)
    }

    override fun willReject(demand: SupplyChainModel.Demand): Boolean {
        val inv = myInventory[demand.itemType] ?: return true
        return inv.willReject(demand)
    }

    /** Subclasses define how demands are received and filled. */
    abstract override fun receive(demand: SupplyChainModel.Demand)
    abstract override fun fillDemand(demand: SupplyChainModel.Demand)

    // ---- replenishment routing -----------------------------------------

    /**
     * Invoked by an internal inventory (via the wired-up replenishment
     * requester) to send its replenishment demand outward. Sets the
     * holder as the demand's sender and uses the holder's filler or
     * finder to dispatch.
     *
     * Note: replenishment listeners are already attached by
     * [Inventory.requestReplenishment]; not re-attached here.
     */
    protected open fun requestReplenishment(
        inventory: Inventory,
        demand: SupplyChainModel.Demand,
    ) {
        demand.setDemandSender(this)
        val filler = demandFiller
            ?: demandFillerFinder?.findDemandFiller(demand)
            ?: throw NoDemandFillerFoundException(
                "No demand filler set for $name to handle demand $demand",
            )
        demand.setFiller(filler)
        demand.sent()
        filler.receive(demand)
    }

    // ---- AggregateInventoryResponseIfc delegation ----------------------

    override val aggregateOnHandInventory get() = myAggregateResponse.aggregateOnHandInventory
    override val aggregateAmountOnOrder get() = myAggregateResponse.aggregateAmountOnOrder
    override val aggregateAmountBackOrdered get() = myAggregateResponse.aggregateAmountBackOrdered
    override val aggregateNumberBackOrdered get() = myAggregateResponse.aggregateNumberBackOrdered
    override val aggregateAvgFirstFillRate get() = myAggregateResponse.aggregateAvgFirstFillRate
    override val aggregateAvgCustomerWaitTime get() = myAggregateResponse.aggregateAvgCustomerWaitTime
    override val aggregateNumberOfReplenishmentDemands
        get() = myAggregateResponse.aggregateNumberOfReplenishmentDemands

    override fun subscribeTo(r: AggregateInventoryResponseIfc) =
        myAggregateResponse.subscribeTo(r)

    override fun unsubscribeFrom(r: AggregateInventoryResponseIfc) =
        myAggregateResponse.unsubscribeFrom(r)

    /** Read-only view of the time-weighted "fraction of time on-hand > 0". */
    val aggregateFractionTimeOnHandGTZero: TWResponseCIfc get() = myAggInvAvailability

    // ---- inner classes -------------------------------------------------

    /**
     * Observes the aggregate on-hand response; flips
     * [myAggInvAvailability] to 1.0 whenever total on-hand > 0,
     * else 0.0. Replaces Java's `AggregateAvailabilityObserver`.
     */
    private inner class AggregateAvailabilityObserver : ModelElementObserver() {
        override fun beforeReplication(modelElement: ModelElement) {
            // beforeReplication fires after the model starts running, so
            // initialValue is locked. Set the current value instead, which
            // gets the time-weighted statistic going from the right point.
            val r = modelElement as TWResponse
            myAggInvAvailability.value = if (r.initialValue > 0.0) 1.0 else 0.0
        }
        override fun update(modelElement: ModelElement) {
            val r = modelElement as TWResponse
            myAggInvAvailability.value = if (r.value > 0.0) 1.0 else 0.0
        }
    }

    /** Default replenishment requester — calls back into the holder. */
    private inner class HolderReplenishmentRequester : ReplenishmentRequesterIfc {
        override fun requestReplenishment(
            inventory: Inventory,
            demand: SupplyChainModel.Demand,
        ) = this@InventoryHolderAbstract.requestReplenishment(inventory, demand)
    }
}
