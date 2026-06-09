package ksl.modeling.supplychain

/**
 * Default [DemandFillerFinderIfc] implementation backed by a
 * customer-to-supplier map. Each [DemandSenderIfc] (customer) maps to
 * at most one [DemandFillerIfc] (supplier); a supplier may serve many
 * customers.
 *
 * @see sc.inventorylayer.DemandFillerFinder
 */
class DemandFillerFinder : DemandFillerFinderIfc {

    // LinkedHashMap per porting plan §4.3 — Java used HashMap.
    private val myCustomerSupplierMap: MutableMap<DemandSenderIfc, DemandFillerIfc> =
        linkedMapOf()

    /** True iff [customer] has been registered. */
    fun containsCustomer(customer: DemandSenderIfc): Boolean =
        customer in myCustomerSupplierMap

    /** True iff [filler] appears as a supplier for at least one customer. */
    fun containsSupplier(filler: DemandFillerIfc): Boolean =
        myCustomerSupplierMap.containsValue(filler)

    /** Read-only view of all registered customers, in insertion order. */
    val customers: Set<DemandSenderIfc> get() = myCustomerSupplierMap.keys

    /** Number of registered customer→supplier mappings. */
    val size: Int get() = myCustomerSupplierMap.size

    /** True iff no mappings have been registered. */
    val isEmpty: Boolean get() = myCustomerSupplierMap.isEmpty()

    /**
     * Register or replace the supplier for [customer]. Returns the
     * prior supplier, if any.
     */
    fun put(
        customer: DemandSenderIfc,
        supplier: DemandFillerIfc,
    ): DemandFillerIfc? = myCustomerSupplierMap.put(customer, supplier)

    /**
     * Look up the filler for the sender of [demand]. Returns null if
     * the demand's sender has no registered supplier. (Java threw
     * [NoDemandFillerFoundException] here; we honor the nullable
     * contract of [DemandFillerFinderIfc] and let the caller decide.)
     */
    override fun findDemandFiller(
        demand: SupplyChainModel.Demand,
    ): DemandFillerIfc? = myCustomerSupplierMap[demand.demandSender]
}
