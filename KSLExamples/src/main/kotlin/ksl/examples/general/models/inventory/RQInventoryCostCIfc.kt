package ksl.examples.general.models.inventory

import ksl.modeling.variable.ResponseCIfc

/**
 * The cost parameters of an inventory policy: unit cost, cost per order, unit holding cost, and unit
 * backorder cost. Split out so both the mutable inventory element and its read-only view share one
 * definition of the economic inputs.
 */
interface InventoryCostParametersCIfc {
    var costPerUnit: Double
    var costPerOrder: Double
    var unitHoldingCost: Double
    var unitBackOrderCost: Double
}

/**
 * Read-only view of an (r, Q) inventory's cost outputs — the total, ordering, holding, backorder,
 * and combined ordering-and-holding cost responses accumulated per replication — layered on top of
 * the [InventoryCostParametersCIfc] economic inputs.
 */
interface RQInventoryCostCIfc : InventoryCostParametersCIfc {
    val totalCost: ResponseCIfc
    val orderingCost: ResponseCIfc
    val holdingCost: ResponseCIfc
    val backOrderCost: ResponseCIfc
    val orderingAndHoldingCost: ResponseCIfc
}