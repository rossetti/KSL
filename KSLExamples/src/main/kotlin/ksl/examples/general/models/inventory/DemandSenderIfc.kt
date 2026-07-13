package ksl.examples.general.models.inventory

/**
 * Functional interface for a component that forwards a [DemandCreator.Demand] on to some receiver,
 * tagging itself as the demand's sender. The sending counterpart to [DemandReceiverIfc].
 */
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