package ksl.examples.general.models.inventory

fun interface DemandReceiverIfc {

    /**
     * Represents an arrival of demand to be processed by the receiver
     *
     * @param demand
     */
    fun receive(demand: DemandCreator.Demand)

}