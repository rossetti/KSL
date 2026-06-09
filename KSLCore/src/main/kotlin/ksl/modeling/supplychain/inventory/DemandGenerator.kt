package ksl.modeling.supplychain.inventory

import ksl.modeling.supplychain.*

import ksl.modeling.elements.EventGenerator
import ksl.modeling.variable.RandomVariable
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * Generates demands at scheduled intervals and routes them to a demand
 * filler. The demand's item type is fixed at construction; the amount
 * is sampled from a configurable distribution (defaults to 1). When
 * [unitDemandOnly] is true and the sampled amount is d, the generator
 * sends d separate unit-quantity demands.
 *
 * @param supplyChainModel the supply-chain model whose `createDemand`
 *        factory is used
 * @param itemType the item type of every demand this generator emits
 * @param timeUntilFirstRV time until the first generation event
 * @param timeBtwEventsRV time between subsequent generation events
 * @param maxNumberOfEvents maximum number of events to generate
 * @param timeOfTheLastEvent simulation time at which to stop generating
 * @param name optional model-element name
 *
 * @see sc.inventorylayer.DemandGenerator
 */
open class DemandGenerator @JvmOverloads constructor(
    val supplyChainModel: SupplyChainModel,
    val itemType: ItemType,
    timeUntilFirstRV: RVariableIfc,
    timeBtwEventsRV: RVariableIfc,
    maxNumberOfEvents: Long = Long.MAX_VALUE,
    timeOfTheLastEvent: Double = Double.POSITIVE_INFINITY,
    name: String? = null,
) : EventGenerator(
    supplyChainModel, null, timeUntilFirstRV, timeBtwEventsRV,
    maxNumberOfEvents, timeOfTheLastEvent, name,
), DemandSenderIfc, ExternalDemandConsumer {

    /**
     * If true, a sampled amount of d > 1 produces d separate unit-quantity
     * demands instead of one demand of amount d. Default false.
     */
    var unitDemandOnly: Boolean = false

    /** Whether emitted demands allow backlogging. */
    var permitBackLogging: Boolean = true

    /** Whether emitted demands allow partial filling. */
    var permitPartialFilling: Boolean = true

    private var myAmountRV: RandomVariable? = null

    /**
     * Set the distribution governing the amount of each demand. If never
     * set, every demand is for 1 unit.
     */
    fun setAmountDistribution(distribution: RVariableIfc) {
        if (myAmountRV == null) {
            myAmountRV = RandomVariable(this, distribution,
                name = "${this.name}:AmountRV")
        } else {
            myAmountRV!!.initialRandomSource = distribution
        }
    }

    override var demandFiller: DemandFillerIfc? = null
    override var demandFillerFinder: DemandFillerFinderIfc? = null

    /**
     * Returns true when [type] equals this generator's [itemType].
     * (Java had this inverted — `!=` — and that bug is corrected here.)
     */
    override fun mightRequest(type: ItemType): Boolean = itemType == type

    private val demandListener =
        DemandStateChangeListener { d, _, to ->
            when (to.stateId) {
                // Defensive: a generator's standalone demand should not
                // be on an order, but if it is the order handles receipt.
                DemandStateId.Received -> if (!d.isPartOfAnOrder) demandReceived(d)
                DemandStateId.Rejected -> demandRejected(d)
                DemandStateId.Delivered -> demandDelivered(d)
                else -> {}
            }
        }

    final override fun generate() {
        val amount = generateAmount()
        if (amount <= 0) return
        if (unitDemandOnly) {
            repeat(amount) { emitOne(1) }
        } else {
            emitOne(amount)
        }
    }

    private fun emitOne(amount: Int) {
        val d = createAndConfigureDemand(amount)
        setFillerOnDemand(d)
        d.addStateChangeListener(demandListener)
        sendDemand(d)
    }

    /** Sample the amount distribution. Defaults to 1 when no dist is set. */
    protected open fun generateAmount(): Int =
        myAmountRV?.value?.toInt() ?: 1

    /** Build a demand with this generator's permission flags and sender. */
    protected open fun createAndConfigureDemand(
        amount: Int,
    ): SupplyChainModel.Demand {
        val d = supplyChainModel.createDemand(itemType, amount)
        d.setAllowBackLogging(permitBackLogging)
        d.setAllowPartialFilling(permitPartialFilling)
        d.demandSender = this
        return d
    }

    /** Resolve the demand's filler. Direct override wins over the finder. */
    protected open fun setFillerOnDemand(demand: SupplyChainModel.Demand) {
        val filler = demandFiller
            ?: demandFillerFinder?.findDemandFiller(demand)
            ?: throw NoDemandFillerFoundException(
                "No demand filler found for $demand"
            )
        demand.setFiller(filler)
    }

    /** Send [demand] to its filler. */
    protected open fun sendDemand(demand: SupplyChainModel.Demand) {
        demand.sent()
        demand.filler!!.receive(demand)
    }

    /**
     * Called when a generated standalone demand reaches `RECEIVED`.
     * Defaults to asking the filler to fill the demand.
     */
    protected open fun demandReceived(demand: SupplyChainModel.Demand) {
        demand.filler!!.fillDemand(demand)
    }

    /** Default: throws. Subclasses may override. */
    protected open fun demandRejected(demand: SupplyChainModel.Demand) {
        error("Demand $demand was rejected")
    }

    /** Default: no-op. Subclasses may override. */
    protected open fun demandDelivered(demand: SupplyChainModel.Demand) {
        // override to react
    }
}
