package ksl.modeling.supplychain.inventory

import ksl.modeling.supplychain.*

import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.Interval
import ksl.utilities.statistic.StatisticIfc
import ksl.utilities.statistic.WeightedStatisticIfc

/**
 * A stocking point for a single [ItemType]. Holds inventory, fills
 * customer demands, requests replenishment from a supplier, and
 * optionally backlogs demands it cannot fill immediately.
 *
 * Construct with an [InventoryPolicyAbstract] that controls when
 * replenishment is requested. A [BackLogPolicyAbstract] is attached
 * separately by constructing one with this inventory as its parent —
 * the backlog policy's `init` block establishes the bidirectional link
 * via [setBackLogPolicy].
 *
 * @param parent the parent model element (must be reachable to a
 *        [SupplyChainModel] so replenishment demands can be created)
 * @param itemType the single [ItemType] this inventory carries
 * @param policy the replenishment policy
 * @param initialOnHand initial stock at the start of each replication
 * @param mayPartiallyFill whether the inventory may partially fill
 *        an incoming demand
 * @param name optional model-element name
 *
 * See `sc.inventorylayer.Inventory`
 */
open class Inventory @JvmOverloads constructor(
    parent: ModelElement,
    itemType: ItemType,
    policy: InventoryPolicyAbstract,
    initialOnHand: Int = 0,
    mayPartiallyFill: Boolean = true,
    name: String? = null,
) : DemandFillerAbstract(parent, initialAvailability = true, name = name),
    InventoryIfc, DemandSenderIfc, InventoryStatisticsIfc {

    val itemType: ItemType = itemType

    // ----------------------------------------------------------------- statistic-bearing children

    private val myStockOutIndicator: TWResponse =
        TWResponse(this, name = "${this.name} : Fraction of Time w/o Stock")

    private val myOnHand: TWResponse =
        TWResponse(
            this,
            name = "${this.name} : On Hand",
            initialValue = initialOnHand.toDouble(),
            allowedDomain = Interval(0.0, Double.POSITIVE_INFINITY),
        )

    private val myOnOrder: TWResponse =
        TWResponse(this, name = "${this.name} : On Order")

    private val myFirstFillRate: Response =
        Response(this, name = "${this.name} : First Fill Rate")

    private val myOrderCounter: Counter =
        Counter(this, name = "${this.name} : #Replenishments")

    // Phase-1 (cost redesign) — physical-event counters that drive
    // the new cost observable surface.  Counters reset at warmup
    // automatically (KSL's Counter.warmUp resets via resetCounter),
    // so values read at REPLICATION_ENDED reflect only the post-
    // warmup observation window.

    private val myStockoutCounter: Counter =
        Counter(this, name = "${this.name} : #Stockouts")

    private val myLostSaleCounter: Counter =
        Counter(this, name = "${this.name} : #LostSales")

    private val myTotalUnitsShort: Counter =
        Counter(this, name = "${this.name} : Total Units Short")

    private val myTotalUnitsOrdered: Counter =
        Counter(this, name = "${this.name} : Total Units Ordered")

    private val myTimeBtwOrders: Response =
        Response(this, name = "${this.name} : Time btw Orders")

    private val myOrderAmount: Response =
        Response(this, name = "${this.name} : Order Size")

    private val myTBD: Response =
        Response(this, name = "${this.name} : Time Btw Demands")

    private val myDemandSize: Response =
        Response(this, name = "${this.name} : Demand Size")

    /** Read-only response views for external observers and aggregates. */
    val onHandResponse: TWResponseCIfc get() = myOnHand
    val onOrderResponse: TWResponseCIfc get() = myOnOrder
    val stockOutIndicatorResponse: TWResponseCIfc get() = myStockOutIndicator
    val firstFillRateResponse: ResponseCIfc get() = myFirstFillRate
    val orderCounterCounter: CounterCIfc get() = myOrderCounter
    val timeBtwOrdersResponse: ResponseCIfc get() = myTimeBtwOrders
    val orderAmountResponse: ResponseCIfc get() = myOrderAmount
    val timeBtwDemandsResponse: ResponseCIfc get() = myTBD
    val demandSizeResponse: ResponseCIfc get() = myDemandSize

    /**
     * Counter of stockout events (per-replication, post-warmup).  A
     * stockout fires every time a customer demand arrives and finds
     * `amountOnHand < remainingDemand`, regardless of whether the
     * demand is then backlogged or rejected.  Drives the stockout-cost
     * line in the cost model.
     */
    val stockoutCounter: CounterCIfc get() = myStockoutCounter

    /**
     * Counter of lost-sale events (per-replication, post-warmup).
     * Fires when a stockout demand is rejected (either because the
     * demand is non-backloggable, or because this inventory is not
     * configured to allow backlogging).  Subset of [stockoutCounter]
     * — a stockout that gets backlogged is not a lost sale.
     */
    val lostSaleCounter: CounterCIfc get() = myLostSaleCounter

    /**
     * Total units short across all stockout events in the replication
     * (post-warmup).  Incremented by `remainingDemand − amountOnHand`
     * at each stockout.  Drives the unit-shortage-cost line.
     */
    val totalUnitsShort: CounterCIfc get() = myTotalUnitsShort

    /**
     * Total units ordered upstream across all replenishment requests
     * in the replication (post-warmup).  Incremented by `qty` at each
     * `requestReplenishment(qty)` call.  Drives variable-quantity
     * ordering-cost formulations.
     */
    val totalUnitsOrdered: CounterCIfc get() = myTotalUnitsOrdered

    // ----------------------------------------------------------------- configuration flags

    /** Whether replenishment demands this inventory sends may be partially filled. */
    var permitPartialFilling: Boolean = true

    /** Whether replenishment demands this inventory sends may be backlogged. */
    var permitBackLogging: Boolean = true

    /** Whether incoming customer demands may be backlogged here. */
    var allowBackLogging: Boolean = false
        internal set

    var mayPartiallyFillDemands: Boolean = mayPartiallyFill

    // ----------------------------------------------------------------- policy & backlog

    private var myInventoryPolicy: InventoryPolicyAbstract = policy

    init {
        // Bidirectional setup: policy <-> inventory.
        policy.setInventory(this)
    }

    val inventoryPolicy: InventoryPolicyAbstract get() = myInventoryPolicy

    fun setInventoryPolicy(newPolicy: InventoryPolicyAbstract) {
        myInventoryPolicy = newPolicy
        newPolicy.setInventory(this)
    }

    private var myBackLogPolicy: BackLogPolicyAbstract? = null

    /** Currently attached backlog policy, or null. */
    val backLogPolicy: BackLogPolicyAbstract? get() = myBackLogPolicy

    /**
     * Attach a [BackLogPolicyAbstract]. Called by the policy's init
     * block when the policy is constructed with this inventory as its
     * parent. Once set, the policy cannot be replaced.
     */
    internal fun setBackLogPolicy(policy: BackLogPolicyAbstract) {
        if (myBackLogPolicy != null) return
        myBackLogPolicy = policy
        allowBackLogging = true
    }

    // ----------------------------------------------------------------- filler/finder state

    override var demandFiller: DemandFillerIfc? = null
    override var demandFillerFinder: DemandFillerFinderIfc? = null

    var replenishmentRequester: ReplenishmentRequesterIfc? = null

    // ----------------------------------------------------------------- arrival hooks

    var demandArrivalListener: InventoryDemandArrivalListenerIfc? = null
    var replenishmentArrivalListener: InventoryDemandArrivalListenerIfc? = null

    private var myAmountPending: Int = 0
    private var myTimeLastOrder: Double = 0.0
    private var myTimeLastDemandArrived: Double = 0.0
    private var myDemandArrivalCounter: Long = 0L

    val amountPending: Int get() = myAmountPending
    val totalArrivedDemand: Long get() = myDemandArrivalCounter
    val timeLastDemandArrived: Double get() = myTimeLastDemandArrived

    var initialOnHand: Int = initialOnHand
        set(value) {
            require(value >= 0) { "initial on hand must be >= 0" }
            field = value
            myOnHand.initialValue = value.toDouble()
            myStockOutIndicator.initialValue = if (value == 0) 1.0 else 0.0
        }

    // ----------------------------------------------------------------- InventoryIfc

    override val amountOnHand: Int get() = myOnHand.value.toInt()
    override val amountOnOrder: Int get() = myOnOrder.value.toInt()

    override val backLogInfo: BackLogInfoIfc
        get() = myBackLogPolicy ?: error("$name has no backlog policy")

    override val inventoryPosition: Int
        get() = amountOnHand + amountOnOrder + amountPending -
            (myBackLogPolicy?.amountBackLogged ?: 0)

    // ----------------------------------------------------------------- DemandSenderIfc

    override fun mightRequest(type: ItemType): Boolean = type == itemType

    // ----------------------------------------------------------------- DemandFillerIfc

    override fun canFillItemType(demand: SupplyChainModel.Demand): Boolean =
        demand.itemType == itemType

    override fun canFillItemType(type: ItemType): Boolean = type == itemType

    override val itemTypes: Collection<ItemType> = listOf(itemType)

    override fun receive(demand: SupplyChainModel.Demand) {
        if (!isAvailable) {
            demand.setStatus(DemandStatusCode.FillerUnavailable)
            demand.reject()
            return
        }
        if (!canFillItemType(demand.itemType)) {
            demand.setStatus(DemandStatusCode.ItemTypeMismatch)
            demand.reject()
            return
        }
        val onHand = amountOnHand
        val amtNeeded = demand.remainingDemand
        if (onHand >= amtNeeded) {
            demand.setStatus(DemandStatusCode.ImmediateFill)
            demand.receive(this)
        } else {
            // Insufficient stock — record the stockout event and the
            // units short before routing to backlog / reject.
            myStockoutCounter.increment()
            myTotalUnitsShort.increment((amtNeeded - onHand).toDouble())
            if (!demand.allowBackLogging) {
                demand.setStatus(
                    if (allowBackLogging)
                        DemandStatusCode.NonBackloggableDemandToBackloggingReceiverNotImmediateFill
                    else
                        DemandStatusCode.NonBackloggableDemandToNonBackloggingReceiverNotImmediateFill
                )
                myLostSaleCounter.increment()
                demand.reject()
            } else {
                if (allowBackLogging) {
                    demand.setStatus(DemandStatusCode.WillBeBacklogged)
                    demand.receive(this)
                } else {
                    demand.setStatus(
                        DemandStatusCode.BackloggableDemandToNonBackloggingReceiverNotImmediateFill
                    )
                    myLostSaleCounter.increment()
                    demand.reject()
                }
            }
        }
    }

    override fun fillDemand(demand: SupplyChainModel.Demand) {
        val size = demand.originalAmountDemanded
        myDemandArrivalCounter += size
        myTBD.value = time - myTimeLastDemandArrived
        myTimeLastDemandArrived = time
        myDemandSize.value = size.toDouble()

        if (demandCarrier != null) {
            demand.addStateChangeListener(demandFilledShipListener)
        }
        demand.process(this)

        if (demand.status == DemandStatusCode.ImmediateFill) {
            fillImmediately(demand)
        } else {
            backLogInitialDemand(demand)
        }

        demandArrivalListener?.demandArrived(this, demand)
    }

    override fun determineRequestStatus(
        demand: SupplyChainModel.Demand,
    ): DemandStatusCode {
        if (!isAvailable) return DemandStatusCode.FillerUnavailable
        if (!canFillItemType(demand.itemType)) return DemandStatusCode.ItemTypeMismatch
        val onHand = amountOnHand
        val amtNeeded = demand.remainingDemand
        if (onHand >= amtNeeded) return DemandStatusCode.ImmediateFill
        if (!demand.allowBackLogging) {
            return if (allowBackLogging)
                DemandStatusCode.NonBackloggableDemandToBackloggingReceiverNotImmediateFill
            else
                DemandStatusCode.NonBackloggableDemandToNonBackloggingReceiverNotImmediateFill
        }
        return if (allowBackLogging) DemandStatusCode.WillBeBacklogged
        else DemandStatusCode.BackloggableDemandToNonBackloggingReceiverNotImmediateFill
    }

    override fun willReject(demand: SupplyChainModel.Demand): Boolean {
        if (!isAvailable) return true
        if (!canFillItemType(demand.itemType)) return true
        val onHand = amountOnHand
        val amtNeeded = demand.remainingDemand
        if (onHand >= amtNeeded) return false
        if (!demand.allowBackLogging) return true
        return !allowBackLogging
    }

    override fun negotiate(demand: SupplyChainModel.Demand): DemandMessageIfc {
        demand.negotiate()
        val canFill = canFillItemType(demand)
        val status = determineRequestStatus(demand)
        val amt = allocateInventory(demand)
        return DemandMessage(
            demandFiller = this,
            timeStamp = time,
            canFillItemType = canFill,
            requestStatus = status,
            requestFillAmount = amt,
            inventory = this,
            mayPartiallyFillDemands = mayPartiallyFillDemands,
            mayBackLogDemands = allowBackLogging,
        )
    }

    // ----------------------------------------------------------------- internal mutators

    internal fun allocateInventory(demand: SupplyChainModel.Demand): Int {
        val onHand = amountOnHand
        val amtNeeded = demand.remainingDemand
        return when {
            onHand >= amtNeeded -> amtNeeded
            demand.allowPartialFilling -> onHand
            else -> 0
        }
    }

    internal fun fillBackLog(demand: SupplyChainModel.Demand, amount: Int) {
        decrementOnHand(amount)
        demand.fill(amount)
        checkInventory()
    }

    /**
     * Request replenishment of [replenishmentQty] units. Delegated to
     * [replenishmentRequester] when set; otherwise this inventory builds
     * the demand and sends it directly to its filler.
     */
    internal fun requestReplenishment(replenishmentQty: Int) {
        myOrderCounter.increment()
        myTotalUnitsOrdered.increment(replenishmentQty.toDouble())
        myTimeBtwOrders.value = time - myTimeLastOrder
        myTimeLastOrder = time
        myOrderAmount.value = replenishmentQty.toDouble()

        val demand = createReplenishmentDemand(replenishmentQty)
        demand.totalArrivedDemand = totalArrivedDemand
        demand.addStateChangeListener(replenishmentListener)
        // Attach the rejection listener here (not in sendReplenishmentDemand)
        // so that external replenishment requesters (InventoryHolderAbstract,
        // WarehouseAbstract, etc.) also get the rejection handler.
        demand.addStateChangeListener(replenishmentRejectionListener)

        val requester = replenishmentRequester
        if (requester != null) requester.requestReplenishment(this, demand)
        else sendReplenishmentDemand(demand)
    }

    private fun createReplenishmentDemand(qty: Int): SupplyChainModel.Demand {
        val sc = findEnclosingSupplyChainModel()
        val demand = sc.createDemand(itemType, qty)
        myAmountPending += qty
        demand.setAllowBackLogging(permitBackLogging)
        demand.setAllowPartialFilling(permitPartialFilling)
        return demand
    }

    private fun sendReplenishmentDemand(demand: SupplyChainModel.Demand) {
        demand.setDemandSender(this)
        val filler = demandFiller
            ?: demandFillerFinder?.findDemandFiller(demand)
            ?: throw NoDemandFillerFoundException(
                "No demand filler found for replenishment $demand"
            )
        demand.setFiller(filler)
        // replenishmentRejectionListener is attached upstream in
        // requestReplenishment so external requesters also benefit.
        demand.sent()
        filler.receive(demand)
    }

    /**
     * Walks the model-element parent chain to find the enclosing
     * [SupplyChainModel]. Required because [SupplyChainModel.Demand] is
     * an inner class whose construction needs an explicit model reference.
     */
    private fun findEnclosingSupplyChainModel(): SupplyChainModel {
        // Use `internal var myParentModelElement` rather than the
        // `protected val parent`, because protected is class-scoped in
        // Kotlin and we walk through other ModelElement instances.
        var p: ModelElement? = this.myParentModelElement
        while (p != null) {
            if (p is SupplyChainModel) return p
            p = p.myParentModelElement
        }
        error("$name is not parented under a SupplyChainModel")
    }

    internal fun decrementOnHand(amt: Int) {
        myOnHand.decrement(amt.toDouble())
        check(myOnHand.value >= 0.0) { "on hand went negative" }
        if (myOnHand.value < 1.0) myStockOutIndicator.value = 1.0
    }

    internal fun incrementOnHand(amt: Int) {
        myOnHand.increment(amt.toDouble())
        if (myOnHand.value > 0.0) myStockOutIndicator.value = 0.0
    }

    internal fun decrementOnOrder(amount: Int) {
        myOnOrder.decrement(amount.toDouble())
        check(myOnOrder.value >= 0.0) { "on order went negative" }
    }

    internal fun incrementOnOrder(amount: Int) {
        myOnOrder.increment(amount.toDouble())
    }

    internal fun decrementAmountPending(amount: Int) {
        myAmountPending -= amount
        check(myAmountPending >= 0) { "amount pending went negative" }
    }

    internal fun incrementAmountPending(amount: Int) {
        myAmountPending += amount
    }

    internal fun checkInventory() {
        myInventoryPolicy.checkInventoryInternal()
    }

    private fun fillImmediately(demand: SupplyChainModel.Demand) {
        myFirstFillRate.value = 1.0
        // Use remainingDemand (via allocateInventory), not
        // originalAmountDemanded (audit finding E).  For a fresh
        // single-hop customer demand these are equal, but a demand that
        // was partially filled upstream (a forwarded / multi-hop demand,
        // remainingDemand < originalAmountDemanded) would otherwise have
        // on-hand over-decremented by the already-filled portion —
        // silent stock loss.  This matches backLogInitialDemand, which
        // already uses allocateInventory.
        val amt = allocateInventory(demand)
        decrementOnHand(amt)
        demand.fill(amt)
        checkInventory()
    }

    private fun backLogInitialDemand(demand: SupplyChainModel.Demand) {
        myFirstFillRate.value = 0.0
        val amt = allocateInventory(demand)
        if (amt > 0) {
            decrementOnHand(amt)
            demand.fill(amt)
        }
        val policy = myBackLogPolicy
            ?: error("backlog requested but no backlog policy attached")
        policy.backlogInternal(demand)
        checkInventory()
    }

    // ----------------------------------------------------------------- listeners (Java's inner classes collapse to lambdas)

    private val replenishmentListener =
        DemandStateChangeListener { d, from, to ->
            // Note: when a replenishment demand is in an order, the order
            // intercepts the RECEIVED transition and only replays listeners
            // on batch-completion. The isPartOfAnOrder check inside
            // demandRequestReceivedByFiller controls whether fillDemand
            // fires; the on-order counter increment runs either way.
            //
            // Accounting fires on STORED (not DELIVERED) — the framework's
            // delivery endpoint (default PassThroughStorageEndpoint) is
            // responsible for advancing Delivered → Stored.  This puts the
            // on-hand increment at the truthful moment ("items integrated
            // into inventory") rather than at "carrier dropped them off."
            // See `docs/supply-chain-framework-design.md` §3.
            when (to.stateId) {
                DemandStateId.Received -> demandRequestReceivedByFiller(d)
                DemandStateId.Stored -> replenishmentDemandDelivered(d)
                DemandStateId.Rejected -> releaseReplenishmentReservation(d, from)
                DemandStateId.Cancelled -> releaseReplenishmentReservation(d, from)
                else -> {}
            }
        }

    /**
     * A replenishment terminated without delivery (rejected by the
     * filler, or cancelled).  Release whichever inventory reservation
     * it still holds so the inventory position
     * (`onHand + onOrder + amountPending − backlog`) stays correct:
     *
     * - terminated **before** Received → it was still `amountPending`
     *   (on-order was never incremented); release pending.
     * - terminated **at/after** Received → `demandRequestReceivedByFiller`
     *   already moved it from pending to on-order; release on-order.
     *
     * Without this, a rejected replenishment leaked `amountPending`
     * permanently (audit finding B): the inflated position made the
     * inventory under-order for the rest of the replication — silently,
     * since the `[0, ∞)` domain check on pending never trips on a
     * *positive* imbalance.  The leak was masked only because the
     * default [InventoryReplenishmentRejectionListener] throws on
     * rejection; a subclass that handles rejection gracefully would
     * have leaked.
     */
    private fun releaseReplenishmentReservation(
        demand: SupplyChainModel.Demand,
        from: SupplyChainModel.DemandState?,
    ) {
        val qty = demand.originalAmountDemanded
        when (from?.stateId) {
            DemandStateId.Sent -> decrementAmountPending(qty)
            DemandStateId.Received, DemandStateId.InProcess -> decrementOnOrder(qty)
            else -> error(
                "Inventory $name: replenishment $demand reached a terminal " +
                    "failure from unexpected state ${from?.stateId}"
            )
        }
    }

    /**
     * Policy-level reaction invoked when a replenishment this inventory
     * sent is rejected by its filler.  Defaults to an
     * [InventoryReplenishmentRejectionListener] whose dispatch methods
     * throw (fail-loud).  Replace it **before simulating** with a
     * subclass that handles rejection gracefully — to retry, route to an
     * alternate supplier, or record-and-continue.
     *
     * This is purely the policy reaction.  The inventory's own
     * accounting — releasing the `amountPending` / `onOrder` reservation
     * held by the rejected replenishment — happens independently in
     * [replenishmentListener] (see [releaseReplenishmentReservation]),
     * which fires before this listener.  So a graceful handler does not
     * leak the inventory position (audit finding B).
     *
     * Setting this after replenishments have already been sent in the
     * current replication does not retroactively change the listener on
     * those in-flight demands.
     */
    var replenishmentRejectionListener: DemandRejectionListener =
        InventoryReplenishmentRejectionListener(this)

    private val demandFilledShipListener =
        DemandStateChangeListener { d, _, to ->
            if (to.stateId === DemandStateId.Filled) {
                val carrier = demandCarrier
                if (carrier != null) carrier.transportDemand(d)
                else { d.ship(); d.deliver() }
            }
        }

    private fun demandRequestReceivedByFiller(demand: SupplyChainModel.Demand) {
        val qty = demand.originalAmountDemanded
        // TWResponse-balance note (see docs/supply-chain-architecture.md §7):
        // BOTH increment paths (in-order and standalone) must run on every
        // RECEIVED transition because `replenishmentDemandDelivered` below
        // unconditionally calls `decrementOnOrder`. An early-return guard
        // here on `isPartOfAnOrder` (as Java had) would skip the increment
        // for ordered replenishment demands but leave the matching
        // decrement in place — net underflow on KSL's [0, ∞) on-order
        // counter. Only the `fillDemand` call below is gated on
        // `!isPartOfAnOrder` because the enclosing order replays fill on
        // batch-completion.
        decrementAmountPending(qty)
        incrementOnOrder(qty)
        if (!demand.isPartOfAnOrder) demand.filler!!.fillDemand(demand)
    }

    private fun replenishmentDemandDelivered(demand: SupplyChainModel.Demand) {
        val replenishmentQty = demand.amountFilled
        decrementOnOrder(replenishmentQty)
        incrementOnHand(replenishmentQty)
        if (allowBackLogging) myBackLogPolicy?.fillBackLogsInternal()
        checkInventory()
        replenishmentArrivalListener?.demandArrived(this, demand)
    }

    // ----------------------------------------------------------------- initialization

    private val checkInventoryAction = CheckInventoryAction()

    private inner class CheckInventoryAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) { checkInventory() }
    }

    override fun initialize() {
        super.initialize()
        myAmountPending = 0
        myTimeLastOrder = 0.0
        myTimeLastDemandArrived = 0.0
        myDemandArrivalCounter = 0L
        checkInventoryAction.schedule(0.0)
    }

    // ----------------------------------------------------------------- policy parameter pass-through

    val inventoryPolicyParameters: DoubleArray
        get() = myInventoryPolicy.getPolicyParameters()

    fun setInventoryPolicyParameters(parameters: DoubleArray) {
        myInventoryPolicy.setPolicyParametersInternal(parameters)
        myInventoryPolicy.checkInventoryInternal()
    }

    fun setInitialPolicyParameters(parameters: DoubleArray) {
        myInventoryPolicy.setInitialPolicyParameters(parameters)
    }

    // ----------------------------------------------------------------- aggregate-response wiring

    fun attachAggregateInventoryResponse(r: AggregateInventoryResponseIfc) {
        // The on-hand / on-order subscriptions were previously skipped
        // because [ksl.modeling.variable.AggregateTWResponse] started at
        // 0 while sources started positive, producing observer-init drift
        // that violated TWResponse's `[0, ∞)` domain on the first
        // decrement.  That defect is now fixed at the AggregateTWResponse
        // level (observe() syncs the aggregate's initialValue from the
        // source), so these subscriptions are restored.  Inventory counts
        // are real, non-negative quantities; the aggregate now tracks
        // them correctly.
        r.aggregateOnHandInventory.observe(myOnHand)
        r.aggregateAmountOnOrder.observe(myOnOrder)
        r.aggregateNumberOfReplenishmentDemands.observe(myOrderCounter)
        r.aggregateAvgFirstFillRate.observe(myFirstFillRate)
        myBackLogPolicy?.attachAggregateInventoryResponse(r)
    }

    fun detachAggregateInventoryResponse(r: AggregateInventoryResponseIfc) {
        r.aggregateOnHandInventory.remove(myOnHand)
        r.aggregateAmountOnOrder.remove(myOnOrder)
        r.aggregateNumberOfReplenishmentDemands.remove(myOrderCounter)
        r.aggregateAvgFirstFillRate.remove(myFirstFillRate)
        myBackLogPolicy?.detachAggregateInventoryResponse(r)
    }

    // ----------------------------------------------------------------- InventoryStatisticsIfc

    override val onHandWithinReplication: WeightedStatisticIfc
        get() = myOnHand.withinReplicationStatistic
    override val onHandAcrossReplications: StatisticIfc
        get() = myOnHand.acrossReplicationStatistic
    override val stockOutIndicatorWithinReplication: WeightedStatisticIfc
        get() = myStockOutIndicator.withinReplicationStatistic
    override val stockOutIndicatorAcrossReplications: StatisticIfc
        get() = myStockOutIndicator.acrossReplicationStatistic
    override val onOrderWithinReplication: WeightedStatisticIfc
        get() = myOnOrder.withinReplicationStatistic
    override val onOrderAcrossReplications: StatisticIfc
        get() = myOnOrder.acrossReplicationStatistic
    override val firstFillRateWithinReplication: WeightedStatisticIfc
        get() = myFirstFillRate.withinReplicationStatistic
    override val firstFillRateAcrossReplications: StatisticIfc
        get() = myFirstFillRate.acrossReplicationStatistic
    override val orderCounterAcrossReplications: StatisticIfc
        get() = myOrderCounter.acrossReplicationStatistic
    override val orderCounterWithinReplication: Double
        get() = myOrderCounter.value
    override val timeBtwOrdersWithinReplication: WeightedStatisticIfc
        get() = myTimeBtwOrders.withinReplicationStatistic
    override val timeBtwOrdersAcrossReplications: StatisticIfc
        get() = myTimeBtwOrders.acrossReplicationStatistic
    override val timeBtwDemandsWithinReplication: WeightedStatisticIfc
        get() = myTBD.withinReplicationStatistic
    override val timeBtwDemandsAcrossReplications: StatisticIfc
        get() = myTBD.acrossReplicationStatistic
    override val demandSizeWithinReplication: WeightedStatisticIfc
        get() = myDemandSize.withinReplicationStatistic
    override val demandSizeAcrossReplications: StatisticIfc
        get() = myDemandSize.acrossReplicationStatistic
    override val orderAmountWithinReplication: WeightedStatisticIfc
        get() = myOrderAmount.withinReplicationStatistic
    override val orderAmountAcrossReplications: StatisticIfc
        get() = myOrderAmount.acrossReplicationStatistic

    override val backLogStatistics: BackLogStatisticsIfc
        get() = myBackLogPolicy?.getBackLogStatistics()
            ?: error("$name has no backlog policy; no backlog statistics")

    override fun toString(): String = buildString {
        append("Inventory: ").append(name)
        append("  Type: ").append(itemType.name)
        append("  OH: ").append(amountOnHand)
        append("  OO: ").append(amountOnOrder)
        append("  PO: ").append(amountPending)
        val bl = myBackLogPolicy?.amountBackLogged ?: 0
        append("  BL: ").append(bl)
        append("  IP: ").append(inventoryPosition)
    }

    companion object {
        /**
         * Builds an inventory with an (r, Q) policy and a FIFO backlog
         * queue already attached. Convenience helper for the common case.
         *
         * Replaces the static factory
         * `Inventory.createReorderPointReorderQuantityInventory` in the
         * legacy Java source.
         */
        @JvmStatic
        @JvmOverloads
        fun createReorderPointReorderQuantityInventory(
            parent: ModelElement,
            itemType: ItemType,
            reorderPoint: Int,
            reorderQty: Int,
            initialOnHand: Int = 0,
            name: String? = null,
        ): Inventory {
            val policy = InventoryPolicyReorderPointReorderQuantity(
                parent, reorderPoint, reorderQty,
                name = "RQPolicy:${name ?: ""}",
            )
            val inventory = Inventory(parent, itemType, policy, initialOnHand, name = name)
            // Constructing the BackLogQueue attaches it via its init block;
            // the discarded reference is intentional.
            BackLogQueue(inventory, name = "${inventory.name} : BackLogQ")
            return inventory
        }

        /**
         * Builds an inventory with an (r, S) policy and a FIFO backlog queue.
         *
         * Replaces `Inventory.createReorderPointOrderUpToLevelInventory`.
         */
        @JvmStatic
        @JvmOverloads
        fun createReorderPointOrderUpToLevelInventory(
            parent: ModelElement,
            itemType: ItemType,
            reorderPoint: Int,
            orderUpToPoint: Int,
            initialOnHand: Int = 0,
            name: String? = null,
        ): Inventory {
            val policy = InventoryPolicyReorderPointOrderUpToLevel(
                parent, reorderPoint, orderUpToPoint,
                name = "RSPolicy:${name ?: ""}",
            )
            val inventory = Inventory(parent, itemType, policy, initialOnHand, name = name)
            BackLogQueue(inventory, name = "${inventory.name} : BackLogQ")
            return inventory
        }
    }
}
