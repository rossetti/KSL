package ksl.modeling.supplychain.spec

/**
 * Marks the supply-chain DSL receiver types so that members of an outer
 * scope cannot be called implicitly from an inner one (e.g. you cannot
 * declare an [SupplyChainScope.item] from inside a node block).
 */
@DslMarker
annotation class SupplyChainDsl

// ---------------------------------------------------------------------------
// Random-variable constructor helpers (top-level: always in scope, so they
// remain callable inside any DSL block despite the @DslMarker).
// ---------------------------------------------------------------------------

/** A deterministic [RVSpec.Constant]. */
fun constant(value: Double): RVSpec = RVSpec.Constant(value)

/** An [RVSpec.Exponential] with the given mean and explicit stream number. */
fun exponential(mean: Double, stream: Int): RVSpec = RVSpec.Exponential(mean, stream)

/** A continuous [RVSpec.Uniform] on `[min, max]`. */
fun uniform(min: Double, max: Double, stream: Int): RVSpec = RVSpec.Uniform(min, max, stream)

/** A [RVSpec.Triangular] with the given min / mode / max. */
fun triangular(min: Double, mode: Double, max: Double, stream: Int): RVSpec =
    RVSpec.Triangular(min, mode, max, stream)

/** A [RVSpec.Lognormal] with the given mean and variance. */
fun lognormal(mean: Double, variance: Double, stream: Int): RVSpec =
    RVSpec.Lognormal(mean, variance, stream)

// ---------------------------------------------------------------------------
// Shipment-formation helpers.
// ---------------------------------------------------------------------------

/** [FormingOption.ALWAYS] single-demand-per-load formation. */
fun alwaysFormation(): ShipmentFormationSpec = ShipmentFormationSpec(FormingOption.ALWAYS)

/** [FormingOption.COUNT] formation: ship a load every [limit] demands. */
fun countFormation(limit: Int): ShipmentFormationSpec =
    ShipmentFormationSpec(FormingOption.COUNT, countLimit = limit)

/** [FormingOption.WEIGHT] formation with the given `[min, max]` weight window. */
fun weightFormation(min: Double, max: Double): ShipmentFormationSpec =
    ShipmentFormationSpec(FormingOption.WEIGHT, weightLimits = LimitsSpec(min, max))

/** [FormingOption.CUBE] formation with the given `[min, max]` cube window. */
fun cubeFormation(min: Double, max: Double): ShipmentFormationSpec =
    ShipmentFormationSpec(FormingOption.CUBE, cubeLimits = LimitsSpec(min, max))

// ---------------------------------------------------------------------------
// Stream-number allocation.
// ---------------------------------------------------------------------------

/**
 * Allocates unique stream numbers from a documented [base], up to
 * [count] of them.  Useful for reserving a contiguous block of streams
 * for a subsystem so a saved spec stays deterministic and
 * non-overlapping.  See also [SupplyChainScope.autoStream].
 *
 * @param base the first stream number
 * @param count how many streams the range owns
 */
class StreamRange(val base: Int, val count: Int) {
    init {
        require(base >= 0) { "stream base must be >= 0" }
        require(count > 0) { "stream count must be > 0" }
    }

    private var offset = 0

    /** The next stream number; throws once the range is exhausted. */
    fun next(): Int {
        check(offset < count) { "StreamRange(base=$base, count=$count) is exhausted" }
        return base + offset++
    }

    /** How many streams have been handed out so far. */
    val used: Int get() = offset
}

/**
 * A handle to an item declared via [SupplyChainScope.item].  Carries
 * just the item [name]; inventories and demand generators reference an
 * item by handle for type-safety and refactor tolerance.
 */
class ItemRef internal constructor(val name: String)

// ---------------------------------------------------------------------------
// DSL entry point.
// ---------------------------------------------------------------------------

/**
 * Author a [NetworkSpec] with a Kotlin DSL.  The DSL is pure sugar over
 * the data layer: it builds and returns a [NetworkSpec] and never
 * touches runtime framework types.  Visual nesting
 * (`holdingPoint { holdingPoint { } }`) lowers to the flat node list,
 * with each node's `parent` inferred from the enclosing scope.
 *
 * ```kotlin
 * val spec = supplyChain("Net") {
 *     transportStrategy = perIHPTimeBased
 *     val widget = item("Widget", exponential(1.0, stream = 1), unitCost = 12.5)
 *     holdingPoint("W") {
 *         attachedToExternalSupplier(constant(3.0))
 *         inventory(widget) { sQ(s = 4, Q = 20, initialOnHand = 20) }
 *         tier(count = 3, namePrefix = "R", transportTime = constant(1.0)) {
 *             inventory(widget) { sS(s = 2, S = 5, initialOnHand = 10) }
 *             demand(widget, exponential(1.5, stream = autoStream()))
 *         }
 *     }
 *     defaultCost()
 * }
 * ```
 *
 * @param name the network name
 * @param autoStreamBase the first number handed out by [SupplyChainScope.autoStream]
 * @param block the DSL body
 * @return the authored [NetworkSpec]
 * @see SupplyChainBuilder
 */
fun supplyChain(
    name: String,
    autoStreamBase: Int = 1,
    block: SupplyChainScope.() -> Unit,
): NetworkSpec {
    val scope = SupplyChainScope(name, autoStreamBase)
    scope.block()
    return scope.build()
}

// ---------------------------------------------------------------------------
// Scopes.
// ---------------------------------------------------------------------------

/** Top-level DSL scope: items, root nodes, cost formulations, strategy. */
@SupplyChainDsl
class SupplyChainScope internal constructor(
    private val networkName: String,
    autoStreamBase: Int,
) {
    /** How shipments move between nodes; defaults to [sharedCarrier]. */
    var transportStrategy: TransportStrategySpec = TransportStrategySpec.SharedCarrier

    private val items = mutableListOf<ItemSpec>()
    private val nodeScopes = mutableListOf<NodeScope>()
    internal val demandGenerators = mutableListOf<DemandGeneratorSpec>()
    private val costFormulations = mutableListOf<CostFormulationSpec>()
    private var streamCounter = autoStreamBase

    /** Convenience accessor for [TransportStrategySpec.SharedCarrier]. */
    val sharedCarrier: TransportStrategySpec get() = TransportStrategySpec.SharedCarrier

    /** Convenience accessor for [TransportStrategySpec.PerIHPTimeBased]. */
    val perIHPTimeBased: TransportStrategySpec get() = TransportStrategySpec.PerIHPTimeBased

    /** Convenience accessor for [TransportStrategySpec.NetworkTimeBased]. */
    val networkTimeBased: TransportStrategySpec get() = TransportStrategySpec.NetworkTimeBased

    /** Allocate the next auto stream number (see [supplyChain]'s `autoStreamBase`). */
    fun autoStream(): Int = streamCounter++

    /**
     * Declare an item type.
     *
     * @return a handle to reference it from inventories and demands
     */
    fun item(
        name: String,
        leadTime: RVSpec,
        weight: Double = 1.0,
        cube: Double = 1.0,
        unitCost: Double = 1.0,
    ): ItemRef {
        items += ItemSpec(name, leadTime, weight, cube, unitCost)
        return ItemRef(name)
    }

    /** A root inventory holding point (parent = external supplier). */
    fun holdingPoint(name: String, block: NodeScope.() -> Unit = {}): NodeScope =
        addNode(name, NodeType.IHP, NodeSpec.EXTERNAL_SUPPLIER, block)

    /** A root cross-dock (parent = external supplier). */
    fun crossDock(name: String, block: NodeScope.() -> Unit = {}): NodeScope =
        addNode(name, NodeType.CD, NodeSpec.EXTERNAL_SUPPLIER, block)

    /**
     * A one-level star: a root IHP plus a leaf IHP per name in
     * [leafNames], each attached to the root with [transportTimeFromRoot].
     *
     * @param rootConfig configures the root node
     * @param leafConfig configures each leaf (receives the leaf name)
     */
    fun star(
        rootName: String,
        leafNames: List<String>,
        transportTimeFromRoot: RVSpec? = null,
        rootConfig: NodeScope.() -> Unit = {},
        leafConfig: NodeScope.(String) -> Unit = {},
    ): NodeScope = holdingPoint(rootName) {
        rootConfig()
        for (leaf in leafNames) {
            holdingPoint(leaf) {
                transportTimeFromParent = transportTimeFromRoot
                leafConfig(leaf)
            }
        }
    }

    /** Attach a network-wide default-parameter cost formulation. */
    fun defaultCost(name: String? = null, params: CostParamsSpec = CostParamsSpec()) {
        costFormulations += CostFormulationSpec.Default(name, params)
    }

    /** Attach a per-node-override cost formulation (built last; needs D5 runtime). */
    fun perNodeCost(
        name: String? = null,
        default: CostParamsSpec = CostParamsSpec(),
        overrides: Map<String, CostParamsSpec> = emptyMap(),
    ) {
        costFormulations += CostFormulationSpec.PerNodeIHP(name, default, overrides)
    }

    internal fun addNode(
        name: String,
        type: NodeType,
        parent: String,
        block: NodeScope.() -> Unit,
    ): NodeScope {
        val scope = NodeScope(this, name, type, parent)
        nodeScopes += scope // appended at call time → parent precedes children
        scope.block()
        return scope
    }

    internal fun build(): NetworkSpec = NetworkSpec(
        name = networkName,
        transportStrategy = transportStrategy,
        items = items.toList(),
        nodes = nodeScopes.map { it.toSpec() },
        demandGenerators = demandGenerators.toList(),
        costFormulations = costFormulations.toList(),
    )
}

/** A node scope: configures one IHP/CD and may nest children under it. */
@SupplyChainDsl
class NodeScope internal constructor(
    private val root: SupplyChainScope,
    /** This node's unique name. */
    val name: String,
    private val type: NodeType,
    private val parent: String,
) {
    /** Transport time on the supplier → this-node edge; `null` ⇒ immediate. */
    var transportTimeFromParent: RVSpec? = null

    /** Install a load-forming carrier on this node's outbound. */
    var enableShipmentFormation: Boolean = false

    /** Per-edge formation toward this node's supplier. */
    var shipmentFormationFromParent: ShipmentFormationSpec? = null

    private val inventories = mutableListOf<InventorySpec>()

    /** Allocate the next auto stream number from the enclosing network. */
    fun autoStream(): Int = root.autoStream()

    /**
     * For a root node only: set the transport time on the external
     * supplier → this-node edge.  Equivalent to assigning
     * [transportTimeFromParent], but reads as intent at a root.
     */
    fun attachedToExternalSupplier(transportTime: RVSpec? = null) {
        check(parent == NodeSpec.EXTERNAL_SUPPLIER) {
            "attachedToExternalSupplier is only valid for a top-level node; " +
                "'$name' is a child of '$parent'"
        }
        transportTimeFromParent = transportTime
    }

    /** A child IHP supplied by this node. */
    fun holdingPoint(name: String, block: NodeScope.() -> Unit = {}): NodeScope =
        root.addNode(name, NodeType.IHP, this.name, block)

    /** A child cross-dock supplied by this node. */
    fun crossDock(name: String, block: NodeScope.() -> Unit = {}): NodeScope =
        root.addNode(name, NodeType.CD, this.name, block)

    /** Add an inventory configured by a [InventoryScope] block. */
    fun inventory(item: ItemRef, block: InventoryScope.() -> Unit) {
        val s = InventoryScope().apply(block)
        val policy = checkNotNull(s.policy) {
            "inventory for item '${item.name}' on node '$name' has no policy"
        }
        inventories += InventorySpec(item.name, policy, s.initialOnHand)
    }

    /** Add an inventory directly from a [PolicySpec] (table-friendly). */
    fun inventory(item: ItemRef, policy: PolicySpec, initialOnHand: Int) {
        inventories += InventorySpec(item.name, policy, initialOnHand)
    }

    /** Attach a customer-demand generator at this node. */
    fun demand(
        item: ItemRef,
        interArrival: RVSpec,
        name: String? = null,
        transportTime: RVSpec? = null,
        shipmentFormation: ShipmentFormationSpec? = null,
    ) {
        root.demandGenerators += DemandGeneratorSpec(
            node = this.name,
            itemTypeName = item.name,
            interArrival = interArrival,
            name = name,
            transportTime = transportTime,
            shipmentFormation = shipmentFormation,
        )
    }

    /**
     * Generate [count] child IHPs named `${namePrefix}1..${namePrefix}N`
     * under this node, each with a common [config] block (which receives
     * the zero-based index).
     */
    fun tier(
        count: Int,
        namePrefix: String,
        transportTime: RVSpec? = null,
        config: NodeScope.(Int) -> Unit = {},
    ) {
        require(count > 0) { "tier count must be > 0" }
        for (i in 0 until count) {
            holdingPoint("$namePrefix${i + 1}") {
                transportTimeFromParent = transportTime
                config(i)
            }
        }
    }

    /**
     * Expand a node × item table into a tier of child IHPs.  Row `r`
     * becomes child `${namePrefix}${r+1}`; column `c` is [items]`[c]`.
     * Each cell of [policyTable] becomes an inventory (with
     * [initialOnHand]); each non-null cell of [demandTable] (if given)
     * becomes a demand generator.  Mirrors the
     * `MultiEchelonNetworkSSPolicyExample` `rsTable` / `demandMeans`,
     * but as data.
     *
     * @param policyTable `[node][item]` replenishment policies
     * @param demandTable optional `[node][item]` demand inter-arrivals
     */
    fun tierFromTables(
        namePrefix: String,
        items: List<ItemRef>,
        policyTable: List<List<PolicySpec>>,
        initialOnHand: Int,
        transportTime: RVSpec? = null,
        demandTable: List<List<RVSpec?>>? = null,
    ) {
        require(policyTable.isNotEmpty()) { "policyTable must have at least one row" }
        require(policyTable.all { it.size == items.size }) {
            "each policyTable row must have ${items.size} entries (one per item)"
        }
        if (demandTable != null) {
            require(demandTable.size == policyTable.size) {
                "demandTable must have one row per policyTable row"
            }
            require(demandTable.all { it.size == items.size }) {
                "each demandTable row must have ${items.size} entries"
            }
        }
        for ((r, row) in policyTable.withIndex()) {
            holdingPoint("$namePrefix${r + 1}") {
                transportTimeFromParent = transportTime
                for ((c, item) in items.withIndex()) {
                    inventory(item, row[c], initialOnHand)
                    demandTable?.get(r)?.get(c)?.let { ia -> demand(item, ia) }
                }
            }
        }
    }

    internal fun toSpec(): NodeSpec = NodeSpec(
        name = name,
        type = type,
        parent = parent,
        transportTimeFromParent = transportTimeFromParent,
        inventory = inventories.toList(),
        enableShipmentFormation = enableShipmentFormation,
        shipmentFormationFromParent = shipmentFormationFromParent,
    )
}

/** Configures the policy and initial stock of one inventory. */
@SupplyChainDsl
class InventoryScope internal constructor() {
    internal var policy: PolicySpec? = null
    internal var initialOnHand: Int = 0

    /** Continuous-review `(s, Q)`: reorder point [s], reorder quantity [Q]. */
    fun sQ(s: Int, Q: Int, initialOnHand: Int) {
        policy = PolicySpec.SQ(s, Q)
        this.initialOnHand = initialOnHand
    }

    /** Continuous-review `(s, S)`: reorder point [s], order-up-to-level [S]. */
    fun sS(s: Int, S: Int, initialOnHand: Int) {
        policy = PolicySpec.SS(s, S)
        this.initialOnHand = initialOnHand
    }

    /** Periodic-review `(s, S)` with the given (constant) [reviewInterval]. */
    fun sSPeriodic(s: Int, S: Int, reviewInterval: RVSpec, initialOnHand: Int) {
        policy = PolicySpec.SSPeriodic(s, S, reviewInterval)
        this.initialOnHand = initialOnHand
    }
}
