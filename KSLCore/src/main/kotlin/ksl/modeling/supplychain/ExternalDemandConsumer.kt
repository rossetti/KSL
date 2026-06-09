package ksl.modeling.supplychain

/**
 * A [DemandSenderIfc] that represents external (outside-the-modeled-
 * network) demand consumption. Network-level carriers (e.g.
 * `NetworkDemandCarrierByTime`) may permit zero-delay delivery to
 * implementers when no explicit transport time has been configured
 * for the (filler, this) pair.
 *
 * The canonical implementer is
 * `ksl.modeling.supplychain.inventory.DemandGenerator`.
 */
interface ExternalDemandConsumer : DemandSenderIfc
