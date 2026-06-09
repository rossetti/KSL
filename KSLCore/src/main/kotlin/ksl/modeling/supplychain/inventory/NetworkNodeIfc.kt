package ksl.modeling.supplychain.inventory

import ksl.modeling.supplychain.DemandFillerIfc
import ksl.modeling.supplychain.DemandSenderIfc
import ksl.modeling.supplychain.flow.DeliveryEndpointIfc
import ksl.modeling.supplychain.flow.PassThroughStorageEndpoint

/**
 * Common abstraction over the node types that may participate in a
 * [ksl.modeling.supplychain.network.MultiEchelonNetwork]'s
 * arborescent tree: today [InventoryHoldingPoint] (holds inventory)
 * and [InventoryCrossDock] (routes demand through without holding
 * inventory).
 *
 * The network keys its topology, level tracking, and per-node
 * demand-generator attachment on this interface so that a single
 * carrier-wiring policy ([ksl.modeling.supplychain.network.TransportStrategy])
 * applies uniformly to both kinds of node.
 *
 * Extends [DemandFillerIfc] (for the [DemandFillerIfc.demandCarrier]
 * slot, the receive/fill API, and `name`/`isAvailable` from the
 * inherited identity / availability interfaces) and [DemandSenderIfc]
 * (for the [DemandSenderIfc.demandFiller] slot used to point at an
 * upstream supplier).
 */
interface NetworkNodeIfc : DemandFillerIfc, DemandSenderIfc {
    /**
     * Tier number assigned by the owning network on attachment:
     * 1 = directly attached to the external supplier, 2 = attached
     * to a level-1 node, and so on. Zero until attached.
     */
    var level: Int

    /**
     * Endpoint invoked when a demand reaches Delivered with this
     * node as its destination.  Owns the Delivered → Stored (or
     * Delivered → Shipped for multi-hop pass-through) transition.
     *
     * Default: [PassThroughStorageEndpoint] (immediate `store()`).
     * A node modelling unload time replaces this with a `Dock`; a
     * pass-through cross-dock replaces this with a routing
     * endpoint.
     *
     * See `docs/supply-chain-framework-design.md` §3.5.
     */
    var deliveryEndpoint: DeliveryEndpointIfc
}
