package ksl.examples.general.models.inventory


interface RQInventoryCIfc : InventoryCIfc, RQInventoryCostCIfc {
    var initialReorderPoint: Int
    var initialReorderQty: Int
}