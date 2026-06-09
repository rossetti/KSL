package ksl.modeling.supplychain.spec

/**
 * A single validation problem found in a [NetworkSpec].  The [message]
 * carries enough context (node / item / edge names) to fix the data
 * without reading a KSL stack trace.
 */
data class SpecError(val message: String) {
    override fun toString(): String = message
}

/**
 * Validate the structural and reference integrity of this
 * [NetworkSpec], returning **every** problem found (not just the
 * first).  An empty list means the spec is well-formed and safe to
 * pass to `SupplyChainBuilder.build`.
 *
 * Checks:
 * - at least one node; no duplicate item or node names; no node named
 *   with the [NodeSpec.EXTERNAL_SUPPLIER] sentinel;
 * - at least one root (a node whose parent is the external supplier);
 * - every node `parent` resolves (a node name or the sentinel);
 * - no cycles in the supplier graph;
 * - cross-dock nodes carry no inventory;
 * - every `itemTypeName` (inventories and demand generators) resolves;
 * - every demand-generator `node` resolves;
 * - shipment formation only under
 *   [TransportStrategySpec.PerIHPTimeBased], with valid per-option
 *   parameters;
 * - per-node cost-override keys resolve to node names.
 *
 * Parameter sanity beyond this (e.g. `reorderQty > 0`) is left to the
 * framework's own `require`s at build time; this layer focuses on the
 * structural/reference integrity that is the spec layer's value-add.
 */
fun NetworkSpec.validate(): List<SpecError> {
    val errors = mutableListOf<SpecError>()
    fun err(msg: String) = errors.add(SpecError(msg))

    // -- names: duplicates and the reserved sentinel --------------------
    val itemNameCounts = items.groupingBy { it.name }.eachCount()
    for ((n, c) in itemNameCounts) if (c > 1) err("duplicate item name '$n' ($c definitions)")

    val nodeNameCounts = nodes.groupingBy { it.name }.eachCount()
    for ((n, c) in nodeNameCounts) if (c > 1) err("duplicate node name '$n' ($c definitions)")
    for (node in nodes) {
        if (node.name == NodeSpec.EXTERNAL_SUPPLIER) {
            err("node name '${node.name}' collides with the reserved " +
                "EXTERNAL_SUPPLIER sentinel")
        }
    }

    val itemNames = items.map { it.name }.toSet()
    val nodeNames = nodes.map { it.name }.toSet()

    if (nodes.isEmpty()) err("network has no nodes")

    // -- supplier graph: roots, dangling parents, cycles ----------------
    if (nodes.isNotEmpty() &&
        nodes.none { it.parent == NodeSpec.EXTERNAL_SUPPLIER }
    ) {
        err("no root node: at least one node must have parent " +
            "'${NodeSpec.EXTERNAL_SUPPLIER}'")
    }
    for (node in nodes) {
        if (node.parent != NodeSpec.EXTERNAL_SUPPLIER && node.parent !in nodeNames) {
            err("node '${node.name}' has unknown parent '${node.parent}'")
        }
    }
    // Cycle detection: repeatedly resolve nodes whose parent is the
    // sentinel or an already-resolved node.  Whatever stays unresolved
    // with a *valid* parent name is in (or downstream of) a cycle;
    // dangling-parent cases are reported above and excluded here.
    val resolved = hashSetOf(NodeSpec.EXTERNAL_SUPPLIER)
    var changed = true
    while (changed) {
        changed = false
        for (node in nodes) {
            if (node.name !in resolved && node.parent in resolved) {
                resolved += node.name
                changed = true
            }
        }
    }
    for (node in nodes) {
        if (node.name !in resolved &&
            (node.parent == NodeSpec.EXTERNAL_SUPPLIER || node.parent in nodeNames)
        ) {
            err("node '${node.name}' is part of a supplier cycle " +
                "(its parent chain never reaches EXTERNAL_SUPPLIER)")
        }
    }

    // -- node contents --------------------------------------------------
    for (node in nodes) {
        if (node.type == NodeType.CD && node.inventory.isNotEmpty()) {
            err("cross-dock node '${node.name}' must hold no inventory " +
                "(found ${node.inventory.size})")
        }
        for (inv in node.inventory) {
            if (inv.itemTypeName !in itemNames) {
                err("node '${node.name}' inventory references unknown item " +
                    "type '${inv.itemTypeName}'")
            }
        }
    }

    // -- demand generators ----------------------------------------------
    for (dg in demandGenerators) {
        val label = dg.name ?: "${dg.node}/${dg.itemTypeName}"
        if (dg.node !in nodeNames) {
            err("demand generator '$label' references unknown node '${dg.node}'")
        }
        if (dg.itemTypeName !in itemNames) {
            err("demand generator '$label' references unknown item type " +
                "'${dg.itemTypeName}'")
        }
    }

    // -- shipment formation: strategy + per-option parameters -----------
    val formationUsed =
        nodes.any { it.enableShipmentFormation || it.shipmentFormationFromParent != null } ||
            demandGenerators.any { it.shipmentFormation != null }
    if (formationUsed && transportStrategy != TransportStrategySpec.PerIHPTimeBased) {
        err("shipment formation requires transportStrategy = PerIHPTimeBased " +
            "(found ${transportStrategy::class.simpleName})")
    }
    fun checkFormation(where: String, f: ShipmentFormationSpec) {
        when (f.option) {
            FormingOption.COUNT ->
                if (f.countLimit == null || f.countLimit <= 0)
                    err("$where: COUNT formation requires countLimit > 0")
            FormingOption.WEIGHT -> {
                val w = f.weightLimits
                if (w == null) err("$where: WEIGHT formation requires weightLimits")
                else if (w.min > w.max) err("$where: weightLimits min > max")
            }
            FormingOption.CUBE -> {
                val c = f.cubeLimits
                if (c == null) err("$where: CUBE formation requires cubeLimits")
                else if (c.min > c.max) err("$where: cubeLimits min > max")
            }
            FormingOption.NONE, FormingOption.ALWAYS -> { /* no params */ }
        }
    }
    for (node in nodes) {
        node.shipmentFormationFromParent?.let {
            checkFormation("node '${node.name}' formation", it)
        }
    }
    for (dg in demandGenerators) {
        dg.shipmentFormation?.let {
            checkFormation("demand generator '${dg.name ?: dg.node}' formation", it)
        }
    }

    // -- cost: per-node override keys resolve ---------------------------
    for (cf in costFormulations) {
        if (cf is CostFormulationSpec.PerNodeIHP) {
            for (nodeName in cf.overrides.keys) {
                if (nodeName !in nodeNames) {
                    err("cost formulation '${cf.name ?: "perNodeIHP"}' overrides " +
                        "unknown node '$nodeName'")
                }
            }
        }
    }
    // Multiple formulations coexist on one network only if each has a
    // distinct, non-null name (the name prefixes the formulation's
    // responses and its KSL ModelElement, which must be unique).
    if (costFormulations.size > 1) {
        if (costFormulations.any { it.name == null }) {
            err("a network with more than one cost formulation requires every " +
                "formulation to have a name (it prefixes the formulation's responses)")
        }
        val cfNameCounts = costFormulations.mapNotNull { it.name }.groupingBy { it }.eachCount()
        for ((n, c) in cfNameCounts) if (c > 1) {
            err("duplicate cost formulation name '$n' ($c definitions)")
        }
    }

    return errors
}
