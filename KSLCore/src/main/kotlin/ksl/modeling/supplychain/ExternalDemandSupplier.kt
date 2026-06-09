package ksl.modeling.supplychain

/**
 * A [DemandFillerIfc] that represents an external (outside-the-
 * modeled-network) source of supply with infinite stock and a
 * lead time. Network-level carriers may permit zero-delay shipping
 * from implementers when no explicit transport time has been
 * configured for the (this, customer) pair.
 *
 * The canonical implementer is
 * `ksl.modeling.supplychain.inventory.LeadTimeDemandFiller`.
 */
interface ExternalDemandSupplier : DemandFillerIfc
