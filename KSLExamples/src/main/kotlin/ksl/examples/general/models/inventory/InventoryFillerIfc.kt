package ksl.examples.general.models.inventory

/**
 *  Provides a mechanism for filling a supplied demand from stored inventory.
 *  The implementer of this interface promises to eventually fill the demand
 *  associated with the filling request.
 */
fun interface InventoryFillerIfc {
    /**
     * Represents a request for the demand to be provided by the filler
     *
     * @param demand
     */
    fun fillInventory(demand: DemandCreator.Demand)
}