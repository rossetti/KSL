package ksl.modeling.supplychain

import ksl.simulation.ModelElement

/**
 * Base class for objects that implement [OrderFillerIfc]. Provides the
 * order-receipt orchestration (availability gating → per-demand filler
 * assignment → status determination → batched receipt) and leaves
 * [fill] / [canFillItemType] / [negotiate] to subclasses.
 *
 * @param parent the [SupplyChainModel] this filler belongs to
 * @param initialAvailability availability at the start of each replication
 * @param name optional model-element name
 *
 * @see sc.inventorylayer.OrderFillerAbstract
 */
abstract class OrderFillerAbstract @JvmOverloads constructor(
    parent: ModelElement,
    initialAvailability: Boolean = true,
    name: String? = null,
) : ModelElement(parent, name), OrderFillerIfc {
    // parent is ModelElement (not SupplyChainModel) for the same reason as
    // DemandFillerAbstract — supports nested-inner filler construction.

    /** Availability at the start of each replication. */
    var initialAvailability: Boolean = initialAvailability

    private var _isAvailable: Boolean = initialAvailability

    final override val isAvailable: Boolean
        get() = _isAvailable

    protected fun setAvailability(flag: Boolean) {
        _isAvailable = flag
    }

    override fun initialize() {
        super.initialize()
        _isAvailable = initialAvailability
    }

    /** Used to look up a per-demand filler for each demand on an order. */
    protected var demandFillerFinder: DemandFillerFinderIfc? = null

    /**
     * Receive [order], routing each of its demands to a per-demand filler.
     * Steps (matching Java's `OrderFillerAbstract.receive`):
     *   1. If unavailable, reject the order with [OrderStatusCode.FillerUnavailable].
     *   2. Assign a [DemandFillerIfc] to each demand via [demandFillerFinder];
     *      throw [NoDemandFillerFoundException] if any demand has no eligible filler.
     *   3. Compute the order's status via [determineOrderStatus]; if any
     *      demand will be rejected, reject the order.
     *   4. Otherwise, ask the order to begin batched receipt.
     */
    override fun receive(order: SupplyChainModel.Order) {
        if (!isAvailable) {
            rejectOrder(order, OrderStatusCode.FillerUnavailable)
            return
        }
        setUpDemandFillersOnOrder(order)
        val rejected = determineOrderStatus(order)
        if (rejected) {
            rejectOrder(order, order.status)
            return
        }
        askOrderToBeginReceivingDemands(order)
    }

    /**
     * True iff every demand on [order] can have its item type filled by
     * this filler. Diverges from the Java original, which returned only
     * the last demand's result (preserved here as a clean `all { ... }`).
     */
    override fun canFillItemTypes(order: SupplyChainModel.Order): Boolean =
        order.demands.all { canFillItemType(it) }

    /**
     * Walks each demand, setting its status via its assigned filler and
     * collecting whether any will reject. Returns true iff at least one
     * demand will be rejected.
     */
    protected open fun determineOrderStatus(order: SupplyChainModel.Order): Boolean {
        var rejected = false
        var immediateFill = true
        for (d in order.demands) {
            val f = d.filler
                ?: error("demand $d has no assigned filler")
            val status = f.determineRequestStatus(d)
            d.setStatus(status)
            if (f.willReject(d)) {
                rejected = true
            } else if (status == DemandStatusCode.WillBeBacklogged) {
                immediateFill = false
            }
        }
        order.setStatus(
            when {
                rejected -> OrderStatusCode.DemandRejected
                immediateFill -> OrderStatusCode.ImmediateFill
                else -> OrderStatusCode.WillBeBacklogged
            }
        )
        return rejected
    }

    /** Place [order] in rejected state with the given status code. */
    protected fun rejectOrder(order: SupplyChainModel.Order, status: OrderStatusCode) {
        order.setStatus(status)
        order.reject()
    }

    /** Place [order] in process state. */
    protected fun placeOrderInProcessState(order: SupplyChainModel.Order) {
        order.process()
    }

    /**
     * Assign a [DemandFillerIfc] to each demand on [order] via
     * [demandFillerFinder]. Throws [NoDemandFillerFoundException] if no
     * filler is found for any demand.
     */
    protected open fun setUpDemandFillersOnOrder(order: SupplyChainModel.Order) {
        val finder = demandFillerFinder
            ?: error("demandFillerFinder not set on $name")
        for (d in order.demands) {
            val filler = finder.findDemandFiller(d)
                ?: throw NoDemandFillerFoundException(
                    "No filler found for demand $d in order ${order.name}"
                )
            d.setFiller(filler)
        }
    }

    /**
     * Tell [order] to begin batched receipt. Subclasses may override to
     * resequence demands before receipt.
     */
    protected open fun askOrderToBeginReceivingDemands(order: SupplyChainModel.Order) {
        order.beginReceivingDemands_()
    }

    /**
     * Tell [order] to begin batched processing. Subclasses may override
     * to resequence demands before processing.
     */
    protected open fun askOrderToBeginProcessingDemands(order: SupplyChainModel.Order) {
        order.beginProcessingDemands_()
    }
}
