package ksl.modeling.supplychain.spec

import kotlinx.serialization.Serializable

/**
 * The canonical, serializable description of a multi-echelon
 * supply-chain network — the single data form that the Kotlin DSL, the
 * TOML/JSON loaders, and programmatic generators all produce, and that
 * `SupplyChainBuilder` consumes to instantiate a running
 * `MultiEchelonNetwork`.
 *
 * Topology is a **flat node list** with `parent` references rather than
 * nested structures: this handles arbitrary multi-tier depth, is
 * reorder- and refactor-tolerant, and makes validation (cycle
 * detection, one-root, reference resolution) straightforward.
 *
 * The spec is pure data — no references to KSL runtime types.  Validate
 * it with [validate] before building.
 *
 * @param name network name
 * @param transportStrategy how shipments move between nodes
 * @param items every item type the network carries
 * @param nodes every IHP / cross-dock, each pointing at its supplier
 *        via [NodeSpec.parent]
 * @param demandGenerators customer-demand arrival processes at nodes
 * @param costFormulations cost formulations to attach (after topology)
 *
 * @see SupplyChainBuilder
 * @see validate
 */
@Serializable
data class NetworkSpec(
    val name: String,
    val transportStrategy: TransportStrategySpec = TransportStrategySpec.SharedCarrier,
    val items: List<ItemSpec>,
    val nodes: List<NodeSpec>,
    val demandGenerators: List<DemandGeneratorSpec> = emptyList(),
    val costFormulations: List<CostFormulationSpec> = emptyList(),
) {
    companion object
}

/**
 * Serializable description of an item type.
 *
 * @param name unique item name
 * @param leadTime the external-supplier production lead time for this item
 * @param weight unit weight (drives weight-based formation and load weight)
 * @param cube unit cube
 * @param unitCost unit cost (drives holding / in-transit / builder-holding cost)
 */
@Serializable
data class ItemSpec(
    val name: String,
    val leadTime: RVSpec,
    val weight: Double = 1.0,
    val cube: Double = 1.0,
    val unitCost: Double = 1.0,
)

/** The kind of network node. */
enum class NodeType {
    /** Inventory holding point — holds stock, runs a replenishment policy. */
    IHP,

    /** Cross-dock — routing hub, holds no inventory. */
    CD,
}

/**
 * Serializable description of a network node and its edge to its
 * supplier.
 *
 * @param name unique node name
 * @param type [NodeType.IHP] or [NodeType.CD]
 * @param parent the supplier: another node's [name], or the sentinel
 *        [EXTERNAL_SUPPLIER]
 * @param transportTimeFromParent transport time on the supplier → this
 *        edge; `null` means immediate transport
 * @param inventory the inventories this node holds (must be empty for a
 *        [NodeType.CD])
 * @param enableShipmentFormation install a load-forming carrier on this
 *        node's outbound (requires [TransportStrategySpec.PerIHPTimeBased])
 * @param shipmentFormationFromParent per-edge formation policy toward
 *        this node's supplier (requires the supplier to be
 *        formation-enabled)
 */
@Serializable
data class NodeSpec(
    val name: String,
    val type: NodeType,
    val parent: String,
    val transportTimeFromParent: RVSpec? = null,
    val inventory: List<InventorySpec> = emptyList(),
    val enableShipmentFormation: Boolean = false,
    val shipmentFormationFromParent: ShipmentFormationSpec? = null,
) {
    companion object {
        /** Sentinel [parent] value: attach to the network's external supplier. */
        const val EXTERNAL_SUPPLIER = "EXTERNAL_SUPPLIER"
    }
}

/**
 * Serializable description of one inventory (one item type) held at a
 * node.
 *
 * @param itemTypeName the [ItemSpec.name] this inventory carries
 * @param policy the replenishment policy
 * @param initialOnHand stock at the start of each replication
 */
@Serializable
data class InventorySpec(
    val itemTypeName: String,
    val policy: PolicySpec,
    val initialOnHand: Int,
)

/**
 * Serializable description of a customer-demand arrival process at a
 * node.
 *
 * @param node the [NodeSpec.name] where demand arrives
 * @param itemTypeName the [ItemSpec.name] demanded
 * @param interArrival inter-arrival time distribution
 * @param name optional generator name
 * @param transportTime transport time on the node → generator edge;
 *        `null` means immediate
 * @param shipmentFormation per-edge formation toward this generator
 *        (requires the node to be formation-enabled)
 */
@Serializable
data class DemandGeneratorSpec(
    val node: String,
    val itemTypeName: String,
    val interArrival: RVSpec,
    val name: String? = null,
    val transportTime: RVSpec? = null,
    val shipmentFormation: ShipmentFormationSpec? = null,
)
