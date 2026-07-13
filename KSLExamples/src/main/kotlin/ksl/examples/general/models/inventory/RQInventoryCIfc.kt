package ksl.examples.general.models.inventory


/**
 * Read-only (controlled) view of an [RQInventory]: the full inventory state and responses of
 * [InventoryCIfc] plus the (r, Q) cost outputs of [RQInventoryCostCIfc], with the settable initial
 * reorder point and reorder quantity that define the policy.
 */
interface RQInventoryCIfc : InventoryCIfc, RQInventoryCostCIfc {
    var initialReorderPoint: Int
    var initialReorderQty: Int
}