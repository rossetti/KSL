package ksl.modeling.supplychain

import ksl.modeling.entity.ProcessModel
import ksl.simulation.ModelElement

/**
 * A model element that hosts a supply-chain submodel. Acts as the umbrella
 * within which [Demand]s and [Order]s live, mirroring how [ProcessModel]
 * hosts `Entity`. Multiple [SupplyChainModel] instances can coexist within
 * a single KSL [ksl.simulation.Model].
 *
 * Extends [ProcessModel] so supply-chain elements can also participate in
 * process-oriented modeling (entities, resources, KSL processes).
 *
 * Demands and orders are created via [createDemand] and
 * [createOrderPlaceholder]; their constructors are not exposed.
 *
 * @see sc.inventorylayer.Demand
 * @see sc.inventorylayer.Order
 */
open class SupplyChainModel(
    parent: ModelElement,
    name: String? = null,
) : ProcessModel(parent, name) {

    /**
     * Creates a new demand within this supply-chain model. The demand starts
     * in [inPreparation].
     *
     * @param itemType the [ItemType] of this demand
     * @param amountDemanded units required; must be > 0; defaults to 1
     * @param demandName optional name
     */
    fun createDemand(
        itemType: ItemType,
        amountDemanded: Int = 1,
        demandName: String? = null,
    ): Demand = Demand(itemType, amountDemanded, demandName)

    /**
     * Framework-level Delivered dispatch hook.  Called from
     * [Demand.transitionTo] **after** all user state-change
     * listeners have fired for the Delivered transition.  Looks up
     * the demand's destination's
     * [ksl.modeling.supplychain.flow.DeliveryEndpointIfc] and
     * invokes [ksl.modeling.supplychain.flow.DeliveryEndpointIfc.onDelivered]
     * on it.  Falls back to
     * [ksl.modeling.supplychain.flow.PassThroughStorageEndpoint]
     * (immediate `store()`) when the demand's sender is not a
     * [ksl.modeling.supplychain.inventory.NetworkNodeIfc] — covering
     * customer-demand generators, test fixtures, and any other
     * destination that didn't opt in to dock or routing modelling.
     *
     * Done as a post-listener hook (rather than as a listener
     * itself attached at creation time) so that the cascade to
     * Stored happens *after* all user observers on Delivered have
     * seen the Delivered transition.  This preserves the
     * chronological intuition: user listeners see Delivered first,
     * then Stored.
     */
    private fun dispatchDeliveredEndpoint(d: Demand) {
        val sender = d.demandSender
        val endpoint =
            (sender as? ksl.modeling.supplychain.inventory.NetworkNodeIfc)
                ?.deliveryEndpoint
                ?: ksl.modeling.supplychain.flow.PassThroughStorageEndpoint
        endpoint.onDelivered(d)
    }

    /**
     * Creates a new order within this supply-chain model. Starts in
     * [orderCreated]; add demands via [Order.addDemand] before sending.
     */
    fun createOrder(
        orderName: String? = null,
        allowBackLogging: Boolean = true,
        allowPartialShipping: Boolean = false,
        allowCancelling: Boolean = false,
    ): Order = Order(orderName, allowBackLogging, allowPartialShipping, allowCancelling)

    /**
     * Creates a new [DemandLoad] within this supply-chain model. Used by
     * transportlayer load builders and load carriers.
     */
    @JvmOverloads
    fun createDemandLoad(loadName: String? = null): DemandLoad = DemandLoad(loadName)

    // ========================================================================
    // Demand
    // ========================================================================

    /**
     * A demand for a quantity of an [ItemType]. Flows through the supply
     * chain via a state machine; see [DemandState]. Listeners can be
     * attached to react to state transitions.
     *
     * Created only via [SupplyChainModel.createDemand].
     *
     * @see sc.inventorylayer.Demand
     */
    open inner class Demand internal constructor(
        itemType: ItemType,
        amountDemanded: Int,
        demandName: String? = null,
    ) : QObject(demandName) {

        init {
            require(amountDemanded > 0) { "amount demanded must be > 0" }
        }

        /** The item type for this demand. */
        val itemType: ItemType = itemType

        /** The original quantity requested when the demand was created. */
        val originalAmountDemanded: Int = amountDemanded

        /** Quantity not yet filled. Reaches 0 when the demand is filled. */
        var remainingDemand: Int = amountDemanded
            private set

        /** Quantity supplied so far against this demand. */
        var amountFilled: Int = 0
            private set

        /** True once the entire [originalAmountDemanded] has been supplied. */
        var isFilled: Boolean = false
            private set

        /** Rolling total demand seen by the filler when this demand arrived. */
        var totalArrivedDemand: Long = 0
            set(value) {
                require(value >= 0) { "total arrived demand must be >= 0" }
                field = value
            }

        // -- relationship fields ----------------------------------------------

        /** The order this demand is attached to, or null. */
        var order: Order? = null
            internal set

        /** The line index within [order]. */
        var orderIndex: Int = 0
            internal set

        /** True iff this demand is part of an order. */
        val isPartOfAnOrder: Boolean get() = order != null

        /** The filler that this demand was committed to. */
        var filler: DemandFillerIfc? = null
            internal set

        /** The filler that actually received the demand. */
        var receiver: DemandFillerIfc? = null
            internal set

        /**
         * The sender of this demand. Named [demandSender] (not `sender`) to
         * avoid hiding [QObject.sender], which is the station-layer
         * [ksl.modeling.station.QObjectSenderIfc].
         */
        var demandSender: DemandSenderIfc? = null
            internal set

        /**
         * The original demand this one was forwarded from, if any. Set
         * by [ksl.modeling.supplychain.flow.DemandForwarder] and
         * routing nodes (cross-docks) that forward a request upstream:
         * the forwarder sends a forwarded request upstream while
         * parking the original in IN_PROCESS; when the forwarded
         * request returns delivered, the receiving endpoint reads this
         * property to recover the typed original and fill it.
         *
         * Typed alternative to stashing the original on
         * [QObject.attachedObject], which is `Any?` and required a
         * runtime cast at every receive site.
         */
        var forwardedFrom: Demand? = null
            internal set

        // -- status -----------------------------------------------------------

        /** Outcome status. See [DemandStatusCode]. */
        var status: DemandStatusCode = DemandStatusCode.NoStatus
            internal set

        // -- flags ------------------------------------------------------------

        /** Whether this demand can be backlogged when not immediately filled. */
        var allowBackLogging: Boolean = true
            internal set

        /** Whether partial filling is permitted. */
        var allowPartialFilling: Boolean = true
            internal set

        /** Whether the demand can be cancelled after receipt. */
        var allowCancelling: Boolean = false
            internal set

        // -- timestamps (NaN until set) ---------------------------------------

        var timeReceived: Double = Double.NaN
            private set
        var timeEnteredInProcess: Double = Double.NaN
            private set
        var timeEnteredBackLog: Double = Double.NaN
            private set
        var timeFillingEnded: Double = Double.NaN
            private set
        var timeShipped: Double = Double.NaN
            private set
        var timeDelivered: Double = Double.NaN
            private set
        var timeStored: Double = Double.NaN
            private set

        val fillingTime: Double get() = timeFillingEnded - timeEnteredInProcess
        val loadBuildingTime: Double get() = timeShipped - timeFillingEnded
        val transportTime: Double get() = timeDelivered - timeShipped
        val totalLeadTime: Double get() = timeDelivered - timeEnteredInProcess

        // -- weight / cube derived from itemType ------------------------------

        val weight: Double get() = itemType.weight * amountFilled
        val cube: Double get() = itemType.cube * amountFilled

        // -- state machine ----------------------------------------------------

        /** Current state. Starts at [inPreparation]. */
        var demandState: DemandState = inPreparation
            private set

        /** Most recent prior state, or null on creation. */
        var previousDemandState: DemandState? = null
            private set

        /** True iff [demandState] === [state]. */
        fun isState(state: DemandState): Boolean = demandState === state

        /** True iff [previousDemandState] === [state]. */
        fun isPreviousState(state: DemandState): Boolean =
            previousDemandState === state

        private val stateChangeListeners = mutableListOf<DemandStateChangeListener>()

        /** Register a listener. */
        fun addStateChangeListener(listener: DemandStateChangeListener) {
            stateChangeListeners += listener
        }

        /** Unregister a listener. */
        fun removeStateChangeListener(listener: DemandStateChangeListener) {
            stateChangeListeners -= listener
        }

        /**
         * Register a listener that fires exactly once — on the first
         * transition to [targetState] — and then removes itself. The
         * action runs inside a `try`/`finally` so the listener is
         * unregistered even if [action] throws.
         *
         * If [targetState] is never reached, the listener simply
         * lingers until the demand is collected at end of replication
         * (same lifecycle as any other attached listener).
         *
         * @return the underlying listener instance, so callers may
         *         remove it early if needed.
         */
        fun addOneShotListener(
            targetState: DemandStateId,
            action: (Demand) -> Unit,
        ): DemandStateChangeListener {
            lateinit var self: DemandStateChangeListener
            self = DemandStateChangeListener { d, _, to ->
                if (to.stateId === targetState) {
                    try { action(d) } finally { d.removeStateChangeListener(self) }
                }
            }
            addStateChangeListener(self)
            return self
        }

        /**
         * Called by [DemandState] subclasses to perform a transition.
         * Listener-firing follows the gating rules of the legacy Java
         * `Demand.setDemandState`:
         *   - `inPreparation`, `negotiating`, `sent` are silent (pre-active).
         *   - `backLogged` notifies the parent order only.
         *   - `received` / `rejected` fire demand listeners only when not in
         *     an order; otherwise the order is notified and replays the
         *     listener fire when its batch completes.
         *   - all other states fire demand listeners unconditionally.
         */
        internal fun transitionTo(newState: DemandState) {
            val old = demandState
            previousDemandState = old
            demandState = newState
            when (newState) {
                inPreparation, negotiating, sent -> { /* silent */ }
                backLogged -> order?.demandBackLogged(this)
                received -> {
                    val o = order
                    if (o == null) fireStateChangeListeners(old, newState)
                    else o.demandReceived(this)
                }
                rejected -> {
                    val o = order
                    if (o == null) fireStateChangeListeners(old, newState)
                    else o.demandRejected(this)
                }
                inProcess, filled, shipped, stored, cancelled ->
                    fireStateChangeListeners(old, newState)
                delivered -> {
                    // Fire user listeners first so they see Delivered
                    // before any cascade to Stored.  Then dispatch to
                    // the destination's delivery endpoint to advance
                    // the demand (typically to Stored).
                    fireStateChangeListeners(old, newState)
                    dispatchDeliveredEndpoint(this)
                }
                else -> error("unhandled state $newState")
            }
        }

        /**
         * Fires registered state-change listeners with the given `from`/`to`.
         * Called directly from [transitionTo], and also by [Order] to replay
         * the receipt/rejection events once it has finished batching.
         *
         * Iterates over a snapshot — listeners that synchronously trigger
         * further state transitions (e.g., a `received` listener calling
         * `filler.fillDemand(d)` which attaches a filled-listener) would
         * otherwise raise `ConcurrentModificationException`.
         */
        internal fun fireStateChangeListeners(
            from: DemandState?,
            to: DemandState,
        ) {
            for (l in stateChangeListeners.toList()) {
                l.onDemandStateChange(this, from, to)
            }
        }

        // -- transition entry points (delegate to current state) --------------

        fun prepare() = demandState.prepare(this)
        fun negotiate() = demandState.negotiate(this)
        fun sent() = demandState.sent(this)
        fun receive(receiver: DemandFillerIfc) {
            this.receiver = receiver
            demandState.receive(this)
        }
        fun process(filler: DemandFillerIfc) {
            check(receiver === filler) {
                "demand was received by a different filler"
            }
            demandState.process(this)
        }
        fun fill(amountSupplied: Int) = demandState.fill(this, amountSupplied)
        fun backlog() = demandState.backlog(this)
        fun reject() = demandState.reject(this)
        fun cancel() = demandState.cancel(this)
        fun ship() = demandState.ship(this)
        fun deliver() = demandState.deliver(this)
        fun store() = demandState.store(this)

        // -- mutators usable only from certain states -------------------------

        fun setFiller(filler: DemandFillerIfc) =
            demandState.setFiller(this, filler)
        fun setDemandSender(sender: DemandSenderIfc) =
            demandState.setSender(this, sender)
        fun setStatus(status: DemandStatusCode) =
            demandState.setStatus(this, status)
        fun setAllowBackLogging(flag: Boolean) =
            demandState.setAllowBackLogging(this, flag)
        fun setAllowPartialFilling(flag: Boolean) =
            demandState.setAllowPartialFilling(this, flag)
        fun setAllowCancelling(flag: Boolean) =
            demandState.setAllowCancelling(this, flag)

        // -- internal callbacks invoked from the state machine ---------------

        internal fun recordReceived() { timeReceived = time }
        internal fun recordEnteredInProcess() {
            check(time == timeReceived) {
                "a time delay occurred between receipt and entering process"
            }
            timeEnteredInProcess = time
        }
        internal fun recordEnteredBackLog() { timeEnteredBackLog = time }
        internal fun recordShipped()   { timeShipped = time }
        internal fun recordDelivered() { timeDelivered = time }
        internal fun recordStored()    { timeStored = time }

        internal fun applyFill(amountSupplied: Int) {
            require(amountSupplied >= 0) { "amount supplied must be >= 0" }
            if (!allowPartialFilling) {
                require(amountSupplied == originalAmountDemanded) {
                    "partial filling not allowed; supplied != originally demanded"
                }
            }
            val amtUsed = minOf(amountSupplied, remainingDemand)
            remainingDemand -= amtUsed
            amountFilled += amtUsed
            if (amountFilled == originalAmountDemanded) {
                timeFillingEnded = time
                isFilled = true
                transitionTo(filled)
            }
        }

        override fun toString(): String = buildString {
            append("Demand[id=").append(id)
            append(", item=").append(itemType.name)
            append(", requested=").append(originalAmountDemanded)
            append(", remaining=").append(remainingDemand)
            append(", filled=").append(amountFilled)
            append(", state=").append(demandState.stateName)
            append("]")
        }
    }

    // ========================================================================
    // DemandState — sealed-style hierarchy as inner classes
    // ========================================================================

    /**
     * State in the demand lifecycle. State subclasses encapsulate the
     * legal transitions; an attempted illegal transition throws
     * [IllegalStateException] via [unsupported].
     *
     * @see sc.inventorylayer.DemandState
     */
    abstract inner class DemandState internal constructor(val stateId: DemandStateId) {

        /** Back-compat alias for the state's display name. */
        val stateName: String get() = stateId.displayName

        internal open fun prepare(d: Demand): Unit = unsupported("prepare")
        internal open fun negotiate(d: Demand): Unit = unsupported("negotiate")
        internal open fun sent(d: Demand): Unit = unsupported("sent")
        internal open fun receive(d: Demand): Unit = unsupported("receive")
        internal open fun process(d: Demand): Unit = unsupported("process")
        internal open fun reject(d: Demand): Unit = unsupported("reject")
        internal open fun fill(d: Demand, amountSupplied: Int): Unit =
            unsupported("fill")
        internal open fun backlog(d: Demand): Unit = unsupported("backlog")
        internal open fun cancel(d: Demand): Unit = unsupported("cancel")
        internal open fun ship(d: Demand): Unit = unsupported("ship")
        internal open fun deliver(d: Demand): Unit = unsupported("deliver")
        internal open fun store(d: Demand): Unit = unsupported("store")

        internal open fun setFiller(d: Demand, filler: DemandFillerIfc): Unit =
            unsupported("setFiller")
        internal open fun setSender(d: Demand, sender: DemandSenderIfc): Unit =
            unsupported("setSender")
        internal open fun setStatus(d: Demand, status: DemandStatusCode): Unit =
            unsupported("setStatus")
        internal open fun setAllowBackLogging(d: Demand, flag: Boolean): Unit =
            unsupported("setAllowBackLogging")
        internal open fun setAllowPartialFilling(d: Demand, flag: Boolean): Unit =
            unsupported("setAllowPartialFilling")
        internal open fun setAllowCancelling(d: Demand, flag: Boolean): Unit =
            unsupported("setAllowCancelling")

        protected fun unsupported(op: String): Nothing =
            error("$op not allowed from $stateName")

        override fun toString(): String = stateName
    }

    /** Demand is being prepared to be sent or negotiated. */
    val inPreparation: DemandState = InPreparation()
    private inner class InPreparation : DemandState(DemandStateId.InPreparation) {
        override fun setStatus(d: Demand, status: DemandStatusCode) {
            d.status = status
        }
        override fun setSender(d: Demand, sender: DemandSenderIfc) {
            d.demandSender = sender
        }
        override fun setFiller(d: Demand, filler: DemandFillerIfc) {
            d.filler = filler
        }
        override fun setAllowPartialFilling(d: Demand, flag: Boolean) {
            d.allowPartialFilling = flag
        }
        override fun setAllowCancelling(d: Demand, flag: Boolean) {
            d.allowCancelling = flag
        }
        override fun setAllowBackLogging(d: Demand, flag: Boolean) {
            d.allowBackLogging = flag
        }
        override fun negotiate(d: Demand) { d.transitionTo(negotiating) }
        override fun sent(d: Demand)      { d.transitionTo(sent) }
    }

    /** Demand has been sent to a filler. */
    val sent: DemandState = Sent()
    private inner class Sent : DemandState(DemandStateId.Sent) {
        override fun setStatus(d: Demand, status: DemandStatusCode) {
            d.status = status
        }
        override fun setFiller(d: Demand, filler: DemandFillerIfc) {
            d.filler = filler
        }
        override fun receive(d: Demand) {
            d.recordReceived()
            d.transitionTo(received)
        }
        override fun reject(d: Demand) { d.transitionTo(rejected) }
        override fun prepare(d: Demand) { d.transitionTo(inPreparation) }
    }

    /** Demand is being negotiated. */
    val negotiating: DemandState = Negotiating()
    private inner class Negotiating : DemandState(DemandStateId.Negotiating) {
        override fun setStatus(d: Demand, status: DemandStatusCode) {
            d.status = status
        }
        override fun prepare(d: Demand) { d.transitionTo(inPreparation) }
        // Note: Java's DemandStateNegotiating allows only setStatus + prepare.
        // The reject path from negotiation goes through prepare -> sent ->
        // reject in Java; do not add sent/reject overrides here.
    }

    /** Demand is ready to be processed at a filler. */
    val received: DemandState = Received()
    private inner class Received : DemandState(DemandStateId.Received) {
        override fun process(d: Demand) {
            d.recordEnteredInProcess()
            d.transitionTo(inProcess)
        }
        override fun reject(d: Demand) { d.transitionTo(rejected) }
        override fun cancel(d: Demand) {
            check(d.allowCancelling) { "demand is not cancellable" }
            d.transitionTo(cancelled)
        }
    }

    /** Demand is being filled. */
    val inProcess: DemandState = InProcess()
    private inner class InProcess : DemandState(DemandStateId.InProcess) {
        override fun fill(d: Demand, amountSupplied: Int) {
            d.applyFill(amountSupplied)
        }
        override fun backlog(d: Demand) {
            d.recordEnteredBackLog()
            d.transitionTo(backLogged)
        }
        override fun cancel(d: Demand) {
            check(d.allowCancelling) { "demand is not cancellable" }
            d.transitionTo(cancelled)
        }
        // A demand parked in IN_PROCESS (e.g. an original parked at an
        // InventoryCrossDock while its forwarded request travels upstream)
        // may fail: if the upstream rejects the forwarded request, the
        // parked original must be able to reject too, otherwise it
        // strands in IN_PROCESS forever (audit finding A).  setStatus is
        // allowed so the failure cause can be propagated before rejecting.
        override fun setStatus(d: Demand, status: DemandStatusCode) {
            d.status = status
        }
        override fun reject(d: Demand) { d.transitionTo(rejected) }
    }

    /** Demand is in the backlog awaiting future supply. */
    val backLogged: DemandState = BackLogged()
    private inner class BackLogged : DemandState(DemandStateId.BackLogged) {
        override fun fill(d: Demand, amountSupplied: Int) {
            d.applyFill(amountSupplied)
        }
    }

    /** Demand has been rejected. */
    val rejected: DemandState = Rejected()
    private inner class Rejected : DemandState(DemandStateId.Rejected) {
        override fun prepare(d: Demand) { d.transitionTo(inPreparation) }
    }

    /** Demand was cancelled. */
    val cancelled: DemandState = Cancelled()
    private inner class Cancelled : DemandState(DemandStateId.Cancelled)

    /** Demand has been filled. */
    val filled: DemandState = Filled()
    private inner class Filled : DemandState(DemandStateId.Filled) {
        override fun ship(d: Demand) {
            d.recordShipped()
            d.transitionTo(shipped)
        }
    }

    /** Demand has been shipped. */
    val shipped: DemandState = Shipped()
    private inner class Shipped : DemandState(DemandStateId.Shipped) {
        override fun deliver(d: Demand) {
            d.recordDelivered()
            d.transitionTo(delivered)
        }
    }

    /**
     * Demand has been delivered.  Not terminal: the destination's
     * delivery endpoint then transitions either to [stored] (the
     * normal storing case) or back to [shipped] (multi-hop pass-
     * through, e.g. a cross-dock re-shipping toward the next hop).
     *
     * See `docs/supply-chain-framework-design.md` §3.1.
     */
    val delivered: DemandState = Delivered()
    private inner class Delivered : DemandState(DemandStateId.Delivered) {
        override fun store(d: Demand) {
            d.recordStored()
            d.transitionTo(stored)
        }
        override fun ship(d: Demand) {
            // Re-ship for the next leg of a multi-hop delivery.
            // Overwrites timeShipped — last-leg semantics for v1.
            d.recordShipped()
            d.transitionTo(shipped)
        }
    }

    /**
     * Demand has been integrated into the destination's inventory
     * (or otherwise finalised at the destination).  Terminal state.
     * Accounting listeners (e.g. an [Inventory]'s replenishment
     * handler) fire on this transition rather than on [delivered].
     *
     * See `docs/supply-chain-framework-design.md` §3.1.
     */
    val stored: DemandState = Stored()
    private inner class Stored : DemandState(DemandStateId.Stored)

    // ========================================================================
    // Order
    // ========================================================================

    /**
     * A replenishment order containing one or more [Demand]s for distinct
     * [ItemType]s. Like [Demand], an order moves through a state machine
     * (see [OrderState]).
     *
     * Created only via [SupplyChainModel.createOrder].
     *
     * @see sc.inventorylayer.Order
     */
    open inner class Order internal constructor(
        orderName: String? = null,
        allowBackLogging: Boolean = true,
        allowPartialShipping: Boolean = false,
        allowCancelling: Boolean = false,
    ) : QObject(orderName) {

        // -- flags ------------------------------------------------------------

        var allowBackLogging: Boolean = allowBackLogging
            internal set
        var allowPartialShipping: Boolean = allowPartialShipping
            internal set
        var allowCancelling: Boolean = allowCancelling
            internal set

        // -- relationships ---------------------------------------------------

        var orderSender: OrderSenderIfc? = null
            internal set
        var filler: OrderFillerIfc? = null
            internal set

        // -- status ----------------------------------------------------------

        var status: OrderStatusCode = OrderStatusCode.NoStatus
            internal set

        // -- demand collection -----------------------------------------------

        private val myDemands: MutableList<Demand> = mutableListOf()
        // Preserve insertion order to honor porting-plan §4.3 (no
        // HashSet iteration-order reliance). Java used HashSet here.
        private val myItemTypes: MutableSet<ItemType> = linkedSetOf()

        /** Read-only view of the demands on this order, in insertion order. */
        val demands: List<Demand> get() = myDemands

        val numberOfDemands: Int get() = myDemands.size

        val isEmpty: Boolean get() = myDemands.isEmpty()

        fun getDemand(index: Int): Demand = myDemands[index]

        // -- fill tracking ---------------------------------------------------

        var numFilledDemands: Int = 0
            private set

        var isFilled: Boolean = false
            private set

        var requiredWeight: Double = 0.0
            private set

        var requiredCube: Double = 0.0
            private set

        // -- received/rejected batching counters -----------------------------

        private var rejectionFlag: Boolean = false
        private var numReceived: Int = 0
        private var numRejected: Int = 0
        private var numIterated: Int = 0

        // -- state machine ---------------------------------------------------

        var orderState: OrderState = orderCreated
            private set

        fun isState(state: OrderState): Boolean = orderState === state

        private val stateChangeListeners =
            mutableListOf<OrderStateChangeListener>()

        fun addStateChangeListener(listener: OrderStateChangeListener) {
            stateChangeListeners += listener
        }

        fun removeStateChangeListener(listener: OrderStateChangeListener) {
            stateChangeListeners -= listener
        }

        /**
         * Register a listener that fires exactly once — on the first
         * transition to [targetState] — and then removes itself.
         * Mirrors [Demand.addOneShotListener].
         *
         * @return the underlying listener instance.
         */
        fun addOneShotListener(
            targetState: OrderStateId,
            action: (Order) -> Unit,
        ): OrderStateChangeListener {
            lateinit var self: OrderStateChangeListener
            self = OrderStateChangeListener { o, _, to ->
                if (to.stateId === targetState) {
                    try { action(o) } finally { o.removeStateChangeListener(self) }
                }
            }
            addStateChangeListener(self)
            return self
        }

        // -- transition entry points -----------------------------------------

        fun sent()      = orderState.sent(this)
        fun receive()   = orderState.receive(this)
        fun process()   = orderState.process(this)
        fun reject()    = orderState.reject(this)
        fun fill()      = orderState.fill(this)
        fun backLog()   = orderState.backlog(this)
        fun ship()      = orderState.ship(this)
        fun deliver()   = orderState.deliver(this)
        fun negotiate() = orderState.negotiate(this)
        fun prepare()   = orderState.prepare(this)

        fun addDemand(demand: Demand) {
            require(demand.allowBackLogging == allowBackLogging) {
                "demand backlogging incompatible with order backlogging"
            }
            require(demand.itemType !in myItemTypes) {
                "item type already on order"
            }
            require(demand !in myDemands) { "demand already on order" }
            orderState.addDemand(this, demand)
        }

        fun removeDemand(demand: Demand) {
            require(demand.order === this) {
                "demand is not part of this order"
            }
            orderState.removeDemand(this, demand)
        }

        fun setFiller(filler: OrderFillerIfc) =
            orderState.setFiller(this, filler)
        fun setStatus(status: OrderStatusCode) =
            orderState.setStatus(this, status)
        fun setAllowBackLogging(flag: Boolean) =
            orderState.setAllowBackLogging(this, flag)
        fun setAllowPartialShipping(flag: Boolean) =
            orderState.setAllowPartialShipping(this, flag)
        fun setAllowCancelling(flag: Boolean) =
            orderState.setAllowCancelling(this, flag)

        fun setDemandStateToShipped() =
            orderState.setDemandStateToShipped(this)

        fun setDemandStateToDelivered() =
            orderState.setDemandStateToDelivered(this)

        // -- mutators internal to the state machine --------------------------

        internal fun transitionTo(newState: OrderState) {
            val old = orderState
            orderState = newState
            // Snapshot to allow listeners to attach/remove listeners
            // safely during iteration (see Demand.fireStateChangeListeners).
            for (l in stateChangeListeners.toList()) {
                l.onOrderStateChange(this, old, newState)
            }
        }

        internal fun addDemand_(demand: Demand) {
            require(!demand.isFilled) {
                "cannot add a filled demand to an order"
            }
            myDemands += demand
            demand.order = this
            demand.orderIndex = myDemands.lastIndex
            requiredWeight += demand.weight
            requiredCube += demand.cube
            myItemTypes += demand.itemType
            demand.addStateChangeListener(orderDemandFillTracker)
        }

        internal fun removeDemand_(demand: Demand) {
            myDemands -= demand
            demand.order = null
            demand.orderIndex = -1
            requiredWeight -= demand.weight
            // NOTE: legacy Java had a typo here (`+` instead of `-`); we
            // fix it. The Java line: `myRequiredCube = myRequiredCube +
            // demand.getCube();`
            requiredCube -= demand.cube
            myItemTypes -= demand.itemType
            demand.removeStateChangeListener(orderDemandFillTracker)
        }

        internal fun demandReceived(demand: Demand) {
            numReceived++
            numIterated++
            if (numIterated == myDemands.size) {
                if (rejectionFlag) {
                    status = OrderStatusCode.DemandRejected
                    reject()
                } else {
                    receive()
                }
            }
        }

        internal fun demandRejected(demand: Demand) {
            rejectionFlag = true
            numRejected++
            numIterated++
            if (numIterated == myDemands.size) {
                status = OrderStatusCode.DemandRejected
                reject()
            }
        }

        internal fun demandBackLogged(demand: Demand) {
            if (!isState(orderBackLogged)) backLog()
        }

        /**
         * Reset batched-receipt counters and ask each demand's filler to
         * receive it. Called by [OrderFillerAbstract.askOrderToBeginReceivingDemands].
         */
        internal fun beginReceivingDemands_() {
            rejectionFlag = false
            numIterated = 0
            numReceived = 0
            numRejected = 0
            for (d in myDemands) {
                val f = d.filler ?: error("demand $d has no assigned filler")
                f.receive(d)
            }
        }

        /**
         * Reset the filled-demand counter and ask each demand's filler to
         * fill it. Called by [OrderFillerAbstract.askOrderToBeginProcessingDemands].
         */
        internal fun beginProcessingDemands_() {
            numFilledDemands = 0
            for (d in myDemands) {
                val f = d.filler ?: error("demand $d has no assigned filler")
                f.fillDemand(d)
            }
        }

        /**
         * Replays the demand-level state-change listeners for the batched
         * receipt event. Matches Java's `notifyDemandListenersOfOrderReceipt`.
         */
        internal fun notifyDemandListenersOfOrderReceipt() {
            for (d in myDemands) {
                d.fireStateChangeListeners(d.previousDemandState, d.demandState)
            }
        }

        internal fun notifyDemandListenersOfOrderRejection() {
            for (d in myDemands) {
                d.fireStateChangeListeners(d.previousDemandState, d.demandState)
            }
        }

        internal fun setDemandStateToShipped_() {
            for (d in myDemands) d.ship()
        }

        internal fun setDemandStateToDelivered_() {
            for (d in myDemands) d.deliver()
        }

        internal fun placeDemandsInSentState() {
            for (d in myDemands) d.sent()
        }

        internal fun markFilled() {
            isFilled = true
        }

        // -- the demand-fill listener ----------------------------------------

        /**
         * Attached to every demand on this order. Increments
         * [numFilledDemands] when a demand reaches [filled] and transitions
         * the order to [orderFilled] when all demands are complete.
         * Matches Java's `Order.DemandListener.demandFilled`.
         */
        private val orderDemandFillTracker =
            DemandStateChangeListener { _, _, to ->
                if (to === filled) {
                    numFilledDemands++
                    if (numFilledDemands == myDemands.size) {
                        fill()
                    }
                }
            }

        /**
         * Auto-attached at construction: when the order ships, cascade
         * shipment to each demand. Matches Java's
         * `myShippedListener = myDemandListener` default wiring.
         */
        init {
            addStateChangeListener { _, _, to ->
                if (to === orderShipped) setDemandStateToShipped()
            }
        }

        override fun toString(): String = buildString {
            append("Order[id=").append(id)
            append(", name=").append(name)
            append(", state=").append(orderState.stateName)
            append(", demands=").append(myDemands.size)
            append("]")
        }
    }

    // ========================================================================
    // OrderState — sealed-style hierarchy as inner classes
    // ========================================================================

    /**
     * State in the order lifecycle. State subclasses encapsulate the legal
     * transitions; an attempted illegal transition throws
     * [IllegalStateException] via [unsupported].
     *
     * @see sc.inventorylayer.OrderState
     */
    abstract inner class OrderState internal constructor(val stateId: OrderStateId) {

        /** Back-compat alias for the state's display name. */
        val stateName: String get() = stateId.displayName


        internal open fun sent(o: Order): Unit       = unsupported("sent")
        internal open fun receive(o: Order): Unit    = unsupported("receive")
        internal open fun prepare(o: Order): Unit    = unsupported("prepare")
        internal open fun process(o: Order): Unit    = unsupported("process")
        internal open fun negotiate(o: Order): Unit  = unsupported("negotiate")
        internal open fun reject(o: Order): Unit     = unsupported("reject")
        internal open fun cancel(o: Order): Unit     = unsupported("cancel")
        internal open fun ship(o: Order): Unit       = unsupported("ship")
        internal open fun deliver(o: Order): Unit    = unsupported("deliver")
        internal open fun backlog(o: Order): Unit    = unsupported("backlog")
        internal open fun fill(o: Order): Unit       = unsupported("fill")

        internal open fun addDemand(o: Order, d: Demand): Unit =
            unsupported("addDemand")
        internal open fun removeDemand(o: Order, d: Demand): Unit =
            unsupported("removeDemand")

        internal open fun setFiller(o: Order, filler: OrderFillerIfc): Unit =
            unsupported("setFiller")
        internal open fun setStatus(o: Order, status: OrderStatusCode): Unit =
            unsupported("setStatus")
        internal open fun setAllowBackLogging(o: Order, flag: Boolean): Unit =
            unsupported("setAllowBackLogging")
        internal open fun setAllowPartialShipping(o: Order, flag: Boolean): Unit =
            unsupported("setAllowPartialShipping")
        internal open fun setAllowCancelling(o: Order, flag: Boolean): Unit =
            unsupported("setAllowCancelling")
        internal open fun setDemandStateToShipped(o: Order): Unit =
            unsupported("setDemandStateToShipped")
        internal open fun setDemandStateToDelivered(o: Order): Unit =
            unsupported("setDemandStateToDelivered")

        protected fun unsupported(op: String): Nothing =
            error("$op not allowed from $stateName")

        override fun toString(): String = stateName
    }

    /** Just created, no demands yet. */
    val orderCreated: OrderState = OrderCreated()
    private inner class OrderCreated : OrderState(OrderStateId.Created) {
        override fun setAllowPartialShipping(o: Order, flag: Boolean) {
            o.allowPartialShipping = flag
        }
        override fun setAllowBackLogging(o: Order, flag: Boolean) {
            o.allowBackLogging = flag
        }
        override fun setAllowCancelling(o: Order, flag: Boolean) {
            o.allowCancelling = flag
        }
        override fun addDemand(o: Order, d: Demand) {
            o.addDemand_(d)
            o.transitionTo(orderInPreparation)
        }
    }

    /** Has at least one demand; not yet sent. */
    val orderInPreparation: OrderState = OrderInPreparation()
    private inner class OrderInPreparation : OrderState(OrderStateId.InPreparation) {
        override fun setFiller(o: Order, filler: OrderFillerIfc) {
            o.filler = filler
        }
        override fun setStatus(o: Order, status: OrderStatusCode) {
            o.status = status
        }
        override fun addDemand(o: Order, d: Demand) { o.addDemand_(d) }
        override fun removeDemand(o: Order, d: Demand) { o.removeDemand_(d) }
        override fun negotiate(o: Order) { o.transitionTo(orderNegotiating) }
        override fun sent(o: Order) {
            o.transitionTo(orderSent)
            o.placeDemandsInSentState()
        }
    }

    val orderSent: OrderState = OrderSent()
    private inner class OrderSent : OrderState(OrderStateId.Sent) {
        override fun setStatus(o: Order, status: OrderStatusCode) {
            o.status = status
        }
        override fun receive(o: Order) {
            o.notifyDemandListenersOfOrderReceipt()
            o.transitionTo(orderReceived)
        }
        override fun reject(o: Order) {
            o.notifyDemandListenersOfOrderRejection()
            o.transitionTo(orderRejected)
        }
        override fun prepare(o: Order) { o.transitionTo(orderInPreparation) }
    }

    val orderNegotiating: OrderState = OrderNegotiating()
    private inner class OrderNegotiating : OrderState(OrderStateId.Negotiating)
    // Java OrderStateNegotiating has no overrides; base default rules apply.

    val orderReceived: OrderState = OrderReceived()
    private inner class OrderReceived : OrderState(OrderStateId.Received) {
        override fun cancel(o: Order) {
            check(o.allowCancelling) { "cancelling not allowed" }
            o.transitionTo(orderCancelled)
        }
        override fun process(o: Order) { o.transitionTo(orderInProcess) }
    }

    val orderInProcess: OrderState = OrderInProcess()
    private inner class OrderInProcess : OrderState(OrderStateId.InProcess) {
        override fun backlog(o: Order) {
            check(o.allowBackLogging) { "backlogging not allowed" }
            o.transitionTo(orderBackLogged)
        }
        override fun fill(o: Order) {
            o.markFilled()
            o.transitionTo(orderFilled)
        }
    }

    val orderBackLogged: OrderState = OrderBackLogged()
    private inner class OrderBackLogged : OrderState(OrderStateId.BackLogged) {
        override fun fill(o: Order) {
            o.markFilled()
            o.transitionTo(orderFilled)
        }
    }

    val orderRejected: OrderState = OrderRejected()
    private inner class OrderRejected : OrderState(OrderStateId.Rejected) {
        override fun prepare(o: Order) { o.transitionTo(orderInPreparation) }
    }

    val orderCancelled: OrderState = OrderCancelled()
    private inner class OrderCancelled : OrderState(OrderStateId.Cancelled)

    val orderFilled: OrderState = OrderFilled()
    private inner class OrderFilled : OrderState(OrderStateId.Filled) {
        override fun removeDemand(o: Order, d: Demand) {
            check(o.allowPartialShipping) {
                "removing demands not allowed without partial shipping"
            }
            o.removeDemand_(d)
        }
        override fun ship(o: Order) { o.transitionTo(orderShipped) }
    }

    val orderShipped: OrderState = OrderShipped()
    private inner class OrderShipped : OrderState(OrderStateId.Shipped) {
        override fun deliver(o: Order) { o.transitionTo(orderDelivered) }
        override fun setDemandStateToShipped(o: Order) {
            o.setDemandStateToShipped_()
        }
    }

    val orderDelivered: OrderState = OrderDelivered()
    private inner class OrderDelivered : OrderState(OrderStateId.Delivered) {
        override fun setDemandStateToDelivered(o: Order) {
            o.setDemandStateToDelivered_()
        }
    }

    // ========================================================================
    // DemandLoad — a collection of demands traveling together to one destination
    // ========================================================================

    /**
     * A bundle of demands all destined for the same [DemandSenderIfc].
     * Used by transportlayer load-builders and load carriers. Created
     * only via [SupplyChainModel.createDemandLoad].
     *
     * @see sc.transportlayer.DemandLoad
     */
    open inner class DemandLoad internal constructor(
        loadName: String? = null,
    ) : QObject(loadName) {

        // LinkedHashSet preserves insertion order (porting plan §4.3 —
        // Java used HashSet).
        private val myDemands: MutableSet<Demand> = linkedSetOf()

        private var myDestination: DemandSenderIfc? = null

        var weight: Double = 0.0
            private set

        var cube: Double = 0.0
            private set

        /** Read-only view of the demands on this load, in insertion order. */
        val demands: Set<Demand> get() = myDemands

        /** The shared destination (the sender of the demands), or null if empty. */
        val destination: DemandSenderIfc? get() = myDestination

        /**
         * Add [demand] to this load. The demand must have a non-null
         * [SupplyChainModel.Demand.demandSender]; all demands on the
         * same load must share the same sender.
         */
        fun addDemand(demand: Demand) {
            require(demand !in myDemands) { "demand is already on this load" }
            val dest = demand.demandSender
                ?: error("demand sender is null; cannot add to load")
            val current = myDestination
            if (current == null) {
                myDestination = dest
            } else {
                require(dest === current) {
                    "demand destination differs from load destination"
                }
            }
            weight += demand.weight
            cube += demand.cube
            myDemands += demand
        }

        /** Number of demands on this load. */
        val size: Int get() = myDemands.size

        /** True iff no demands have been added. */
        val isEmpty: Boolean get() = myDemands.isEmpty()

        /** Transitions every demand on the load to SHIPPED. */
        fun ship() {
            for (d in myDemands) d.ship()
        }

        /** Transitions every demand on the load to DELIVERED. */
        fun deliver() {
            for (d in myDemands) d.deliver()
        }
    }
}
