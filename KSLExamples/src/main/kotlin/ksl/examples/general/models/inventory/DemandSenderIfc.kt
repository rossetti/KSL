package ksl.examples.general.models.inventory

fun interface DemandSenderIfc {

    /**
     * Represents the action of sending the demand specified to some receiver.
     * The demand sender is responsible for updating the demand's demand sender to itself,
     * and then sending the demand to the appropriate receiver.
     *
     * @param demand
     */
    fun sendDemand(demand: DemandCreator.Demand)
}