package ksl.modeling.supplychain.cost

import ksl.modeling.supplychain.inventory.NetworkNodeIfc
import ksl.modeling.supplychain.network.MultiEchelonNetwork

/**
 * A [DefaultMultiEchelonCostFormulation] whose cost rates vary by node.
 * Every calculator uses the [CostParams] override registered for its
 * owning node (keyed by node name), falling back to [defaultParams] for
 * nodes without an override and for the external supplier's own
 * outbound.
 *
 * "Owning node" follows the same attribution the base formulation uses:
 * an inventory's / backlog's / load-builder's holder, an outbound edge's
 * supplier, and an inbound edge's customer.  So, for example, raising a
 * single retailer's `carryingRate` lifts only that retailer's holding
 * cost; raising a warehouse's `unloadingCost` lifts only the cost of
 * unloading shipments *into* that warehouse.
 *
 * The override map is threaded into the base constructor as a resolver
 * (rather than an overridden method) so it is already available when the
 * base `init` block builds the calculators — avoiding the
 * open-call-from-constructor initialization-order trap.
 *
 * Like the base formulation, this attaches itself to [network] and must
 * be constructed **after** the topology is final (the ordering / coverage
 * guards enforce it).
 *
 * @param network the network whose calculators this formulation manages
 * @param defaultParams the fallback parameter bundle
 * @param overrides per-node parameter overrides, keyed by node name
 * @param name optional ModelElement name
 *
 * @see DefaultMultiEchelonCostFormulation
 */
open class PerNodeIHPCostFormulation @JvmOverloads constructor(
    network: MultiEchelonNetwork,
    val defaultParams: CostParams = CostParams(),
    val overrides: Map<String, CostParams> = emptyMap(),
    name: String? = null,
) : DefaultMultiEchelonCostFormulation(
    network = network,
    params = defaultParams,
    name = name ?: "PerNodeIHPCostFormulation",
    paramsResolver = { node: NetworkNodeIfc? ->
        node?.let { overrides[it.name] } ?: defaultParams
    },
)
