package ksl.modeling.supplychain.spec

import ksl.modeling.supplychain.ItemType
import ksl.modeling.supplychain.SupplyChainModel
import ksl.modeling.supplychain.cost.CostParams
import ksl.modeling.supplychain.cost.DefaultMultiEchelonCostFormulation
import ksl.modeling.supplychain.cost.PerNodeIHPCostFormulation
import ksl.modeling.supplychain.inventory.BackLogQueue
import ksl.modeling.supplychain.inventory.Inventory
import ksl.modeling.supplychain.inventory.InventoryHoldingPoint
import ksl.modeling.supplychain.inventory.InventoryPolicyReorderPointOrderUpToLevelPeriodic
import ksl.modeling.supplychain.inventory.NetworkNodeIfc
import ksl.modeling.supplychain.network.MultiEchelonNetwork
import ksl.modeling.supplychain.network.ShipmentFormation
import ksl.modeling.supplychain.network.TransportStrategy
import ksl.modeling.supplychain.transport.DemandLoadBuilder
import ksl.modeling.supplychain.transport.TimeBasedNetworkDemandCarrier
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.LognormalRV
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.random.rvariable.TriangularRV
import ksl.utilities.random.rvariable.UniformRV

/**
 * The result of [SupplyChainBuilder.build]: the running
 * [MultiEchelonNetwork] plus the handles a caller needs to post-mutate
 * it (the v1 escape hatch for custom carriers, backlog policies,
 * delivery endpoints, `RULE` formation, and custom cost formulations).
 *
 * @param supplyChainModel the [SupplyChainModel] created to hold the network
 * @param network the instantiated, ready-to-simulate network
 * @param itemsByName built [ItemType]s keyed by [ItemSpec.name]
 * @param nodesByName built nodes keyed by [NodeSpec.name]
 */
data class BuildResult(
    val supplyChainModel: SupplyChainModel,
    val network: MultiEchelonNetwork,
    val itemsByName: Map<String, ItemType>,
    val nodesByName: Map<String, NetworkNodeIfc>,
)

/**
 * Instantiates a running [MultiEchelonNetwork] from a [NetworkSpec].
 *
 * This is the single consumer of the spec layer (§4 of the DSL plan):
 * the Kotlin DSL and the TOML/JSON loaders all *produce* a
 * [NetworkSpec]; this object is the only thing that *consumes* one and
 * touches runtime framework types.
 *
 * The build follows the framework's hardened construction-order
 * contract — item types, then nodes in topological (parent-before-child)
 * order with their supplier edges and inventories, then demand
 * generators, and finally cost formulations **last**, after the topology
 * is final.  That ordering is exactly what the cost-formulation ordering
 * and coverage guards require, so the builder is the canonical
 * "do-it-right" path.
 *
 * @see NetworkSpec
 * @see BuildResult
 */
object SupplyChainBuilder {

    /**
     * Build [spec] under [model].  Validates first via
     * [NetworkSpec.validate] and throws [IllegalArgumentException] with
     * every problem listed if the spec is malformed.
     *
     * @param model the enclosing KSL [Model]
     * @param spec the network description
     * @return a [BuildResult] holding the network and its name maps
     * @throws IllegalArgumentException if [spec] fails validation
     */
    fun build(model: Model, spec: NetworkSpec): BuildResult {
        val errors = spec.validate()
        require(errors.isEmpty()) {
            "NetworkSpec '${spec.name}' is invalid:\n" +
                errors.joinToString("\n") { " - ${it.message}" }
        }

        val sc = SupplyChainModel(model, name = spec.name)
        val net = MultiEchelonNetwork(
            sc,
            name = "${spec.name}-network",
            transportStrategy = materializeStrategy(spec.transportStrategy, sc),
        )

        // -- item types -------------------------------------------------
        val itemsByName = LinkedHashMap<String, ItemType>()
        for (item in spec.items) {
            val type = net.addItemType(
                item.name, materialize(item.leadTime), item.weight, item.cube,
            )
            type.unitCost = item.unitCost
            itemsByName[item.name] = type
        }

        // -- nodes (parent before child) + supplier edges + inventories -
        val nodesByName = LinkedHashMap<String, NetworkNodeIfc>()
        for (nodeSpec in topologicalOrder(spec.nodes)) {
            val node: NetworkNodeIfc = when (nodeSpec.type) {
                NodeType.IHP ->
                    net.addInventoryHoldingPoint(nodeSpec.name, nodeSpec.enableShipmentFormation)
                NodeType.CD ->
                    net.addInventoryCrossDock(nodeSpec.name, nodeSpec.enableShipmentFormation)
            }
            nodesByName[nodeSpec.name] = node

            val transportTime = nodeSpec.transportTimeFromParent?.let { materialize(it) }
            val formation = nodeSpec.shipmentFormationFromParent?.let { materialize(it) }
            if (nodeSpec.parent == NodeSpec.EXTERNAL_SUPPLIER) {
                net.attachToExternalSupplier(node, transportTime, formation)
            } else {
                net.attachToSupplier(
                    nodesByName.getValue(nodeSpec.parent), node, transportTime, formation,
                )
            }

            // Validation guarantees a CD carries no inventory.
            if (node is InventoryHoldingPoint) {
                for (inv in nodeSpec.inventory) {
                    addInventory(node, itemsByName.getValue(inv.itemTypeName), inv)
                }
            }
        }

        // -- demand generators ------------------------------------------
        for (dg in spec.demandGenerators) {
            net.attachDemandGenerator(
                supplier = nodesByName.getValue(dg.node),
                type = itemsByName.getValue(dg.itemTypeName),
                timeUntilNext = materialize(dg.interArrival),
                name = dg.name,
                transportTime = dg.transportTime?.let { materialize(it) },
                shipmentFormation = dg.shipmentFormation?.let { materialize(it) },
            )
        }

        // -- cost formulations LAST (ordering / coverage guards) --------
        for (cf in spec.costFormulations) {
            when (cf) {
                is CostFormulationSpec.Default ->
                    DefaultMultiEchelonCostFormulation(net, cf.params.toCostParams(), cf.name)
                is CostFormulationSpec.PerNodeIHP ->
                    PerNodeIHPCostFormulation(
                        net,
                        defaultParams = cf.default.toCostParams(),
                        overrides = cf.overrides.mapValues { it.value.toCostParams() },
                        name = cf.name,
                    )
            }
        }

        return BuildResult(sc, net, itemsByName, nodesByName)
    }

    /**
     * Materialize an [RVSpec] into the KSL random variable carrying its
     * stream number — the single choke point for stream-number handling
     * (CLAUDE.md §4.1).  Reproducibility is the spec author's
     * responsibility; the builder does no implicit stream assignment.
     *
     * @param spec the random-variable description
     * @return the corresponding [RVariableIfc]
     */
    fun materialize(spec: RVSpec): RVariableIfc = when (spec) {
        is RVSpec.Constant -> ConstantRV(spec.value)
        is RVSpec.Exponential -> ExponentialRV(spec.mean, streamNum = spec.stream)
        is RVSpec.Uniform -> UniformRV(spec.min, spec.max, streamNum = spec.stream)
        is RVSpec.Triangular ->
            TriangularRV(spec.min, spec.mode, spec.max, streamNum = spec.stream)
        is RVSpec.Lognormal -> LognormalRV(spec.mean, spec.variance, streamNum = spec.stream)
    }

    // -- internals ------------------------------------------------------

    /** The three standard strategies; custom shared carriers are post-mutate. */
    private fun materializeStrategy(
        spec: TransportStrategySpec,
        sc: SupplyChainModel,
    ): TransportStrategy = when (spec) {
        TransportStrategySpec.SharedCarrier -> TransportStrategy.SharedCarrier()
        TransportStrategySpec.PerIHPTimeBased -> TransportStrategy.PerIHPTimeBased
        TransportStrategySpec.NetworkTimeBased ->
            TransportStrategy.NetworkTimeBased(
                TimeBasedNetworkDemandCarrier(sc, name = "${sc.name}:NetworkCarrier"),
            )
    }

    /** Map a [ShipmentFormationSpec] to the runtime [ShipmentFormation]. */
    private fun materialize(spec: ShipmentFormationSpec): ShipmentFormation {
        val option = when (spec.option) {
            FormingOption.NONE -> DemandLoadBuilder.LoadFormingOption.NONE
            FormingOption.ALWAYS -> DemandLoadBuilder.LoadFormingOption.ALWAYS
            FormingOption.COUNT -> DemandLoadBuilder.LoadFormingOption.COUNT
            FormingOption.WEIGHT -> DemandLoadBuilder.LoadFormingOption.WEIGHT
            FormingOption.CUBE -> DemandLoadBuilder.LoadFormingOption.CUBE
        }
        return ShipmentFormation(
            option = option,
            countLimit = spec.countLimit ?: 0,
            weightLimits = spec.weightLimits?.let { it.min to it.max },
            cubeLimits = spec.cubeLimits?.let { it.min to it.max },
        )
    }

    /** Add one inventory to [ihp], mapping the [PolicySpec] to its factory. */
    private fun addInventory(ihp: InventoryHoldingPoint, type: ItemType, spec: InventorySpec) {
        when (val p = spec.policy) {
            is PolicySpec.SQ ->
                ihp.addReorderPointReorderQuantityInventory(
                    type, p.reorderPoint, p.reorderQty, spec.initialOnHand,
                )
            is PolicySpec.SS ->
                ihp.addReorderPointOrderUpToLevelInventory(
                    type, p.reorderPoint, p.orderUpToLevel, spec.initialOnHand,
                )
            is PolicySpec.SSPeriodic -> {
                // The framework's periodic policy uses a fixed review
                // period, so the review interval must be a constant.
                val reviewPeriod = (p.reviewInterval as? RVSpec.Constant)?.value
                    ?: error(
                        "SSPeriodic reviewInterval must be a constant " +
                            "(the periodic policy uses a fixed review period); " +
                            "got ${p.reviewInterval}",
                    )
                val policy = InventoryPolicyReorderPointOrderUpToLevelPeriodic(
                    ihp,
                    reorderPoint = p.reorderPoint,
                    orderUpToPoint = p.orderUpToLevel,
                    reviewPeriod = reviewPeriod,
                )
                val inventory = Inventory(ihp, type, policy, spec.initialOnHand)
                BackLogQueue(inventory, name = "${inventory.name} : BackLogQ")
                ihp.addInventory(inventory)
            }
        }
    }

    /**
     * Return [nodes] in topological (parent-before-child) order.  The
     * spec is assumed validated (acyclic, all parents resolve), so the
     * resolution always terminates with every node placed.
     */
    private fun topologicalOrder(nodes: List<NodeSpec>): List<NodeSpec> {
        val ordered = ArrayList<NodeSpec>(nodes.size)
        val placed = hashSetOf(NodeSpec.EXTERNAL_SUPPLIER)
        var changed = true
        while (changed) {
            changed = false
            for (n in nodes) {
                if (n.name !in placed && n.parent in placed) {
                    ordered += n
                    placed += n.name
                    changed = true
                }
            }
        }
        check(ordered.size == nodes.size) {
            "topological sort failed; the spec should have been validated first"
        }
        return ordered
    }

    /** Convert the serializable [CostParamsSpec] twin to the runtime [CostParams]. */
    private fun CostParamsSpec.toCostParams(): CostParams = CostParams(
        carryingRate = carryingRate,
        backorderRate = backorderRate,
        orderingCost = orderingCost,
        unloadingCost = unloadingCost,
        loadingCost = loadingCost,
        shippingCost = shippingCost,
        stockoutCost = stockoutCost,
        lostSaleCost = lostSaleCost,
        unitShortageCost = unitShortageCost,
        esLoadingCost = esLoadingCost,
    )
}
