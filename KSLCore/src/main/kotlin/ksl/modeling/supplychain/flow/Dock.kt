/*
 * Phase 1.E — default Dock implementation.
 *
 * A Dock is a DeliveryEndpointIfc that holds a demand in the
 * Delivered state for a service time before invoking the
 * destination's terminal action (store or re-ship).  Models the
 * unload-time delay between "carrier dropped it off" and "items
 * available at the destination."
 *
 * See `docs/supply-chain-framework-design.md` §3.5.
 */
package ksl.modeling.supplychain.flow

import ksl.modeling.queue.Queue
import ksl.modeling.supplychain.DemandCarrierIfc
import ksl.modeling.supplychain.SupplyChainModel
import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * Terminal action a [Dock] performs once its service-time delay
 * elapses for an arriving demand.
 *
 * - [Store] (default) — call `demand.store()`, finishing the demand
 *   at the storing destination's inventory.
 * - [Ship] — re-ship the demand to its next multi-hop destination
 *   via [Ship.routingTable] and [Ship.outboundCarrier].
 */
sealed class DockTerminalAction {
    /** Finalise the demand at this destination via `demand.store()`. */
    object Store : DockTerminalAction()

    /**
     * Re-ship the demand to its next hop in a multi-hop pass-through
     * (a cross-dock).  The dock looks up the next hop in
     * [routingTable] using the demand's
     * [SupplyChainModel.Demand.demandSender] (the final
     * destination), calls `demand.ship()` to transition Delivered →
     * Shipped, and hands the demand to [outboundCarrier].
     *
     * @property routingTable resolves `finalDestination → nextHop`
     *           for every demand that may arrive at this dock
     * @property outboundCarrier carrier responsible for the next leg
     */
    data class Ship(
        val routingTable: RoutingTableIfc,
        val outboundCarrier: DemandCarrierIfc,
    ) : DockTerminalAction()
}

/**
 * Single-server discrete-event dock that interposes a service-time
 * delay between a demand reaching [DemandStateId.Delivered][ksl.modeling.supplychain.DemandStateId.Delivered]
 * and the terminal action ([store][SupplyChainModel.Demand.store]
 * or [ship][SupplyChainModel.Demand.ship]) chosen by the dock's
 * configuration.
 *
 * Assigned to a destination's
 * [ksl.modeling.supplychain.inventory.NetworkNodeIfc.deliveryEndpoint]
 * slot to model unload-time behaviour at that node.  Replaces the
 * default [PassThroughStorageEndpoint] (which has zero service time)
 * with a configurable service-time variant.
 *
 * The default implementation is a strict FIFO single-server queue
 * with statistics matching a service station:
 * - [numberServedResponse] — counter of demands that completed
 *   service this replication.
 * - [timeInDockResponse] — per-demand observation of (service-end
 *   time − arrival time at the dock).
 * - [utilizationResponse] — time-weighted busy fraction (1.0 when
 *   a demand is in service, 0.0 when idle).
 * - [queueSize] — current size of the waiting line.
 *
 * Subclasses may override [beginService] and [applyTerminalAction]
 * to integrate with KSL's process-view modelling (resources,
 * transporters, conveyors) — that's the long-term integration path
 * for a `ProcessViewDock`.
 *
 * @param parent the model element that owns this dock (typically
 *        the [ksl.modeling.supplychain.inventory.NetworkNodeIfc]
 *        whose `deliveryEndpoint` slot this dock fills)
 * @param serviceTime per-demand service time distribution; default
 *        [ConstantRV.ZERO] (degenerate to immediate pass-through)
 * @param terminalAction what to do once service ends — default
 *        [DockTerminalAction.Store]
 * @param name optional model-element name
 */
open class Dock @JvmOverloads constructor(
    parent: ModelElement,
    serviceTime: RVariableIfc = ConstantRV.ZERO,
    val terminalAction: DockTerminalAction = DockTerminalAction.Store,
    name: String? = null,
) : ModelElement(parent, name), DeliveryEndpointIfc {

    private val myServiceTime: RandomVariable = RandomVariable(this, serviceTime)

    /**
     * Service-time distribution.  Reassigning swaps the underlying
     * source on the inner [RandomVariable] (per porting-plan §4.1).
     */
    var serviceTime: RVariableIfc
        get() = myServiceTime.initialRandomSource
        set(value) {
            myServiceTime.initialRandomSource = value
        }

    private val myQueue: Queue<SupplyChainModel.Demand> =
        Queue(this, "${this.name}:Queue")

    private val myNumberServed: Counter =
        Counter(this, name = "${this.name}:NumberServed")

    private val myTimeInDock: Response =
        Response(this, name = "${this.name}:TimeInDock")

    private val myUtilization: TWResponse =
        TWResponse(this, name = "${this.name}:Utilization")

    private val serviceEndAction = ServiceEndAction()

    /** Demand currently being serviced, or null if idle. */
    private var inService: SupplyChainModel.Demand? = null

    /** Count of demands that finished service this replication. */
    val numberServedResponse: CounterCIfc get() = myNumberServed

    /** Per-observation time-in-dock response (arrival → service end). */
    val timeInDockResponse: ResponseCIfc get() = myTimeInDock

    /** Time-weighted utilisation (busy fraction). */
    val utilizationResponse: TWResponseCIfc get() = myUtilization

    /** Current number of demands waiting in the queue. */
    val queueSize: Int get() = myQueue.size

    /** True iff a demand is currently being serviced. */
    val isBusy: Boolean get() = inService != null

    // -- DeliveryEndpointIfc ---------------------------------------------

    override fun onDelivered(demand: SupplyChainModel.Demand) {
        if (inService == null) {
            beginService(demand)
        } else {
            myQueue.enqueue(demand)
        }
    }

    // -- internal service mechanics -------------------------------------

    /**
     * Hook fired when [demand] becomes the in-service demand.
     * Default schedules a service-end event after a sampled
     * service time.  Subclasses may override to integrate with KSL
     * process-view primitives (e.g. seize a resource, run a
     * `Process` block).
     */
    protected open fun beginService(demand: SupplyChainModel.Demand) {
        inService = demand
        myUtilization.value = 1.0
        serviceEndAction.schedule(myServiceTime.value, message = demand)
    }

    /**
     * Hook fired when service ends for [demand].  Default invokes
     * the configured [terminalAction] — calling `demand.store()` or
     * routing + re-ship per [DockTerminalAction.Ship].
     *
     * Subclasses overriding this should call `demand.store()` or
     * `demand.ship()` themselves to finalise the demand's lifecycle.
     */
    protected open fun applyTerminalAction(demand: SupplyChainModel.Demand) {
        when (val action = terminalAction) {
            DockTerminalAction.Store -> demand.store()
            is DockTerminalAction.Ship -> {
                val nextHop = action.routingTable.nextHop(demand.demandSender)
                    ?: error(
                        "$name: no route to final destination " +
                            "${demand.demandSender?.name ?: "(null)"}",
                    )
                // Update demand's filler to the dock's owning node so
                // the carrier sees the right "from" for the next leg.
                // For now we just transition the demand to Shipped and
                // hand to the carrier; the carrier route is keyed on
                // the demand's demandSender (final destination) and
                // the routing table's next hop is informational for
                // user code that wants to inspect or override.
                @Suppress("UNUSED_VARIABLE") val routedTo = nextHop
                demand.ship()
                action.outboundCarrier.transportDemand(demand)
            }
        }
    }

    private inner class ServiceEndAction : EventAction<SupplyChainModel.Demand>() {
        override fun action(event: KSLEvent<SupplyChainModel.Demand>) {
            val finished = event.message!!
            // Record stats before invoking the terminal action — the
            // terminal action may modify demand state and we want the
            // time-in-dock to reflect the arrival-to-service-end span.
            myNumberServed.increment()
            myTimeInDock.value = time - finished.timeDelivered

            // Apply terminal action (store or re-ship).
            applyTerminalAction(finished)

            // Pick up the next queued demand, or go idle.
            val next = if (myQueue.isNotEmpty) myQueue.removeNext() else null
            if (next != null) {
                beginService(next)
            } else {
                inService = null
                myUtilization.value = 0.0
            }
        }
    }

    override fun initialize() {
        super.initialize()
        inService = null
        myUtilization.value = 0.0
    }
}
