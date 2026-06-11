package ksl.modeling.supplychain.inventory

import ksl.modeling.supplychain.*
import ksl.modeling.supplychain.findEnclosingSupplyChainModel
import ksl.modeling.supplychain.flow.DeliveryEndpointIfc
import ksl.modeling.supplychain.flow.fulfillAndDispatch
import ksl.modeling.supplychain.flow.receiveForProcessing

import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.ModelElement

/**
 * Inventory-tier cross-dock: a routing node that holds no inventory
 * but receives a demand, forwards an upstream request, and on the
 * upstream's delivery fills the original demand and ships it out
 * through its own [demandCarrier].
 *
 * Slots into a [ksl.modeling.supplychain.network.MultiEchelonNetwork]
 * alongside [InventoryHoldingPoint]s via the common [NetworkNodeIfc]
 * abstraction — both kinds of node share the network's level
 * tracking, attachment plumbing, and transport-strategy wiring.
 *
 * Per-cross-dock statistics:
 * - [numberOfDemandsCrossDockedResponse] — counter of demands that
 *   completed the receive-fill-ship round trip.
 * - [crossDockWaitTimeResponse] — observation: time from
 *   `receive(demand)` to the upstream's delivery (i.e. how long the
 *   original parked at the cross-dock waiting for replenishment).
 *
 * **Phase 1.F migration**: the cross-dock no longer uses a
 * [ksl.modeling.supplychain.flow.DemandForwarder].  The
 * upstream-forwarding mechanics are inlined into [receive], and the
 * round-trip completion lives in [deliveryEndpoint] — a custom
 * [DeliveryEndpointIfc] that fires when the framework's Delivered
 * dispatch routes the upstream's reply back here.
 *
 * The cross-dock still creates a clone for the upstream request.
 * The demand state machine does not permit a single demand to be
 * re-sent upstream from Received/InProcess, so the request-path
 * uses cloning of necessity.  A future evolution
 * (`docs/supply-chain-framework-design.md` §6 "Planned future
 * direction: delivery-path multi-hop (full re-ship)") moves cross-
 * docks off the request path entirely and uses a single demand
 * making a multi-hop journey on the delivery path only.
 *
 * @param parent the parent model element
 * @param initialAvailability availability at the start of each replication
 * @param name optional model-element name
 *
 * See `sc.inventorylayer.CrossDock`
 * See `ksl.modeling.supplychain.facility.CrossDockFacility`
 */
open class InventoryCrossDock @JvmOverloads constructor(
    parent: ModelElement,
    initialAvailability: Boolean = true,
    name: String? = null,
) : DemandFillerAbstract(parent, initialAvailability, name),
    DemandSenderIfc,
    NetworkNodeIfc {

    // -- NetworkNodeIfc fields not already provided by supertypes -------

    override var level: Int = 0

    // demandCarrier is inherited from DemandFillerAbstract (DemandFillerIfc).
    // demandFiller is provided here (DemandSenderIfc).

    override var demandFiller: DemandFillerIfc? = null
    override var demandFillerFinder: DemandFillerFinderIfc? = null

    // -- statistics -----------------------------------------------------

    private val myNumberCrossDocked: Counter =
        Counter(this, name = "${this.name}:NumberCrossDocked")
    private val myNumberCrossDockRejected: Counter =
        Counter(this, name = "${this.name}:NumberCrossDockRejected")
    private val myCrossDockWaitTime: Response =
        Response(this, name = "${this.name}:CrossDockWaitTime")

    /** Per-replication count of demands that completed the round trip. */
    val numberOfDemandsCrossDockedResponse: CounterCIfc get() = myNumberCrossDocked

    /**
     * Per-replication count of demands whose forwarded request was
     * rejected upstream (the round trip failed and the original was
     * rejected rather than fulfilled).
     */
    val numberOfDemandsRejectedResponse: CounterCIfc get() = myNumberCrossDockRejected

    /**
     * Per-observation response of `upstream-delivered-time − receive-time`
     * for each demand routed through the cross-dock.
     */
    val crossDockWaitTimeResponse: ResponseCIfc get() = myCrossDockWaitTime

    // -- delivery endpoint: round-trip completion -----------------------

    /**
     * Fires when an upstream-forwarded request reaches Delivered back
     * at this cross-dock.  Recovers the parked original via
     * [SupplyChainModel.Demand.forwardedFrom], records the wait-time
     * stat, increments the counter, completes the original (fill +
     * dispatch via [demandCarrier]), and terminates the forwarded
     * request at Stored.
     *
     * Replaces the previous [ksl.modeling.supplychain.flow.DemandForwarder]'s
     * `onUpstreamDelivered` callback.  Lifecycle is now driven by the
     * framework's universal Delivered → endpoint dispatch.
     */
    override var deliveryEndpoint: DeliveryEndpointIfc =
        object : DeliveryEndpointIfc {
            override fun onDelivered(demand: SupplyChainModel.Demand) {
                val original = demand.forwardedFrom
                    ?: error(
                        "${this@InventoryCrossDock.name}: forwarded " +
                            "request reached Delivered without a " +
                            "forwardedFrom back-pointer",
                    )
                myCrossDockWaitTime.value = time - original.timeReceived
                myNumberCrossDocked.increment()
                original.fulfillAndDispatch(carrier = demandCarrier)
                // Terminate the forwarded request at Stored — its job
                // is done.  The original continues onward via the
                // outbound carrier and reaches Stored at its eventual
                // destination via that destination's endpoint.
                demand.store()
            }
        }

    /**
     * Listener attached to each forwarded request.  Drives the round
     * trip's two non-delivery outcomes:
     *
     * - **RECEIVED**: tell the upstream filler to actually fill the
     *   request (mirrors the receive-side hook the previous
     *   `DemandForwarder` provided).
     * - **REJECTED**: the upstream could not fulfil the request
     *   (stockout on a non-backloggable demand, item mismatch, or an
     *   unavailable filler).  Recover the parked original via
     *   [SupplyChainModel.Demand.forwardedFrom], propagate the upstream
     *   failure status, and reject the original — un-parking it from
     *   IN_PROCESS.  Without this the original would strand in IN_PROCESS
     *   for the rest of the replication and every cross-dock statistic
     *   would silently undercount (audit finding A).
     *
     * The successful outcome (DELIVERED) is handled by [deliveryEndpoint],
     * which the framework's universal Delivered dispatch invokes.
     */
    private val forwardedRequestListener =
        DemandStateChangeListener { d, _, to ->
            when (to.stateId) {
                DemandStateId.Received -> {
                    val filler = d.filler
                        ?: error(
                            "${this.name}: forwarded request has no filler " +
                                "in RECEIVED state",
                        )
                    filler.fillDemand(d)
                }
                DemandStateId.Rejected -> {
                    val original = d.forwardedFrom
                        ?: error(
                            "${this.name}: rejected forwarded request had no " +
                                "forwardedFrom back-pointer",
                        )
                    myNumberCrossDockRejected.increment()
                    original.setStatus(d.status)
                    original.reject()
                }
                else -> {}
            }
        }

    // -- DemandFillerIfc.receive ----------------------------------------

    /**
     * Forward [demand]'s replenishment request upstream by creating a
     * forwarded request (a clone) that travels to [demandFiller] while
     * the original parks in IN_PROCESS at this cross-dock.  The
     * framework's Delivered dispatch on the forwarded request invokes
     * this cross-dock's [deliveryEndpoint], which completes the
     * round trip.
     */
    override fun receive(demand: SupplyChainModel.Demand) {
        if (!isAvailable) {
            demand.setStatus(DemandStatusCode.FillerUnavailable)
            demand.reject()
            return
        }
        val upstream = demandFiller
            ?: error("$name: no upstream demandFiller; cannot forward demand")
        demand.receiveForProcessing(this)

        val sc = findEnclosingSupplyChainModel()
        val forwardedRequest = sc.createDemand(
            demand.itemType, demand.originalAmountDemanded,
        )
        forwardedRequest.forwardedFrom = demand
        forwardedRequest.setDemandSender(this)
        forwardedRequest.addStateChangeListener(forwardedRequestListener)
        forwardedRequest.sent()
        forwardedRequest.setFiller(upstream)
        upstream.receive(forwardedRequest)
    }

    // -- DemandFillerIfc stubs (routing only — never holds inventory) --

    override fun fillDemand(demand: SupplyChainModel.Demand) {
        // No-op: the cross-dock fulfils demands by forwarding upstream,
        // not by acting on them locally.
    }

    override fun canFillItemType(demand: SupplyChainModel.Demand): Boolean = true
    override fun canFillItemType(type: ItemType): Boolean = true
    override val itemTypes: Collection<ItemType> get() = emptyList()

    override fun negotiate(demand: SupplyChainModel.Demand): DemandMessageIfc? = null

    override fun willReject(demand: SupplyChainModel.Demand): Boolean = false

    override fun determineRequestStatus(
        demand: SupplyChainModel.Demand,
    ): DemandStatusCode = DemandStatusCode.NoStatus

    // -- DemandSenderIfc -------------------------------------------------

    override fun mightRequest(type: ItemType): Boolean = false
}
