package ksl.modeling.supplychain.inventory

import ksl.modeling.supplychain.*

import ksl.modeling.variable.RandomVariable
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * Abstract base for demand-arrival listeners that bucket arrivals
 * into time periods of length sampled from [periodLength] and report
 * each period's aggregate via the [reportPeriod] hook.
 *
 * Translates Java's `DemandArrivalListenerAbstract`, which used a
 * JSL `ActionSchedule` of periodic events. KSL's idiom is a recurring
 * [EventAction] that re-schedules itself.
 *
 * @see sc.inventorylayer.DemandArrivalListenerAbstract
 */
abstract class DemandArrivalListenerAbstract @JvmOverloads constructor(
    val inventory: Inventory,
    periodLength: RVariableIfc = ConstantRV(1.0),
    name: String? = null,
) : ModelElement(inventory, name), InventoryDemandArrivalListenerIfc {

    private val periodRV: RandomVariable = RandomVariable(
        this, periodLength, name = "${this.name}:Period",
    )

    /** Number of demand arrivals observed in the current period. */
    protected var periodArrivals: Int = 0
        private set

    /** Total quantity demanded in the current period. */
    protected var periodAmount: Long = 0
        private set

    /** Hook fired at the end of each period with the bucketed totals. */
    protected abstract fun reportPeriod(arrivals: Int, amount: Long)

    override fun demandArrived(
        inventory: Inventory,
        demand: SupplyChainModel.Demand,
    ) {
        periodArrivals++
        periodAmount += demand.originalAmountDemanded
    }

    private val periodEnd = PeriodEndAction()

    override fun initialize() {
        super.initialize()
        periodArrivals = 0
        periodAmount = 0
        periodEnd.schedule(periodRV.value)
    }

    private inner class PeriodEndAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            reportPeriod(periodArrivals, periodAmount)
            periodArrivals = 0
            periodAmount = 0
            schedule(periodRV.value)
        }
    }
}
