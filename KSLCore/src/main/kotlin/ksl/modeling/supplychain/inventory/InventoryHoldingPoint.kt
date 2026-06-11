package ksl.modeling.supplychain.inventory

import ksl.modeling.supplychain.*

import ksl.simulation.ModelElement

/**
 * Concrete [InventoryHolderAbstract] that holds [Inventory] instances
 * and delegates incoming demands to them by [ItemType]. Filled demands
 * are shipped via [demandCarrier] (if set) or transitioned immediately
 * through ship → deliver.
 *
 * See `sc.inventorylayer.InventoryHoldingPoint`
 */
open class InventoryHoldingPoint @JvmOverloads constructor(
    parent: ModelElement,
    initialAvailability: Boolean = true,
    name: String? = null,
) : InventoryHolderAbstract(parent, initialAvailability, name), NetworkNodeIfc {

    /** Optional hierarchy-level number. Smaller = upstream. */
    override var level: Int = 0

    /**
     * Delivery endpoint for incoming demands.  Default
     * [ksl.modeling.supplychain.flow.PassThroughStorageEndpoint]
     * — immediate `store()`.  Replace with a `Dock` to model
     * unload time.
     */
    override var deliveryEndpoint: ksl.modeling.supplychain.flow.DeliveryEndpointIfc =
        ksl.modeling.supplychain.flow.PassThroughStorageEndpoint

    private val demandFilledListener =
        DemandStateChangeListener { d, _, to ->
            if (to.stateId === DemandStateId.Filled) internalDemandFilled(d)
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
        inv.receive(demand)
    }

    override fun fillDemand(demand: SupplyChainModel.Demand) {
        // The IHP listens for the fill so it can ship; the inventory
        // does the actual filling.
        demand.addStateChangeListener(demandFilledListener)
        val inv = myInventory[demand.itemType]
            ?: error("$name has no inventory for ${demand.itemType.name}")
        inv.fillDemand(demand)
    }

    /** Hook fired when an internal inventory fills the demand. */
    protected open fun internalDemandFilled(demand: SupplyChainModel.Demand) {
        prepareDemandForShipment(demand)
        shipDemandToCustomer(demand)
    }

    /** Hands off to [demandCarrier] or transitions through ship+deliver. */
    protected open fun shipDemandToCustomer(demand: SupplyChainModel.Demand) {
        val carrier = demandCarrier
        if (carrier != null) {
            carrier.transportDemand(demand)
        } else {
            demand.ship()
            demand.deliver()
        }
    }

    /** If [demandPreparer] is set, asks it to prepare; otherwise no-op. */
    protected open fun prepareDemandForShipment(demand: SupplyChainModel.Demand) {
        demandPreparer?.prepareDemand(demand)
    }
}
