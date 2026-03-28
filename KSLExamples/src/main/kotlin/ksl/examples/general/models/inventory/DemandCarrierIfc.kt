package ksl.examples.general.models.inventory

fun interface DemandCarrierIfc {
    fun transport(demand: DemandCreator.Demand)
}