package ksl.modeling.supplychain.inventory

import ksl.modeling.supplychain.*

import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * Concrete [DemandFillerAbstract] that fills demands after a per-item-type
 * lead-time delay. Once filled, demands are either handed to the inherited
 * [demandCarrier] (if set) or immediately transition through ship and
 * deliver with no delay.
 *
 * Lead-time distributions are registered via [addLeadTime]; each
 * distribution becomes a [RandomVariable] child of this filler.
 *
 * @param parent the parent model element (typically a [SupplyChainModel])
 * @param name optional model-element name
 *
 * @see sc.inventorylayer.LeadTimeDemandFiller
 */
open class LeadTimeDemandFiller @JvmOverloads constructor(
    parent: ModelElement,
    name: String? = null,
) : DemandFillerAbstract(parent, initialAvailability = true, name = name),
    ExternalDemandSupplier {

    /**
     * Lead-time random variables keyed by item type. LinkedHashMap preserves
     * insertion order (porting plan §4.3 — Java used HashMap).
     */
    protected val myLeadTimes: MutableMap<ItemType, RandomVariable> = linkedMapOf()

    /** Per-item-type "number currently in process" time-weighted statistic. */
    protected val myNumInProcessByType: MutableMap<ItemType, TWResponse> = linkedMapOf()

    /** Per-item-type "lead time observed" tally. */
    protected val myLeadTimeByType: MutableMap<ItemType, Response> = linkedMapOf()

    /** Aggregate "number in process" across all item types. */
    protected val myAggregateNumInProcess: TWResponse =
        TWResponse(this, name = "${this.name} : #In Process")

    /** Aggregate "lead time observed" tally. */
    protected val myAggregateLeadTime: Response =
        Response(this, name = "${this.name} : Lead Time")

    protected val leadTimeEventAction: LeadTimeAction = LeadTimeAction()

    /**
     * Register or replace the lead-time distribution for [itemType].
     * Re-registering an existing item type updates the underlying
     * [RandomVariable]'s `initialRandomSource` (preserving statistic
     * identity); a new item type creates fresh [RandomVariable] /
     * [TWResponse] / [Response] children of this filler.
     *
     * The caller is responsible for stream-number assignment when
     * constructing [distribution] (porting plan §4.1).
     */
    fun addLeadTime(itemType: ItemType, distribution: RVariableIfc) {
        val existing = myLeadTimes[itemType]
        if (existing != null) {
            existing.initialRandomSource = distribution
            return
        }
        val rv = RandomVariable(this, distribution,
            name = "${this.name} : ${itemType.name} : LeadTime")
        myLeadTimes[itemType] = rv
        myNumInProcessByType[itemType] = TWResponse(this,
            name = "${this.name} : ${itemType.name} : #In Process")
        myLeadTimeByType[itemType] = Response(this,
            name = "${this.name} : ${itemType.name} : Lead Time")
    }

    /**
     * Remove [itemType] from this filler. Best invoked between replications:
     * if a fill event is in flight for this item type it is not cancelled.
     * Matches Java behavior.
     */
    fun removeLeadTime(itemType: ItemType) {
        val rv = myLeadTimes.remove(itemType) ?: return
        val numInProcess = myNumInProcessByType.remove(itemType)
        val leadTime = myLeadTimeByType.remove(itemType)
        rv.removeFromModel()
        numInProcess?.removeFromModel()
        leadTime?.removeFromModel()
    }

    /** Returns the underlying random source for [itemType], or null. */
    fun getLeadTime(itemType: ItemType): RVariableIfc? =
        myLeadTimes[itemType]?.initialRandomSource

    // ---- DemandFillerIfc surface ----------------------------------------

    override fun receive(demand: SupplyChainModel.Demand) {
        if (!isAvailable) {
            demand.setStatus(DemandStatusCode.FillerUnavailable)
            demand.reject()
            return
        }
        if (!canFillItemType(demand)) {
            demand.setStatus(DemandStatusCode.ItemTypeMismatch)
            demand.reject()
            return
        }
        demand.setStatus(DemandStatusCode.ImmediateFill)
        demand.receive(this)
    }

    override fun fillDemand(demand: SupplyChainModel.Demand) {
        demand.process(this)
        // Auto-ship via the inherited carrier (or zero-delay ship+deliver)
        // when this demand reaches the filled state.
        demand.addStateChangeListener(filledListener)

        val type = demand.itemType
        val rv = myLeadTimes[type]
            ?: error("no lead time configured for $type on $name")
        val leadTime = rv.value

        myAggregateNumInProcess.increment()
        myNumInProcessByType[type]?.increment()

        leadTimeEventAction.schedule(leadTime, message = demand)
    }

    override fun negotiate(demand: SupplyChainModel.Demand): DemandMessageIfc {
        demand.negotiate()
        val canFill = canFillItemType(demand)
        // Java hardcodes ImmediateFill here even when the filler is
        // unavailable; preserved for behavioral parity.
        return DemandMessage(
            demandFiller = this,
            timeStamp = time,
            canFillItemType = canFill,
            requestStatus = DemandStatusCode.ImmediateFill,
            requestFillAmount = demand.remainingDemand,
        )
    }

    override fun canFillItemType(demand: SupplyChainModel.Demand): Boolean =
        demand.itemType in myLeadTimes

    override fun canFillItemType(type: ItemType): Boolean =
        type in myLeadTimes

    override val itemTypes: Collection<ItemType>
        get() = myLeadTimes.keys.toList()

    override fun determineRequestStatus(
        demand: SupplyChainModel.Demand,
    ): DemandStatusCode = when {
        !isAvailable -> DemandStatusCode.FillerUnavailable
        !canFillItemType(demand) -> DemandStatusCode.ItemTypeMismatch
        else -> DemandStatusCode.ImmediateFill
    }

    override fun willReject(demand: SupplyChainModel.Demand): Boolean =
        !isAvailable || !canFillItemType(demand)

    // ---- post-fill shipping --------------------------------------------

    /**
     * Hook for subclasses. Called after [demand] reaches the filled state
     * via this filler's lead-time event. Default behavior is [shipDemand].
     */
    protected open fun demandFilled(demand: SupplyChainModel.Demand) {
        shipDemand(demand)
    }

    /**
     * Hand [demand] to the configured [demandCarrier], or — if no carrier
     * is set — transition directly through ship -> deliver with no delay.
     */
    protected fun shipDemand(demand: SupplyChainModel.Demand) {
        val carrier = demandCarrier
        if (carrier != null) {
            carrier.transportDemand(demand)
        } else {
            demand.ship()
            demand.deliver()
        }
    }

    /**
     * State-change listener registered on each demand by [fillDemand].
     * Compares on [SupplyChainModel.DemandState.stateName] rather than
     * `===` identity so the filler does not need a reference to the
     * enclosing [SupplyChainModel]'s state singletons.
     */
    private val filledListener =
        DemandStateChangeListener { d, _, to ->
            if (to.stateId === DemandStateId.Filled) demandFilled(d)
        }

    // ---- the lead-time event action ------------------------------------

    /**
     * Protected so subclasses (e.g., [LeadTimeOrderFiller.InnerDemandFiller])
     * can call its `schedule` from their own `fillDemand` override.
     */
    protected inner class LeadTimeAction : EventAction<SupplyChainModel.Demand>() {
        override fun action(event: KSLEvent<SupplyChainModel.Demand>) {
            val demand = event.message!!
            val type = demand.itemType
            myAggregateNumInProcess.decrement()
            myNumInProcessByType[type]?.decrement()
            val observedLeadTime = time - demand.timeEnteredInProcess
            myAggregateLeadTime.value = observedLeadTime
            myLeadTimeByType[type]?.value = observedLeadTime
            demand.fill(demand.originalAmountDemanded)
        }
    }
}
