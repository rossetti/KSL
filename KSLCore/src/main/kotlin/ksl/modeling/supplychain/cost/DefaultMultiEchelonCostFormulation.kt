package ksl.modeling.supplychain.cost

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.modeling.supplychain.inventory.InventoryCrossDock
import ksl.modeling.supplychain.inventory.InventoryHoldingPoint
import ksl.modeling.supplychain.inventory.NetworkNodeIfc
import ksl.modeling.supplychain.network.MultiEchelonNetwork
import ksl.modeling.supplychain.network.TransportStrategy
import ksl.modeling.supplychain.transport.TimeBasedDemandCarrier
import ksl.modeling.supplychain.transport.TimeBasedLoadCarrier
import ksl.modeling.supplychain.transport.TimeBasedNetworkDemandCarrier
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.ModelElement

/**
 * Standard cost formulation for a [MultiEchelonNetwork].  Walks the
 * network at construction time and instantiates one [CostCalculator]
 * per source kind: per inventory, per backlog, per outbound edge
 * (inbound and outbound), per load builder, plus one for the ES.
 *
 * Pre-allocates a [Response] for every line × tier combination plus
 * per-line, per-tier, and grand-total Responses; sums them in
 * [replicationEnded].  KSL's tree walk visits the calculator
 * children before this formulation's `replicationEnded`, guaranteeing
 * the calculators' Responses are populated by the time the
 * formulation's rollup runs.
 *
 * **Auto-attach.** Construction also registers this formulation
 * with [network] via [MultiEchelonNetwork.attachCostFormulation],
 * so it appears in the network's `costFormulations` snapshot
 * without an explicit second call.
 *
 * **Transport-strategy support.**  All three
 * [TransportStrategy] variants are supported:
 *   - `SharedCarrier`: per-edge counters do not exist, so flow-line
 *     Responses (Loading, Shipping, Unloading, ESLoading) report 0,
 *     matching the legacy framework's behaviour.
 *   - `PerIHPTimeBased`: each node owns its own
 *     [ksl.modeling.supplychain.transport.TimeBasedDemandCarrier];
 *     the formulation builds one
 *     [EdgeOutboundCostCalculator] + [EdgeInboundCostCalculator]
 *     per edge plus one [ESCostCalculator] for the ES outbound.
 *   - `NetworkTimeBased`: a single shared
 *     [ksl.modeling.supplychain.transport.TimeBasedNetworkDemandCarrier]
 *     carries every edge; the formulation builds the network-typed
 *     variants ([NetworkEdgeOutboundCostCalculator],
 *     [NetworkEdgeInboundCostCalculator], [NetworkESCostCalculator])
 *     which read per-edge counts keyed on `(filler, sender)`.
 *
 * @param network the network whose calculators this formulation
 *        manages.  Used as the [ModelElement] parent so KSL's
 *        tree walk includes this formulation in the network's
 *        `replicationEnded` lifecycle (children-first ordering
 *        guarantees calculator Responses are populated before this
 *        formulation's rollup runs).
 * @param params parameter bundle for the line-item calculators
 * @param name optional ModelElement name
 * @param paramsResolver optional per-node parameter override.  When
 *        non-null, it is consulted for the [CostParams] each calculator
 *        should use, keyed by the calculator's *owning* node (the
 *        inventory's / backlog's / builder's holder, an outbound edge's
 *        supplier, an inbound edge's customer; `null` for the external
 *        supplier's own outbound).  Return the node's override, or null
 *        to fall back to the live network-level bundle (the
 *        construction-time [params] as adjusted by the rate controls).
 *        When the resolver itself is null (the default), every
 *        calculator uses the live network-level bundle — the
 *        uniform-cost behaviour.  Passed as a constructor parameter
 *        (not an overridable method) so it is available while the
 *        `init` block builds calculators, avoiding the
 *        open-call-from-constructor initialization-order trap.
 *        Resolution happens at each replication end, so rate changes
 *        made between replications take effect.
 *
 * @see CostFormulation
 * @see CostCalculator
 * @see PerNodeIHPCostFormulation
 */
open class DefaultMultiEchelonCostFormulation @JvmOverloads constructor(
    network: MultiEchelonNetwork,
    val params: CostParams = CostParams(),
    name: String? = null,
    private val paramsResolver: ((NetworkNodeIfc?) -> CostParams?)? = null,
) : ModelElement(network, name ?: "DefaultMultiEchelonCostFormulation"),
    CostFormulation {

    // The live network-level parameter bundle. Starts at the construction-time
    // params and is updated by the rate controls below; calculators resolve their
    // rates through paramsFor at each replication end, so control changes made
    // between replications take effect.
    private var myCurrentParams: CostParams = params

    /** The current (possibly control-adjusted) network-level parameter bundle. */
    val currentParams: CostParams
        get() = myCurrentParams

    /**
     * The [CostParams] a calculator owned by [node] should use: the
     * [paramsResolver]'s non-null answer when one was supplied for the node,
     * else the live network-level bundle (the construction-time [params] as
     * adjusted by the rate controls).  `node` is `null` for the external
     * supplier's own outbound calculator.  Resolved at each replication end.
     */
    private fun paramsFor(node: NetworkNodeIfc?): CostParams =
        paramsResolver?.invoke(node) ?: myCurrentParams

    /**
     * Network-level continuous inventory carrying rate (per unit value per
     * modeler time unit). A control: rates are pure end-of-replication
     * multipliers, so changes between replications are always sound; changing a
     * rate during a replication is rejected (it would alter the in-flight
     * replication's cost accounting). Per-node resolver overrides (see
     * `PerNodeIHPCostFormulation`) take precedence for their nodes; this value
     * governs all other nodes.
     */
    @set:KSLControl(controlType = ControlType.DOUBLE, lowerBound = 0.0)
    var carryingRate: Double
        get() = myCurrentParams.carryingRate
        set(value) {
            require(!model.isRunning) {
                "Cost rates cannot be changed while the model is running."
            }
            myCurrentParams = myCurrentParams.copy(carryingRate = value)
        }

    /** Network-level replenishment ordering cost ($ per order event). A control;
     *  see [carryingRate] for the timing and per-node-precedence semantics. */
    @set:KSLControl(controlType = ControlType.DOUBLE, lowerBound = 0.0)
    var orderingCost: Double
        get() = myCurrentParams.orderingCost
        set(value) {
            require(!model.isRunning) {
                "Cost rates cannot be changed while the model is running."
            }
            myCurrentParams = myCurrentParams.copy(orderingCost = value)
        }

    /** Network-level continuous backorder carrying rate (per unit per modeler
     *  time unit; 0 disables the line). A control; see [carryingRate]. */
    @set:KSLControl(controlType = ControlType.DOUBLE, lowerBound = 0.0)
    var backorderRate: Double
        get() = myCurrentParams.backorderRate
        set(value) {
            require(!model.isRunning) {
                "Cost rates cannot be changed while the model is running."
            }
            myCurrentParams = myCurrentParams.copy(backorderRate = value)
        }

    /** Network-level stockout cost ($ per stockout event; 0 disables the line).
     *  A control; see [carryingRate]. */
    @set:KSLControl(controlType = ControlType.DOUBLE, lowerBound = 0.0)
    var stockoutCost: Double
        get() = myCurrentParams.stockoutCost
        set(value) {
            require(!model.isRunning) {
                "Cost rates cannot be changed while the model is running."
            }
            myCurrentParams = myCurrentParams.copy(stockoutCost = value)
        }

    // Stored so the coverage guard (beforeExperiment) can re-measure the
    // topology after construction.
    private val myNetwork: MultiEchelonNetwork = network

    private val myCalculators: MutableList<CostCalculator> = mutableListOf()

    // Owning node name per calculator (null for the external supplier's own
    // outbound), using the same attribution the params resolver uses. Drives
    // the per-node rollup Responses.
    private val myCalculatorOwners: MutableMap<CostCalculator, String?> = mutableMapOf()

    private fun addCalculator(calculator: CostCalculator, owner: NetworkNodeIfc?) {
        myCalculators += calculator
        myCalculatorOwners[calculator] = owner?.name
    }

    override val calculators: Collection<CostCalculator>
        get() = myCalculators

    // Pre-allocated rollup Responses.  Per-(tier, line) is the
    // finest granularity; per-line and per-tier are derived sums in
    // replicationEnded, as is the grand total.
    private val myByTierAndLine: Map<NodeTier, Map<CostLine, Response>> =
        NodeTier.all.associateWith { tier ->
            CostLine.all.associateWith { line ->
                Response(this, name = "${this.name}:Tier:$tier:Line:$line")
            }
        }
    private val myByLine: Map<CostLine, Response> =
        CostLine.all.associateWith { line ->
            Response(this, name = "${this.name}:Total:$line")
        }
    private val myByTier: Map<NodeTier, Response> =
        NodeTier.all.associateWith { tier ->
            Response(this, name = "${this.name}:Total:Tier:$tier")
        }
    private val myTotal: Response =
        Response(this, name = "${this.name}:GrandTotal")

    // Per-node (location) rollup Responses, keyed by node name. Allocated in the
    // init block after buildCalculators so the tracked node set is known.
    private val myByNode: MutableMap<String, Response> = mutableMapOf()

    override fun byNodeResponse(nodeName: String): ResponseCIfc? =
        myByNode[nodeName]

    override val trackedNodeNames: Set<String>
        get() = myByNode.keys

    /** The name of the grand-total Response — the string an optimization
     *  problem's objective-function response name should reference. */
    val totalCostResponseName: String
        get() = myTotal.name

    override fun byLineResponse(line: CostLine): ResponseCIfc? =
        myByLine[line]

    override fun byTierResponse(tier: NodeTier): ResponseCIfc? =
        myByTier[tier]

    override fun byTierAndLineResponse(tier: NodeTier, line: CostLine): ResponseCIfc? =
        myByTierAndLine[tier]?.get(line)

    override val totalCostResponse: ResponseCIfc
        get() = myTotal

    /**
     * Roll up the calculators' line Responses into the per-(tier, line),
     * per-line, per-tier, and grand-total Responses.
     *
     * **Ordering contract (audit finding F).** Each [CostCalculator]'s
     * work happens in a `ModelElementObserver` attached to its *source*
     * ModelElement (an inventory, carrier, builder, or backlog policy),
     * which fires when that source reaches `REPLICATION_ENDED`.  This
     * rollup reads the calculators' Responses, so every source must have
     * completed its replication before this method runs.  That holds when
     * the formulation is constructed *after* the full network topology
     * (the documented contract — sources then precede the formulation in
     * the model's depth-first tree walk).  The guard below converts a
     * violation (a source constructed after the formulation, whose
     * observer has not yet fired, leaving its Response at the
     * `beforeReplication`-reset value of 0) from a *silent* wrong-zero
     * into a loud failure.  Earlier draft KDoc/tests claimed a
     * parent-child tree-walk guarantee; that was incorrect — calculators
     * are parented to the formulation but their observers fire on the
     * *sources*, so parenting does not establish the ordering.
     */
    override fun replicationEnded() {
        super.replicationEnded()

        // F: fail loud if any calculator's source has not finished its
        // replication (its observer would not yet have populated the
        // calculator's Response → a silent 0 in the rollup).
        for (calc in myCalculators) {
            check(calc.source.currentStatus ==
                ksl.simulation.ModelElement.Status.REPLICATION_ENDED) {
                "Cost source '${calc.source.name}' had not completed its " +
                    "replication when '${this.name}' rolled up costs. " +
                    "Construct the cost formulation AFTER the network " +
                    "topology is fully built so every cost source precedes " +
                    "the formulation in the model element tree."
            }
        }

        // Per-(tier, line): sum every calculator whose tier matches.
        for ((tier, perLineMap) in myByTierAndLine) {
            for ((line, agg) in perLineMap) {
                var sum = 0.0
                for (calc in myCalculators) {
                    if (calc.tier !== tier) continue
                    val r = calc.lineResponses[line] ?: continue
                    sum += r.value
                }
                agg.value = sum
            }
        }

        // Per-line: sum the per-(tier, line) Responses across tiers.
        var grand = 0.0
        for ((line, agg) in myByLine) {
            var sum = 0.0
            for (tier in NodeTier.all) {
                sum += myByTierAndLine[tier]?.get(line)?.value ?: 0.0
            }
            agg.value = sum
            grand += sum
        }

        // Per-tier: sum the per-(tier, line) Responses across lines.
        for ((tier, agg) in myByTier) {
            var sum = 0.0
            for (line in CostLine.all) {
                sum += myByTierAndLine[tier]?.get(line)?.value ?: 0.0
            }
            agg.value = sum
        }

        myTotal.value = grand

        // Per-node (location): sum every line of every calculator owned by the
        // node, using the same ownership attribution the params resolver uses.
        for ((nodeName, agg) in myByNode) {
            var sum = 0.0
            for (calc in myCalculators) {
                if (myCalculatorOwners[calc] != nodeName) continue
                for (r in calc.lineResponses.values) {
                    sum += r.value
                }
            }
            agg.value = sum
        }
    }

    init {
        // Walk the network and pre-allocate one calculator per source
        // kind.  Phase 3 supports PerIHPTimeBased + SharedCarrier;
        // under SharedCarrier the carriers are not TimeBasedDemandCarriers
        // so edge calculators are skipped (their flow-line contributions
        // are 0 by construction, matching the legacy behavior).
        buildCalculators(network)

        // Allocate one per-node (location) total Response per owning node the
        // calculators were attributed to (the external supplier's own outbound
        // has no owning node and contributes only to the grand total).
        for (nodeName in myCalculatorOwners.values.filterNotNull().distinct()) {
            myByNode[nodeName] = Response(this, name = "${this.name}:Node:$nodeName:Total")
        }

        // Suppress the standard half-width report rows for rollup
        // Responses that no calculator in this topology produces.
        // The Responses still exist (so `byTier* / byLine*` continue
        // to return non-null and integrate values to 0 at replication
        // end), but they are excluded from the default report so
        // structurally-impossible combinations (e.g. ES-tier holding,
        // CD-tier ordering) and topology-absent combinations (e.g.
        // CD-tier lines when no cross-docks exist) don't clutter the
        // output.  Users who want them back flip `defaultReportingOption`
        // to true on the specific Response.
        suppressUnproducedRollupReports()

        // Register with the network for discoverability.
        network.attachCostFormulation(this)
    }

    // G: snapshot the topology (after the init block has built the
    // calculators) so beforeExperiment can detect nodes/edges/generators
    // attached after this formulation was built, which would be silently
    // uncovered by any calculator.  measureTopology only reads the
    // network, which is fully built by construction time per the
    // formulation's ordering contract.
    private val myBuildTopology: TopologyFingerprint = measureTopology(myNetwork)

    /**
     * Compact fingerprint of the cost-relevant topology size: node
     * count, total (IHP, item) inventory count, total demand-generator
     * count, and total edge count.  Used only by the [beforeExperiment]
     * coverage guard.
     */
    private data class TopologyFingerprint(
        val nodes: Int,
        val inventories: Int,
        val demandGenerators: Int,
        val edges: Int,
    )

    private fun measureTopology(net: MultiEchelonNetwork): TopologyFingerprint {
        val nodes = net.getNodes()
        val inventories = net.getInventoryHoldingPoints()
            .sumOf { it.itemTypes.size }
        val demandGenerators = nodes.sumOf { net.getDemandGenerators(it).size }
        val edges = nodes.sumOf { net.customersOf(it).size } +
            nodes.count { net.isAttachedToExternalSupplier(it) }
        return TopologyFingerprint(nodes.size, inventories, demandGenerators, edges)
    }

    /**
     * Coverage guard (audit finding G).  [buildCalculators] runs once at
     * construction, so any node, edge, inventory, or demand generator
     * attached to the network *after* this formulation was built gets no
     * calculator and contributes nothing to any rollup — silently.  This
     * runs once before the experiment (after `simulate()` is called but
     * before the executive starts) and fails loud if the topology grew.
     */
    override fun beforeExperiment() {
        super.beforeExperiment()
        val current = measureTopology(myNetwork)
        check(current == myBuildTopology) {
            "Network topology changed after '${this.name}' was constructed " +
                "(at build: $myBuildTopology, at simulation start: $current). " +
                "Construct the cost formulation AFTER the topology is fully " +
                "built; nodes, edges, inventories, or demand generators added " +
                "afterward are not covered by any cost calculator."
        }
    }

    /**
     * For each per-(tier, line), per-tier, and per-line rollup
     * Response, set [ksl.modeling.variable.Response.defaultReportingOption]
     * to `false` when no [CostCalculator] in [myCalculators] produces
     * a contribution.  Called once from `init` after
     * [buildCalculators].
     */
    private fun suppressUnproducedRollupReports() {
        val producedTierLines = mutableSetOf<Pair<NodeTier, CostLine>>()
        val producedTiers = mutableSetOf<NodeTier>()
        val producedLines = mutableSetOf<CostLine>()
        for (calc in myCalculators) {
            val t = calc.tier ?: continue
            for (line in calc.lineResponses.keys) {
                producedTierLines += t to line
                producedTiers += t
                producedLines += line
            }
        }
        for ((tier, perLineMap) in myByTierAndLine) {
            for ((line, response) in perLineMap) {
                if (tier to line !in producedTierLines) {
                    response.defaultReportingOption = false
                }
            }
        }
        for ((line, response) in myByLine) {
            if (line !in producedLines) response.defaultReportingOption = false
        }
        for ((tier, response) in myByTier) {
            if (tier !in producedTiers) response.defaultReportingOption = false
        }
    }

    // ----------------------------------------------------------------- building

    // Prefix included in every calculator's KSL model-element name so
    // multiple formulations on the same network produce distinct names
    // (KSL requires unique ModelElement names).
    private val calcNamePrefix: String get() = this.name

    private fun buildCalculators(network: MultiEchelonNetwork) {
        // Inventory/backlog/builder calculators are strategy-independent.
        // Edge calculators dispatch on the network's transport strategy
        // because the per-edge shipment counters live on different
        // carrier kinds — TimeBasedDemandCarrier under PerIHPTimeBased
        // (one carrier per node), TimeBasedNetworkDemandCarrier under
        // NetworkTimeBased (one shared carrier, edges keyed by
        // `(filler, sender)`).

        // 1. Per-IHP: inventories, backlog, builders.
        for (ihp in network.getInventoryHoldingPoints()) {
            for (item in ihp.itemTypes) {
                val inv = ihp.getInventory(item) ?: continue
                val ihpParams = { paramsFor(ihp) }
                addCalculator(InventoryCostCalculator(
                    this, inv, ihpParams,
                    name = "$calcNamePrefix:${inv.name}:CostCalc",
                ), ihp)
                inv.backLogPolicy?.let { policy ->
                    addCalculator(BackorderCostCalculator(
                        this, policy, ihpParams,
                        name = "$calcNamePrefix:${policy.name}:CostCalc",
                    ), ihp)
                }
            }
            buildBuilderCalculators(ihp, NodeTier.IHP)
        }

        // 2. Per-CD: builders.
        for (cd in network.getInventoryCrossDocks()) {
            buildBuilderCalculators(cd, NodeTier.CD)
        }

        // 3. Edge and ES calculators dispatch on transport strategy.
        when (val strategy = network.transportStrategy) {
            is ksl.modeling.supplychain.network.TransportStrategy.SharedCarrier -> {
                // No per-edge counters → flow lines stay at 0.
            }
            is ksl.modeling.supplychain.network.TransportStrategy.PerIHPTimeBased ->
                buildPerIHPEdgeCalculators(network)
            is ksl.modeling.supplychain.network.TransportStrategy.NetworkTimeBased ->
                buildNetworkEdgeCalculators(network, strategy.carrier)
        }
    }

    private fun buildPerIHPEdgeCalculators(network: MultiEchelonNetwork) {
        // Outbound from every IHP and CD.
        for (ihp in network.getInventoryHoldingPoints()) {
            buildPerIHPOutboundFor(network, ihp, NodeTier.IHP)
        }
        for (cd in network.getInventoryCrossDocks()) {
            buildPerIHPOutboundFor(network, cd, NodeTier.CD)
        }
        // ES under PerIHPTimeBased owns its own dedicated outbound
        // carrier (a TimeBasedDemandCarrier).
        val esCarrier = network.externalSupplier.demandCarrier
            as? TimeBasedDemandCarrier ?: return
        val esDestinations = network.getNodes()
            .filter { network.isAttachedToExternalSupplier(it) }
            .map { it as ksl.modeling.supplychain.DemandSenderIfc }
        addCalculator(ESCostCalculator(
            this, esCarrier, esDestinations, { paramsFor(null) },
            name = "$calcNamePrefix:${esCarrier.name}:ESCostCalc",
        ), null)
        for (dest in esDestinations) {
            val destNode = dest as NetworkNodeIfc
            val destTier = tierOf(destNode) ?: continue
            addCalculator(EdgeInboundCostCalculator(
                this, esCarrier, dest, destTier, { paramsFor(destNode) },
                name = "$calcNamePrefix:${esCarrier.name}->${dest.name}:In",
            ), destNode)
        }
    }

    private fun buildPerIHPOutboundFor(
        network: MultiEchelonNetwork,
        node: NetworkNodeIfc,
        supplierTier: NodeTier,
    ) {
        val outboundCarrier = node.demandCarrier as? TimeBasedDemandCarrier
            ?: return

        val nodeCustomers = network.customersOf(node)
        val dgCustomers = network.getDemandGenerators(node)
        val nodeParams = { paramsFor(node) }

        for (customer in nodeCustomers) {
            addCalculator(EdgeOutboundCostCalculator(
                this, outboundCarrier, customer, supplierTier, nodeParams,
                name = "$calcNamePrefix:${outboundCarrier.name}->${customer.name}:Out",
            ), node)
            val destTier = tierOf(customer) ?: continue
            addCalculator(EdgeInboundCostCalculator(
                this, outboundCarrier, customer, destTier, { paramsFor(customer) },
                name = "$calcNamePrefix:${outboundCarrier.name}->${customer.name}:In",
            ), customer)
        }
        for (dg in dgCustomers) {
            addCalculator(EdgeOutboundCostCalculator(
                this, outboundCarrier, dg, supplierTier, nodeParams,
                name = "$calcNamePrefix:${outboundCarrier.name}->${dg.name}:Out",
            ), node)
        }
    }

    private fun buildNetworkEdgeCalculators(
        network: MultiEchelonNetwork,
        networkCarrier: TimeBasedNetworkDemandCarrier,
    ) {
        // Walk every edge in the topology and instantiate the
        // network-typed calculators.  Each edge is keyed by
        // (filler, sender) in the carrier's per-edge counter map.

        // ES → node edges.
        val esDestinations = network.getNodes()
            .filter { network.isAttachedToExternalSupplier(it) }
            .map { it as ksl.modeling.supplychain.DemandSenderIfc }
        if (esDestinations.isNotEmpty()) {
            addCalculator(NetworkESCostCalculator(
                this, networkCarrier, network.externalSupplier,
                esDestinations, { paramsFor(null) },
                name = "$calcNamePrefix:${networkCarrier.name}:ESCostCalc",
            ), null)
            for (dest in esDestinations) {
                val destNode = dest as NetworkNodeIfc
                val destTier = tierOf(destNode) ?: continue
                addCalculator(NetworkEdgeInboundCostCalculator(
                    this, networkCarrier,
                    network.externalSupplier, dest, destTier, { paramsFor(destNode) },
                    name = "$calcNamePrefix:${networkCarrier.name}:" +
                        "${network.externalSupplier.name}->${dest.name}:In",
                ), destNode)
            }
        }

        // Node → node and node → DG edges, for every IHP and CD.
        for (node in network.getNodes()) {
            val supplierTier = tierOf(node) ?: continue
            val nodeAsFiller = node as? ksl.modeling.supplychain.DemandFillerIfc
                ?: continue
            val nodeParams = { paramsFor(node) }
            for (customer in network.customersOf(node)) {
                addCalculator(NetworkEdgeOutboundCostCalculator(
                    this, networkCarrier, nodeAsFiller, customer,
                    supplierTier, nodeParams,
                    name = "$calcNamePrefix:${networkCarrier.name}:" +
                        "${node.name}->${customer.name}:Out",
                ), node)
                val destTier = tierOf(customer) ?: continue
                addCalculator(NetworkEdgeInboundCostCalculator(
                    this, networkCarrier, nodeAsFiller, customer,
                    destTier, { paramsFor(customer) },
                    name = "$calcNamePrefix:${networkCarrier.name}:" +
                        "${node.name}->${customer.name}:In",
                ), customer)
            }
            for (dg in network.getDemandGenerators(node)) {
                addCalculator(NetworkEdgeOutboundCostCalculator(
                    this, networkCarrier, nodeAsFiller, dg,
                    supplierTier, nodeParams,
                    name = "$calcNamePrefix:${networkCarrier.name}:" +
                        "${node.name}->${dg.name}:Out",
                ), node)
            }
        }
    }

    private fun buildBuilderCalculators(
        node: NetworkNodeIfc,
        ownerTier: NodeTier,
    ) {
        val loadCarrier = node.demandCarrier as? TimeBasedLoadCarrier ?: return
        val nodeParams = { paramsFor(node) }
        for (builder in loadCarrier.allLoadBuilders()) {
            addCalculator(BuilderCostCalculator(
                this, builder, ownerTier, nodeParams,
                name = "$calcNamePrefix:${builder.name}:CostCalc",
            ), node)
        }
    }

    private fun tierOf(node: NetworkNodeIfc): NodeTier? = when (node) {
        is InventoryHoldingPoint -> NodeTier.IHP
        is InventoryCrossDock -> NodeTier.CD
        else -> null
    }
}
