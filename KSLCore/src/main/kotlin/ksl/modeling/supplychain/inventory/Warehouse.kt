package ksl.modeling.supplychain.inventory

import ksl.modeling.supplychain.*

import ksl.modeling.queue.Queue
import ksl.modeling.queue.QueueCIfc
import ksl.simulation.ModelElement

/**
 * Concrete [WarehouseAbstract] that holds incoming orders in a FIFO
 * queue and dispatches each order's demands to per-item-type
 * inventories via an internal [InventoryHolderAbstract]. When all
 * demands on an order are filled, the order is shipped via
 * [orderShipper] (if set) or transitioned through ship → deliver.
 *
 * See `sc.inventorylayer.Warehouse`
 */
open class Warehouse @JvmOverloads constructor(
    parent: ModelElement,
    initialAvailability: Boolean = true,
    name: String? = null,
) : WarehouseAbstract(parent, initialAvailability, name) {

    protected val myOrderQ: Queue<SupplyChainModel.Order> =
        Queue(this, "${this.name} OrderQ")

    /** Read-only view of the order queue. */
    val orderQueue: QueueCIfc<SupplyChainModel.Order> get() = myOrderQ

    private val myInventoryHolder: WarehouseInventoryHolder =
        WarehouseInventoryHolder(this, "${this.name}InventoryHolder")

    init {
        // Route incoming demands through the internal holder.
        demandFillerFinder = myInventoryHolder
    }

    override fun mightRequest(type: ItemType): Boolean =
        myInventoryHolder.mightRequest(type)

    override fun fill(order: SupplyChainModel.Order) {
        require(!order.isEmpty) { "Cannot fill an empty order" }
        placeOrderInProcessState(order)
        enqueueOrder(order)
        askOrderToBeginProcessingDemands(order)
    }

    /** Add a pre-built [Inventory] to this warehouse. */
    fun addInventory(inventory: Inventory): Unit =
        myInventoryHolder.addInventory(inventory)

    /** Convenience: build and add an (r, Q) inventory. */
    @JvmOverloads
    fun addReorderPointReorderQuantityInventory(
        type: ItemType,
        reorderPoint: Int,
        reorderQty: Int,
        initialOnHand: Int = 0,
        name: String? = null,
    ): Inventory = myInventoryHolder.addReorderPointReorderQuantityInventory(
        type, reorderPoint, reorderQty, initialOnHand, name,
    )

    /** Convenience: build and add an (r, S) inventory. */
    @JvmOverloads
    fun addReorderPointOrderUpToLevelInventory(
        type: ItemType,
        reorderPoint: Int,
        orderUpToPoint: Int,
        initialOnHand: Int = 0,
        name: String? = null,
    ): Inventory = myInventoryHolder.addReorderPointOrderUpToLevelInventory(
        type, reorderPoint, orderUpToPoint, initialOnHand, name,
    )

    override fun orderFilled(order: SupplyChainModel.Order) {
        myOrderQ.remove(order)
        val shipper = orderShipper
        if (shipper != null) {
            shipper.transportOrder(order)
        } else {
            order.ship()
            order.deliver()
        }
    }

    override fun replenishmentOrderDelivered(order: SupplyChainModel.Order) {
        // Cascade delivery to each demand on the order.
        order.setDemandStateToDelivered()
    }

    /** Enqueues [order] and registers warehouse listeners. */
    protected open fun enqueueOrder(order: SupplyChainModel.Order) {
        myOrderQ.enqueue(order)
        attachOrderFilledListener(order)
        attachDemandOnOrderFilledListeners(order)
    }

    /**
     * Internal [InventoryHolderAbstract] used to route incoming orders'
     * demands to the warehouse's per-item-type inventories. Implements
     * [DemandFillerFinderIfc] (so [OrderFillerAbstract] can use it as
     * `demandFillerFinder`) and [ReplenishmentRequesterIfc] (so internal
     * inventories' replenishment routes back to the enclosing warehouse).
     */
    private inner class WarehouseInventoryHolder(
        parent: ModelElement,
        name: String,
    ) : InventoryHolderAbstract(parent, true, name),
        DemandFillerFinderIfc,
        ReplenishmentRequesterIfc {

        init {
            // Replace the default holder-loopback requester with one that
            // routes up to the enclosing warehouse.
            replenishmentRequester = this
        }

        override fun fillDemand(demand: SupplyChainModel.Demand) {
            // The order's per-demand filler was set during
            // OrderFillerAbstract.setUpDemandFillersOnOrder. Delegate.
            demand.filler!!.fillDemand(demand)
        }

        override fun receive(demand: SupplyChainModel.Demand) {
            if (!isAvailable) {
                demand.setStatus(DemandStatusCode.FillerUnavailable)
                demand.reject()
                return
            }
            val inv = myInventory[demand.itemType]
            if (inv == null) {
                demand.setStatus(DemandStatusCode.ItemTypeMismatch)
                demand.reject()
                return
            }
            // Re-point the demand's filler from the holder to the
            // matching inventory so subsequent fillDemand calls hit it.
            // Allowed because Sent.setFiller is legal (Session 5).
            demand.setFiller(inv)
            inv.receive(demand)
        }

        override fun findDemandFiller(demand: SupplyChainModel.Demand): DemandFillerIfc? =
            myInventory[demand.itemType]

        override fun requestReplenishment(
            inventory: Inventory,
            demand: SupplyChainModel.Demand,
        ) {
            // Route up to the enclosing warehouse.
            this@Warehouse.requestReplenishment(inventory, demand)
        }
    }
}
