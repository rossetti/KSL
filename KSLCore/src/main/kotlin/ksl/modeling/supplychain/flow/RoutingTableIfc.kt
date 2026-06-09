/*
 * Phase 1.E support: routing-table contract for multi-hop docks.
 *
 * A receiving dock configured to re-ship demands (the pass-through
 * cross-dock case) consults a routing table to map each demand's
 * final destination to its next hop.
 *
 * The framework defines the contract here in `flow`; concrete
 * implementations live in the network layer
 * (`ksl.modeling.supplychain.network`) where topology knowledge is
 * available to populate the table.
 */
package ksl.modeling.supplychain.flow

import ksl.modeling.supplychain.DemandSenderIfc
import ksl.modeling.supplychain.inventory.NetworkNodeIfc

/**
 * Lookup table mapping a demand's final destination
 * ([SupplyChainModel.Demand.demandSender][ksl.modeling.supplychain.SupplyChainModel.Demand.demandSender])
 * to the next intermediate hop along its multi-hop journey.  Used
 * by a [DockTerminalAction.Ship] (or any custom re-ship endpoint)
 * to decide where to forward an arriving demand.
 *
 * Implementations are typically pre-populated at network-build
 * time by walking the topology — at a cross-dock, the next hop
 * toward any known final destination is the first edge on the
 * unique path through the arborescence.
 *
 * @see DockTerminalAction.Ship
 */
interface RoutingTableIfc {
    /**
     * Return the next hop a demand bound for [finalDestination]
     * should be forwarded to, or null if no route exists.  A null
     * return is a routing error and the caller is expected to
     * surface it (typically by throwing).
     */
    fun nextHop(finalDestination: DemandSenderIfc?): NetworkNodeIfc?
}
