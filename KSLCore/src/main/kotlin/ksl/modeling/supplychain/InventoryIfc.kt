package ksl.modeling.supplychain

/**
 * Read-only view of an inventory's current quantities and backlog.
 *
 * @see sc.inventorylayer.InventoryIfc
 */
interface InventoryIfc {
    /** Units currently on hand. */
    val amountOnHand: Int

    /** Units currently on order (in replenishment). */
    val amountOnOrder: Int

    /** The inventory's backlog info. */
    val backLogInfo: BackLogInfoIfc

    /**
     * [amountOnHand] + [amountOnOrder] − backlog, the usual
     * inventory-position metric used by reorder policies.
     */
    val inventoryPosition: Int
}
