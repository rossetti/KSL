package ksl.modeling.supplychain.network

import ksl.modeling.supplychain.*
import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*

import ksl.modeling.variable.AggregateCounter
import ksl.modeling.variable.AggregateResponse
import ksl.modeling.variable.AggregateTWResponse
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * Multi-echelon arborescent tree of nodes. Each node has at most one
 * parent (its supplier) and any number of children (its customers).
 * Two node types are supported today and both implement
 * [NetworkNodeIfc]:
 * - [InventoryHoldingPoint] — holds [Inventory] per item type and
 *   replenishes from its upstream supplier; contributes to the
 *   network-wide [AggregateInventoryResponse].
 * - [InventoryCrossDock] — routing node, holds no inventory; clones
 *   incoming demand upstream and ships the original out on clone
 *   delivery.
 *
 * The root is a [LeadTimeDemandFiller] (the external supplier) with
 * an infinite supply that produces each item type after a lead time.
 *
 * Transport between locations is controlled by [transportStrategy]:
 * - [TransportStrategy.SharedCarrier] — one [DemandCarrierIfc] for
 *   the whole network (the original `MultiEchelonNetwork`
 *   behaviour); the carrier slot is mutable via [demandCarrier].
 * - [TransportStrategy.PerIHPTimeBased] — each node and the
 *   external supplier own a [TimeBasedDemandCarrier].
 * - [TransportStrategy.NetworkTimeBased] — a single shared
 *   [TimeBasedNetworkDemandCarrier] supplied by the caller.
 *
 * Setting `levelResponses = true` allocates a per-level
 * [AggregateInventoryResponse] in addition to the network-wide one
 * so callers can compare echelon performance. Only IHPs feed these
 * aggregates; cross-docks are tracked in level buckets but do not
 * subscribe (they hold no inventory).
 *
 * @param supplyChainModel the owning supply-chain model
 * @param name optional model-element name
 * @param transportStrategy how to wire transport between nodes;
 *        defaults to [TransportStrategy.SharedCarrier] with
 *        [NoDelayDemandCarrier]
 * @param levelResponses if true, collect statistics per level too
 *
 * @see TransportStrategy
 * @see NetworkNodeIfc
 * @see InventoryCrossDock
 * See `sc.mimenetworks.MultiEchelonNetwork`
 */
open class MultiEchelonNetwork @JvmOverloads constructor(
    val supplyChainModel: SupplyChainModel,
    name: String? = null,
    val transportStrategy: TransportStrategy = TransportStrategy.SharedCarrier(),
    levelResponses: Boolean = false,
    enableExternalSupplierShipmentFormation: Boolean = false,
) : ModelElement(supplyChainModel, name), AggregateInventoryResponseIfc {

    init {
        if (enableExternalSupplierShipmentFormation) {
            check(transportStrategy is TransportStrategy.PerIHPTimeBased) {
                "enableExternalSupplierShipmentFormation requires " +
                    "TransportStrategy.PerIHPTimeBased"
            }
        }
    }

    // -- top-level supplier + carrier wiring -----------------------------

    private val myExternalSupplier: LeadTimeDemandFiller =
        LeadTimeDemandFiller(this, name = "${this.name} : External Supplier")

    /**
     * Per-IHP carrier owned by the network when [transportStrategy]
     * is [TransportStrategy.PerIHPTimeBased] (the external supplier
     * gets a dedicated carrier separate from any node's). Under
     * `enableExternalSupplierShipmentFormation = true` this is a
     * [TimeBasedLoadCarrier] (with `reactToLoadBuildersFlag = true`)
     * so per-customer load builders can bundle outbound shipments;
     * otherwise it is a plain [TimeBasedDemandCarrier]. Null under
     * the other transport strategies.
     */
    private val myExternalTimeBasedCarrier: TimeBasedDemandCarrier? =
        if (transportStrategy is TransportStrategy.PerIHPTimeBased) {
            if (enableExternalSupplierShipmentFormation) {
                TimeBasedLoadCarrier(
                    supplyChainModel = supplyChainModel,
                    parent = this,
                    name = "${this.name} : External Shipper",
                ).also { it.reactToLoadBuildersFlag = true }
            } else {
                TimeBasedDemandCarrier(this, name = "${this.name} : External Shipper")
            }
        } else null

    /**
     * The network's active demand carrier under
     * [TransportStrategy.SharedCarrier]. Reading or writing under
     * any other strategy throws — use [transportStrategy] to access
     * the carrier of a time-based variant instead.
     */
    var demandCarrier: DemandCarrierIfc
        get() {
            check(transportStrategy is TransportStrategy.SharedCarrier) {
                "demandCarrier is only available under TransportStrategy.SharedCarrier"
            }
            return myDemandCarrierField
        }
        set(value) {
            check(transportStrategy is TransportStrategy.SharedCarrier) {
                "demandCarrier is only available under TransportStrategy.SharedCarrier"
            }
            myDemandCarrierField = value
        }

    private var myDemandCarrierField: DemandCarrierIfc =
        (transportStrategy as? TransportStrategy.SharedCarrier)?.carrier
            ?: NoDelayDemandCarrier

    private val myInnerDemandCarrier: DemandCarrierIfc = ForwardingCarrier()

    // -- per-IHP flag preserved across re-adds under PerIHPTimeBased -----

    private var myAllowExternalDemandGeneratorsFlag: Boolean = false

    // -- topology --------------------------------------------------------
    // Keyed on the common [NetworkNodeIfc] supertype so IHPs and
    // cross-docks share the same maps. Per-type accessors filter.

    private val myNodes:
        MutableMap<NetworkNodeIfc, MutableList<NetworkNodeIfc>> = linkedMapOf()

    private val myCustomerNodes: MutableSet<NetworkNodeIfc> = linkedSetOf()

    private val myNodesAttachedToExternalSupplier:
        MutableSet<NetworkNodeIfc> = linkedSetOf()

    private val myDemandGenerators:
        MutableMap<NetworkNodeIfc, MutableList<DemandGenerator>> = linkedMapOf()

    private val myLevels:
        MutableMap<Int, MutableList<NetworkNodeIfc>> = linkedMapOf()

    // -- aggregate statistics -------------------------------------------

    private val myAggregateInventoryResponse: AggregateInventoryResponse =
        AggregateInventoryResponse(this, this.name)

    private val myLevelResponses:
        MutableMap<Int, AggregateInventoryResponse>? =
            if (levelResponses) linkedMapOf() else null

    init {
        // Wire the external supplier's carrier per the chosen strategy.
        myExternalSupplier.demandCarrier = when (transportStrategy) {
            is TransportStrategy.SharedCarrier -> myInnerDemandCarrier
            is TransportStrategy.PerIHPTimeBased -> myExternalTimeBasedCarrier!!
            is TransportStrategy.NetworkTimeBased -> transportStrategy.carrier
        }
    }

    // -- cost-formulation registry (cost redesign, Phase 2) -------------
    // Each attached CostFormulation is itself a ModelElement parented
    // to this network, so KSL's tree walk handles its replicationEnded
    // lifecycle automatically.  This registry is used only for
    // discoverability: it allows code to enumerate or query formulations
    // by name without reaching through the model-element tree.

    private val myCostFormulations:
        MutableList<ksl.modeling.supplychain.cost.CostFormulation> = mutableListOf()

    /**
     * Register a [ksl.modeling.supplychain.cost.CostFormulation] with
     * this network.  Called automatically from
     * [ksl.modeling.supplychain.cost.DefaultMultiEchelonCostFormulation]'s
     * init block; custom formulations should call this directly from
     * their own init block to participate in [costFormulations] lookup.
     *
     * Idempotent: calling twice with the same formulation is a no-op.
     */
    fun attachCostFormulation(
        formulation: ksl.modeling.supplychain.cost.CostFormulation,
    ) {
        if (formulation !in myCostFormulations) {
            myCostFormulations += formulation
        }
    }

    /** Snapshot of formulations currently attached to this network. */
    val costFormulations: List<ksl.modeling.supplychain.cost.CostFormulation>
        get() = myCostFormulations.toList()

    // -- public read-only access ----------------------------------------

    /** The external supplier acting as the root of the tree. */
    val externalSupplier: LeadTimeDemandFiller get() = myExternalSupplier

    /** Levels for which a per-level aggregate response was created. */
    val levelSet: Set<Int>
        get() = myLevelResponses?.keys ?: emptySet()

    /** Per-level aggregate response (when `levelResponses = true`), or null. */
    fun getAggregateInventoryResponse(level: Int): AggregateInventoryResponseIfc? =
        myLevelResponses?.get(level)

    // -- item types ------------------------------------------------------

    fun addItemType(leadTime: RVariableIfc): ItemType =
        addItemType(name = null, leadTime = leadTime, weight = 1.0, cube = 1.0)

    fun addItemType(name: String?, leadTime: RVariableIfc): ItemType =
        addItemType(name, leadTime, weight = 1.0, cube = 1.0)

    fun addItemType(
        name: String?,
        leadTime: RVariableIfc,
        weight: Double,
    ): ItemType = addItemType(name, leadTime, weight, cube = 1.0)

    fun addItemType(
        name: String?,
        leadTime: RVariableIfc,
        weight: Double,
        cube: Double,
    ): ItemType {
        val type = ItemType(this, name = name, weight = weight, cube = cube)
        myExternalSupplier.addLeadTime(type, leadTime)
        return type
    }

    /** Look up an item type by [name], or null. */
    fun getItemType(name: String): ItemType? =
        model.getModelElement(name) as? ItemType

    /** All item types known across every IHP in the network. */
    val itemTypes: Set<ItemType>
        get() {
            val out = linkedSetOf<ItemType>()
            for (node in myNodes.keys) {
                if (node is InventoryHoldingPoint) out += node.itemTypes
            }
            return out
        }

    // -- IHPs ------------------------------------------------------------

    fun addInventoryHoldingPoint(): InventoryHoldingPoint =
        addInventoryHoldingPoint(null)

    @JvmOverloads
    fun addInventoryHoldingPoint(
        name: String?,
        enableShipmentFormation: Boolean = false,
    ): InventoryHoldingPoint {
        val ihp = InventoryHoldingPoint(this, name = name)
        registerNewNode(ihp, enableShipmentFormation)
        ihp.subscribeTo(myAggregateInventoryResponse)
        return ihp
    }

    fun getInventoryHoldingPoint(name: String): InventoryHoldingPoint? =
        model.getModelElement(name) as? InventoryHoldingPoint

    /** Snapshot of all IHPs currently in the network. */
    fun getInventoryHoldingPoints(): List<InventoryHoldingPoint> =
        myNodes.keys.filterIsInstance<InventoryHoldingPoint>()

    /** IHPs at a given [level], in attachment order. */
    fun getInventoryHoldingPoints(level: Int): List<InventoryHoldingPoint> =
        myLevels[level]?.filterIsInstance<InventoryHoldingPoint>() ?: emptyList()

    // -- Cross-docks -----------------------------------------------------

    fun addInventoryCrossDock(): InventoryCrossDock =
        addInventoryCrossDock(null)

    @JvmOverloads
    fun addInventoryCrossDock(
        name: String?,
        enableShipmentFormation: Boolean = false,
    ): InventoryCrossDock {
        val cd = InventoryCrossDock(this, name = name)
        registerNewNode(cd, enableShipmentFormation)
        // Cross-docks hold no inventory, so they do NOT subscribe to
        // the network's aggregate inventory response.
        return cd
    }

    fun getInventoryCrossDock(name: String): InventoryCrossDock? =
        model.getModelElement(name) as? InventoryCrossDock

    /** Snapshot of all cross-docks currently in the network. */
    fun getInventoryCrossDocks(): List<InventoryCrossDock> =
        myNodes.keys.filterIsInstance<InventoryCrossDock>()

    /** Cross-docks at a given [level], in attachment order. */
    fun getInventoryCrossDocks(level: Int): List<InventoryCrossDock> =
        myLevels[level]?.filterIsInstance<InventoryCrossDock>() ?: emptyList()

    // -- generalised node accessors -------------------------------------

    /** Snapshot of every node (IHP and cross-dock) in the network. */
    fun getNodes(): List<NetworkNodeIfc> = myNodes.keys.toList()

    /** Every node at a given [level], in attachment order. */
    fun getNodes(level: Int): List<NetworkNodeIfc> =
        myLevels[level]?.toList() ?: emptyList()

    /**
     * Downstream customer nodes of [supplier] — every node attached
     * via [attachToSupplier] with [supplier] as its supplier.
     * Returns an empty list when [supplier] is a leaf (or unknown).
     * Useful for walking the network's outbound-edge topology, for
     * example when constructing per-edge cost calculators.
     */
    fun customersOf(supplier: NetworkNodeIfc): List<NetworkNodeIfc> =
        myNodes[supplier]?.toList() ?: emptyList()

    fun isAttachedToExternalSupplier(node: NetworkNodeIfc): Boolean =
        node in myNodesAttachedToExternalSupplier

    fun isAttachedAsCustomer(node: NetworkNodeIfc): Boolean =
        node in myCustomerNodes

    fun isCustomer(supplier: NetworkNodeIfc, customer: NetworkNodeIfc): Boolean {
        val children = myNodes[supplier] ?: return false
        if (supplier !in myNodes || customer !in myNodes) return false
        return customer in children && customer.demandFiller === supplier
    }

    // -- shared node bookkeeping ----------------------------------------

    /**
     * Common factory tail for [addInventoryHoldingPoint] and
     * [addInventoryCrossDock]: register in [myNodes] and wire the
     * node's outbound carrier per [transportStrategy].
     */
    private fun registerNewNode(
        node: NetworkNodeIfc,
        enableShipmentFormation: Boolean,
    ) {
        if (enableShipmentFormation) {
            check(transportStrategy is TransportStrategy.PerIHPTimeBased) {
                "shipment formation is only supported under " +
                    "TransportStrategy.PerIHPTimeBased"
            }
        }
        myNodes[node] = mutableListOf()
        node.demandCarrier = when (transportStrategy) {
            is TransportStrategy.SharedCarrier -> myInnerDemandCarrier
            is TransportStrategy.PerIHPTimeBased -> {
                val shipper: TimeBasedDemandCarrier = if (enableShipmentFormation) {
                    TimeBasedLoadCarrier(
                        supplyChainModel = supplyChainModel,
                        parent = this,
                        name = "${node.name} : LoadCarrier",
                    ).also { it.reactToLoadBuildersFlag = true }
                } else {
                    TimeBasedDemandCarrier(
                        this, name = "${node.name} : TimeBasedCarrier",
                    )
                }
                if (myAllowExternalDemandGeneratorsFlag) {
                    shipper.immediateTransportFlag = true
                }
                shipper
            }
            is TransportStrategy.NetworkTimeBased -> transportStrategy.carrier
        }
    }

    private fun addToLevel(node: NetworkNodeIfc, level: Int) {
        node.level = level
        val bucket = myLevels.getOrPut(level) { mutableListOf() }
        bucket += node
        // Per-level aggregate is inventory-only (cross-docks contribute
        // nothing).
        if (myLevelResponses != null && node is InventoryHoldingPoint) {
            val response = myLevelResponses.getOrPut(level) {
                AggregateInventoryResponse(this, "${this.name} Level $level")
            }
            node.subscribeTo(response)
        }
    }

    // -- shipment-formation wiring helpers ------------------------------

    /**
     * If [supplier]'s carrier is a [TimeBasedLoadCarrier], ensure a
     * [DemandLoadBuilder] exists for [customer] and apply [formation]
     * if provided.  Customers without an explicit formation get a
     * default `ALWAYS` builder so the load carrier never sees a
     * demand for an unregistered destination.
     *
     * @throws IllegalStateException if [formation] is non-null but
     *         [supplier] is not formation-enabled
     */
    private fun configureShipmentFormation(
        supplier: NetworkNodeIfc,
        customer: DemandSenderIfc,
        formation: ShipmentFormation?,
    ) = configureShipmentFormationOnCarrier(
        carrier = supplier.demandCarrier,
        supplierName = supplier.name,
        customer = customer,
        formation = formation,
    )

    /**
     * Carrier-side variant of [configureShipmentFormation] used by
     * the external-supplier attach path (the ES owns its own carrier,
     * not a [NetworkNodeIfc]).
     */
    private fun configureShipmentFormationOnCarrier(
        carrier: DemandCarrierIfc?,
        supplierName: String,
        customer: DemandSenderIfc,
        formation: ShipmentFormation?,
    ) {
        if (carrier !is TimeBasedLoadCarrier) {
            check(formation == null) {
                "shipmentFormation supplied but supplier $supplierName " +
                    "was not created with shipment formation enabled"
            }
            return
        }
        val builder = if (carrier.containsLoadBuilder(customer)) {
            carrier.getLoadBuilder(customer)
        } else {
            // Pre-create one TWResponse per known item type so the
            // cost model can compute per-(builder, item) holding cost
            // (design doc §10 item #3).  itemTypes pulled from the ES
            // — every item type passes through addItemType, which
            // registers a lead time with the ES.
            carrier.assignLoadBuilder(
                customer,
                name = "$supplierName:${customer.name}:LoadBuilder",
                itemTypes = myExternalSupplier.itemTypes,
            )
        }
        if (formation != null) {
            builder.loadFormingOption = formation.option
            when (formation.option) {
                DemandLoadBuilder.LoadFormingOption.COUNT ->
                    builder.countLimit = formation.countLimit
                DemandLoadBuilder.LoadFormingOption.WEIGHT -> {
                    val (mn, mx) = formation.weightLimits!!
                    builder.setWeightFormingLimits(mn, mx)
                }
                DemandLoadBuilder.LoadFormingOption.CUBE -> {
                    val (mn, mx) = formation.cubeLimits!!
                    builder.setCubeFormingLimits(mn, mx)
                }
                DemandLoadBuilder.LoadFormingOption.ALWAYS,
                DemandLoadBuilder.LoadFormingOption.RULE,
                DemandLoadBuilder.LoadFormingOption.NONE -> { /* no extra config */ }
            }
        } else {
            // Default: ALWAYS (single-demand loads) so unregistered
            // formation behaves like direct shipping.
            builder.loadFormingOption = DemandLoadBuilder.LoadFormingOption.ALWAYS
        }
    }

    // -- attachment ------------------------------------------------------
    // Generalised attach methods take NetworkNodeIfc parameters; the
    // typed overloads (IHP-only) delegate so existing callers and tests
    // keep their signatures.

    @JvmOverloads
    fun attachToExternalSupplier(
        node: NetworkNodeIfc,
        transportTime: RVariableIfc? = null,
        shipmentFormation: ShipmentFormation? = null,
    ) {
        require(node !in myNodesAttachedToExternalSupplier) {
            "the supplied node is already attached to the external supplier"
        }
        require(node !in myCustomerNodes) {
            "the supplied node is already a customer to another node"
        }
        when (val s = transportStrategy) {
            is TransportStrategy.SharedCarrier -> {
                require(transportTime == null) {
                    "transportTime is not supported under SharedCarrier"
                }
            }
            is TransportStrategy.PerIHPTimeBased -> {
                if (transportTime != null) {
                    myExternalTimeBasedCarrier!!.setTransportTime(node, transportTime)
                } else {
                    myExternalTimeBasedCarrier!!.immediateTransportFlag = true
                    // Register the destination so its per-edge counters
                    // exist for the immediate-transport path (finding D).
                    myExternalTimeBasedCarrier!!.registerDestination(node)
                }
            }
            is TransportStrategy.NetworkTimeBased -> {
                s.carrier.setTransportTime(
                    myExternalSupplier, node,
                    transportTime ?: ConstantRV.ZERO,
                )
            }
        }
        configureShipmentFormationOnCarrier(
            carrier = myExternalSupplier.demandCarrier,
            supplierName = myExternalSupplier.name,
            customer = node,
            formation = shipmentFormation,
        )
        myNodesAttachedToExternalSupplier += node
        node.demandFiller = myExternalSupplier
        addToLevel(node, 1)
    }

    /** Typed overload for [InventoryHoldingPoint] — delegates to the [NetworkNodeIfc] form. */
    @JvmOverloads
    fun attachIHPToExternalSupplier(
        ihp: InventoryHoldingPoint,
        transportTime: RVariableIfc? = null,
        shipmentFormation: ShipmentFormation? = null,
    ) = attachToExternalSupplier(ihp, transportTime, shipmentFormation)

    @JvmOverloads
    fun attachToSupplier(
        supplier: NetworkNodeIfc,
        customer: NetworkNodeIfc,
        transportTime: RVariableIfc? = null,
        shipmentFormation: ShipmentFormation? = null,
    ) {
        require(supplier in myNodes) {
            "the provided supplier has not been added to the network"
        }
        require(customer in myNodes) {
            "the provided customer has not been added to the network"
        }
        require(customer !in myCustomerNodes) {
            "the supplied customer node is already a customer node"
        }
        when (val s = transportStrategy) {
            is TransportStrategy.SharedCarrier -> {
                require(transportTime == null) {
                    "transportTime is not supported under SharedCarrier"
                }
            }
            is TransportStrategy.PerIHPTimeBased -> {
                val shipper = supplier.demandCarrier as TimeBasedDemandCarrier
                if (transportTime != null) {
                    shipper.setTransportTime(customer, transportTime)
                } else {
                    shipper.immediateTransportFlag = true
                    shipper.registerDestination(customer)
                }
            }
            is TransportStrategy.NetworkTimeBased -> {
                s.carrier.setTransportTime(
                    supplier, customer,
                    transportTime ?: ConstantRV.ZERO,
                )
            }
        }
        configureShipmentFormation(supplier, customer, shipmentFormation)
        myNodes[supplier]!! += customer
        myCustomerNodes += customer
        customer.demandFiller = supplier
        addToLevel(customer, supplier.level + 1)
    }

    /** Typed overload for [InventoryHoldingPoint]-only edges. */
    @JvmOverloads
    fun attachIHPToSupplier(
        supplier: InventoryHoldingPoint,
        customer: InventoryHoldingPoint,
        transportTime: RVariableIfc? = null,
    ) = attachToSupplier(supplier, customer, transportTime)

    // -- demand generators ----------------------------------------------

    @JvmOverloads
    fun attachDemandGenerator(
        supplier: NetworkNodeIfc,
        type: ItemType,
        timeUntilNext: RVariableIfc,
        name: String? = null,
        transportTime: RVariableIfc? = null,
        shipmentFormation: ShipmentFormation? = null,
    ): DemandGenerator {
        val dg = DemandGenerator(
            supplyChainModel = supplyChainModel,
            itemType = type,
            timeUntilFirstRV = timeUntilNext,
            timeBtwEventsRV = timeUntilNext,
            name = name,
        )
        attachDemandGenerator(supplier, dg, transportTime, shipmentFormation)
        return dg
    }

    @JvmOverloads
    fun attachDemandGenerator(
        supplier: NetworkNodeIfc,
        generator: DemandGenerator,
        transportTime: RVariableIfc? = null,
        shipmentFormation: ShipmentFormation? = null,
    ) {
        require(supplier in myNodes) {
            "the provided supplier has not been added to the network"
        }
        val generators = myDemandGenerators.getOrPut(supplier) { mutableListOf() }
        require(generator !in generators) {
            "the provided generator is already attached to the supplier"
        }
        when (val s = transportStrategy) {
            is TransportStrategy.SharedCarrier -> {
                require(transportTime == null) {
                    "transportTime is not supported under SharedCarrier"
                }
            }
            is TransportStrategy.PerIHPTimeBased -> {
                val shipper = supplier.demandCarrier as TimeBasedDemandCarrier
                if (transportTime != null) {
                    shipper.setTransportTime(generator, transportTime)
                } else {
                    shipper.immediateTransportFlag = true
                    shipper.registerDestination(generator)
                }
            }
            is TransportStrategy.NetworkTimeBased -> {
                s.carrier.setTransportTime(
                    supplier, generator,
                    transportTime ?: ConstantRV.ZERO,
                )
            }
        }
        configureShipmentFormation(supplier, generator, shipmentFormation)
        generators += generator
        generator.demandFiller = supplier
    }

    /** Typed delegate for IHP-only callers. */
    @JvmOverloads
    fun attachDemandGeneratorToIHP(
        supplier: InventoryHoldingPoint,
        type: ItemType,
        timeUntilNext: RVariableIfc,
        name: String? = null,
        transportTime: RVariableIfc? = null,
    ): DemandGenerator = attachDemandGenerator(supplier, type, timeUntilNext, name, transportTime)

    /** Typed delegate for IHP-only callers. */
    @JvmOverloads
    fun attachDemandGeneratorToIHP(
        supplier: InventoryHoldingPoint,
        generator: DemandGenerator,
        transportTime: RVariableIfc? = null,
    ) = attachDemandGenerator(supplier, generator, transportTime)

    fun getDemandGenerators(node: NetworkNodeIfc): List<DemandGenerator> =
        myDemandGenerators[node]?.toList() ?: emptyList()

    // -- strategy-specific operations -----------------------------------

    /**
     * Flip every per-node carrier into "immediate transport" mode so
     * external demand generators (with no pre-registered transport
     * time) can ship through it. Subsequent nodes added after this
     * call also inherit the flag.
     *
     * Only meaningful under [TransportStrategy.PerIHPTimeBased].
     */
    fun allowExternalDemandGenerators() {
        check(transportStrategy is TransportStrategy.PerIHPTimeBased) {
            "allowExternalDemandGenerators requires TransportStrategy.PerIHPTimeBased"
        }
        myAllowExternalDemandGeneratorsFlag = true
        for (node in myNodes.keys) {
            (node.demandCarrier as TimeBasedDemandCarrier).immediateTransportFlag = true
        }
    }

    /**
     * Register [sender] as a no-delay destination of every node-filler
     * on the shared network carrier. Useful when an external demand
     * generator can send to any node via a
     * [DemandFillerFinderIfc] — this ensures the network carrier
     * knows how to ship filled demands back.
     *
     * Only meaningful under [TransportStrategy.NetworkTimeBased].
     */
    fun attachExternalDemandSender(sender: DemandSenderIfc) {
        val s = transportStrategy
        check(s is TransportStrategy.NetworkTimeBased) {
            "attachExternalDemandSender requires TransportStrategy.NetworkTimeBased"
        }
        s.carrier.attachDemandSenderToAllFillers(sender)
    }

    // -- cost responses --------------------------------------------------
    // The network's 4 public cost-response accessors look up the first
    // attached [ksl.modeling.supplychain.cost.DefaultMultiEchelonCostFormulation]
    // (typically constructed by the user after building the topology)
    // and delegate to its rollups.  They return null when no default
    // formulation is attached.  See `docs/supply-chain-cost-redesign.md`.
    //
    // The legacy 16 internal `myTotal*` Response fields and the
    // cost-computation block in `replicationEnded()` were retired in
    // Phase 4; the new architecture computes line items via per-source
    // observer calculators owned by the formulation, which roll up to
    // per-line, per-tier, per-(tier, line), and grand-total Responses
    // on the formulation itself.

    private val firstDefaultFormulation:
        ksl.modeling.supplychain.cost.DefaultMultiEchelonCostFormulation?
        get() = myCostFormulations.filterIsInstance<
            ksl.modeling.supplychain.cost.DefaultMultiEchelonCostFormulation
        >().firstOrNull()

    /**
     * Top-line total-cost Response from the first attached
     * [ksl.modeling.supplychain.cost.DefaultMultiEchelonCostFormulation],
     * or null when no default formulation is attached.  Construct one
     * via `DefaultMultiEchelonCostFormulation(network, params)` after
     * building the topology to populate this accessor.
     */
    val totalCostResponse: ksl.modeling.variable.ResponseCIfc?
        get() = firstDefaultFormulation?.totalCostResponse

    /** IHP-tier rollup Response from the first attached default formulation. */
    val totalIHPCostResponse: ksl.modeling.variable.ResponseCIfc?
        get() = firstDefaultFormulation?.byTierResponse(
            ksl.modeling.supplychain.cost.NodeTier.IHP)

    /** Cross-dock-tier rollup Response from the first attached default formulation. */
    val totalCrossDockCostResponse: ksl.modeling.variable.ResponseCIfc?
        get() = firstDefaultFormulation?.byTierResponse(
            ksl.modeling.supplychain.cost.NodeTier.CD)

    /** External-supplier-tier rollup Response from the first attached default formulation. */
    val totalExternalSupplierLoadingCostResponse: ksl.modeling.variable.ResponseCIfc?
        get() = firstDefaultFormulation?.byTierResponse(
            ksl.modeling.supplychain.cost.NodeTier.ES)

    /**
     * Per-line Backorder rollup Response from the first attached
     * default formulation, summing the continuous-rate backorder
     * cost across every per-IHP [ksl.modeling.supplychain.cost.BackorderCostCalculator].
     * Emits a rate in the time unit of the modeler's `backorderRate`.
     */
    val totalBackorderCostResponse: ksl.modeling.variable.ResponseCIfc?
        get() = firstDefaultFormulation?.byLineResponse(
            ksl.modeling.supplychain.cost.CostLine.Backorder)

    /**
     * Per-line Stockout rollup Response from the first attached
     * default formulation, summing the per-stockout-event cost across
     * every per-(IHP, item) [ksl.modeling.supplychain.cost.InventoryCostCalculator].
     */
    val totalStockoutCostResponse: ksl.modeling.variable.ResponseCIfc?
        get() = firstDefaultFormulation?.byLineResponse(
            ksl.modeling.supplychain.cost.CostLine.Stockout)

    /**
     * Per-line LostSale rollup Response from the first attached
     * default formulation, summing per-rejected-demand cost across
     * every per-(IHP, item) [ksl.modeling.supplychain.cost.InventoryCostCalculator].
     */
    val totalLostSaleCostResponse: ksl.modeling.variable.ResponseCIfc?
        get() = firstDefaultFormulation?.byLineResponse(
            ksl.modeling.supplychain.cost.CostLine.LostSale)

    /**
     * Per-line UnitShortage rollup Response from the first attached
     * default formulation, summing per-unit-short cost across every
     * per-(IHP, item) [ksl.modeling.supplychain.cost.InventoryCostCalculator].
     */
    val totalUnitShortageCostResponse: ksl.modeling.variable.ResponseCIfc?
        get() = firstDefaultFormulation?.byLineResponse(
            ksl.modeling.supplychain.cost.CostLine.UnitShortage)

    // -- AggregateInventoryResponseIfc delegation -----------------------

    override val aggregateOnHandInventory: AggregateTWResponse
        get() = myAggregateInventoryResponse.aggregateOnHandInventory
    override val aggregateAmountOnOrder: AggregateTWResponse
        get() = myAggregateInventoryResponse.aggregateAmountOnOrder
    override val aggregateAmountBackOrdered: AggregateTWResponse
        get() = myAggregateInventoryResponse.aggregateAmountBackOrdered
    override val aggregateNumberBackOrdered: AggregateTWResponse
        get() = myAggregateInventoryResponse.aggregateNumberBackOrdered
    override val aggregateAvgFirstFillRate: AggregateResponse
        get() = myAggregateInventoryResponse.aggregateAvgFirstFillRate
    override val aggregateAvgCustomerWaitTime: AggregateResponse
        get() = myAggregateInventoryResponse.aggregateAvgCustomerWaitTime
    override val aggregateNumberOfReplenishmentDemands: AggregateCounter
        get() = myAggregateInventoryResponse.aggregateNumberOfReplenishmentDemands

    override fun subscribeTo(r: AggregateInventoryResponseIfc) =
        myAggregateInventoryResponse.subscribeTo(r)

    override fun unsubscribeFrom(r: AggregateInventoryResponseIfc) =
        myAggregateInventoryResponse.unsubscribeFrom(r)

    // -- inner forwarding carrier ---------------------------------------

    /**
     * Adapter that forwards every transport request to whatever the
     * network's [demandCarrier] slot currently holds. Active only
     * under [TransportStrategy.SharedCarrier]; the other strategies
     * never see it.
     */
    private inner class ForwardingCarrier : DemandCarrierIfc {
        override fun transportDemand(demand: SupplyChainModel.Demand) =
            myDemandCarrierField.transportDemand(demand)
        override fun canShip(demand: SupplyChainModel.Demand): Boolean =
            myDemandCarrierField.canShip(demand)
    }
}
