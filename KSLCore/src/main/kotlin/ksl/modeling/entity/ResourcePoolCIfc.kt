package ksl.modeling.entity

import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponseCIfc

interface ResourcePoolCIfc {
    val numBusyUnits: TWResponseCIfc
    val fractionBusyUnits: ResponseCIfc
    val resources: List<ResourceCIfc>
    val numAvailableUnits: Int
    val hasAvailableUnits: Boolean
    val capacity: Int
    val numBusy: Int
    val fractionBusy: Double
    var initialDefaultResourceSelectionRule: ResourceSelectionRuleIfc
    var initialDefaultResourceAllocationRule: ResourceAllocationRuleIfc
}