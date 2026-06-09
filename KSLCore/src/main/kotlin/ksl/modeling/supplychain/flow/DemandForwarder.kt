/*
 * Phase 0 — flow substrate.
 *
 * Helper C: DemandForwarder — a demand-forwarding policy.
 *
 * Consolidates the demand-forwarding pattern that's duplicated
 * three times in production code:
 *   - StorageFacilityAbstract.StorageReplenishmentCloner
 *   - CrossDockFacility.receive
 *   - InventoryCrossDock.receive
 * Each one takes a demand it can't satisfy locally, passes an
 * equivalent request to an upstream supplier, and waits for the
 * upstream to deliver before completing the original demand.
 *
 * Internally the forwarder propagates the request upstream by
 * creating a clone of the demand (the framework's mechanism for
 * keeping the original's state context intact while a parallel
 * journey happens upstream) — but this is an implementation detail.
 * Users configure a forwarder by saying who the upstream is and
 * what to do when the upstream delivers; they never touch the
 * clone directly.
 */
package ksl.modeling.supplychain.flow

import ksl.modeling.supplychain.DemandFillerIfc
import ksl.modeling.supplychain.DemandMessageIfc
import ksl.modeling.supplychain.DemandPreparerIfc
import ksl.modeling.supplychain.DemandSenderIfc
import ksl.modeling.supplychain.DemandStatusCode
import ksl.modeling.supplychain.ItemType
import ksl.modeling.supplychain.SupplyChainModel
import ksl.modeling.supplychain.findEnclosingSupplyChainModel
import ksl.simulation.ModelElement

/**
 * A [DemandFillerIfc] that doesn't fulfil demands from local stock
 * — instead it **forwards** each incoming demand to an upstream
 * supplier, waits for delivery, and runs a user-supplied callback
 * when the upstream delivers. Analogous to a real-world *freight
 * forwarder* or *drop-shipper*: takes the order, places it with a
 * supplier, takes delivery, hands the goods to the original
 * customer.
 *
 * Typical use is to assign a [DemandForwarder] to a routing
 * node's `demandFiller` slot. When the network sends a demand
 * to that node, the forwarder receives it for processing,
 * propagates an equivalent request upstream, and on upstream
 * delivery runs whatever finalisation the node needs (typically
 * `original.fulfillAndDispatch(carrier)` to ship the original
 * onward, or letting a receiving-dock mechanism handle it).
 *
 * On `receive(demand)` the forwarder:
 *
 * 1. Receives the demand for processing via
 *    [receiveForProcessing] — the demand transitions to
 *    IN_PROCESS and waits here for upstream delivery.
 * 2. Sends an equivalent request to the upstream filler resolved
 *    by [upstreamSupplier].
 * 3. When the upstream receives the forwarded request, the
 *    forwarder asks the upstream to fill it
 *    (`filler.fillDemand(...)`) unless [shouldFillOnReceive]
 *    returns false (used to opt out for upstreams that drive
 *    their own fill cycle, like a routing facility).
 * 4. When the upstream eventually delivers the forwarded request,
 *    [onUpstreamDelivered] is invoked (if non-null) with the
 *    *original* demand — the node now does whatever is needed to
 *    complete it (typically fulfil and dispatch via the node's
 *    outbound carrier).
 *
 * The forwarder is itself a persistent [DemandFillerIfc] — its
 * remaining surface (`fillDemand`, `negotiate`, item-type
 * predicates, etc.) is routing-only stubs.
 *
 * @param parent the model element that owns this forwarder
 *        (typically the routing node it's attached to)
 * @param upstreamSupplier resolves the upstream filler at call
 *        time, so callers can configure the upstream after
 *        construction (the typical pattern when a node's
 *        `demandFiller` is set by network attachment)
 * @param sender the [DemandSenderIfc] to stamp on the forwarded
 *        request — typically the routing node so the upstream
 *        knows where to return the delivery
 * @param onUpstreamDelivered optional callback fired when the
 *        upstream delivers the forwarded request; receives the
 *        original demand that was forwarded. Leave null when a
 *        receiving-dock mechanism will handle finalisation.
 * @param shouldFillOnReceive predicate that gates whether to ask
 *        the upstream to `fillDemand` on RECEIVED. Defaults to
 *        always true; pass a predicate returning false for
 *        upstreams that drive their own fill cycle.
 * @param name optional model-element name
 *
 * @see SupplyChainModel.Demand.receiveForProcessing
 * @see SupplyChainModel.Demand.fulfillAndDispatch
 */
open class DemandForwarder @JvmOverloads constructor(
    parent: ModelElement,
    val upstreamSupplier: () -> DemandFillerIfc?,
    val sender: DemandSenderIfc,
    val onUpstreamDelivered: ((original: SupplyChainModel.Demand) -> Unit)? = null,
    val shouldFillOnReceive: (DemandFillerIfc) -> Boolean = { true },
    name: String? = null,
) : ModelElement(parent, name), DemandFillerIfc {

    private val supplyChainModel: SupplyChainModel = findEnclosingSupplyChainModel()

    /**
     * Lifecycle observer attached to every forwarded request.
     * When the upstream receives the request, drives its
     * `fillDemand` (subject to [shouldFillOnReceive]); when the
     * upstream delivers, invokes [onUpstreamDelivered] with the
     * original demand.
     *
     * Internally the forwarded request is a clone of the original
     * — the clone carries the trip upstream and back; the original
     * waits at this node. The clone-to-original link is recovered
     * via [SupplyChainModel.Demand.forwardedFrom] (a typed property
     * on Demand). The clone vocabulary is an implementation detail
     * not exposed in the public API.
     */
    private val forwardedRequestObserver = object : DemandLifecycleObserver {
        override fun onReceived(d: SupplyChainModel.Demand) {
            val filler = d.filler
                ?: error("$name: forwarded request in RECEIVED state has no filler")
            if (shouldFillOnReceive(filler)) filler.fillDemand(d)
        }
        override fun onDelivered(d: SupplyChainModel.Demand) {
            val callback = onUpstreamDelivered ?: return
            val original = d.forwardedFrom
                ?: error("$name: forwarded request delivered without an original")
            callback(original)
        }
    }

    override fun receive(demand: SupplyChainModel.Demand) {
        demand.receiveForProcessing(this)

        val upstream = upstreamSupplier()
            ?: error("$name: upstream filler is null; cannot forward demand")

        val forwardedRequest = supplyChainModel.createDemand(
            demand.itemType, demand.originalAmountDemanded,
        )
        forwardedRequest.forwardedFrom = demand
        forwardedRequest.setDemandSender(sender)
        forwardedRequest.observe(forwardedRequestObserver)
        forwardedRequest.sent()
        forwardedRequest.setFiller(upstream)
        upstream.receive(forwardedRequest)
    }

    // --- DemandFillerIfc stubs --------------------------------------------
    // Routing only: a forwarder never fills demands from local stock; it
    // parks originals and propagates requests upstream. The remaining
    // surface is no-op.

    override fun fillDemand(demand: SupplyChainModel.Demand) {}
    override fun negotiate(demand: SupplyChainModel.Demand): DemandMessageIfc? = null
    override fun canFillItemType(demand: SupplyChainModel.Demand): Boolean = false
    override fun canFillItemType(type: ItemType): Boolean = false
    override val itemTypes: Collection<ItemType> = emptyList()
    override fun determineRequestStatus(
        demand: SupplyChainModel.Demand,
    ): DemandStatusCode = DemandStatusCode.NoStatus
    override fun willReject(demand: SupplyChainModel.Demand): Boolean = false
    override var demandCarrier: ksl.modeling.supplychain.DemandCarrierIfc? = null
    override var demandPreparer: DemandPreparerIfc? = null
    override val isAvailable: Boolean = true
}
