package ksl.examples.general.models.inventory

import ksl.modeling.variable.ResponseCIfc

interface InventoryCostParametersCIfc {
    var costPerUnit: Double
    var costPerOrder: Double
    var unitHoldingCost: Double
    var unitBackOrderCost: Double
}

interface RQInventoryCostCIfc : InventoryCostParametersCIfc {
    val totalCost: ResponseCIfc
    val orderingCost: ResponseCIfc
    val holdingCost: ResponseCIfc
    val backOrderCost: ResponseCIfc
    val orderingAndHoldingCost: ResponseCIfc
}