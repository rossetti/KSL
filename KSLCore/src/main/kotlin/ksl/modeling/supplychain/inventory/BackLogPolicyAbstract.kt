package ksl.modeling.supplychain.inventory

import ksl.modeling.supplychain.*

import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.simulation.ModelElement

/**
 * Abstract base for backlog policies that an [Inventory] uses to
 * handle demands which cannot be filled immediately.
 *
 * Subclasses provide a queue/data structure for waiting demands and
 * statistic accessors; [backlog] and [fillBackLogs] are the two
 * primary extension points.
 *
 * See `sc.inventorylayer.BackLogPolicyAbstract`
 */
abstract class BackLogPolicyAbstract @JvmOverloads constructor(
    inventory: Inventory,
    name: String? = null,
) : ModelElement(inventory, name), BackLogInfoIfc {

    protected val amtBackLogged: TWResponse =
        TWResponse(this, name = "${this.name} : Amt BackLogged")

    /** Read-only view of the amount-backlogged response. */
    val amtBackLoggedResponse: TWResponseCIfc get() = amtBackLogged

    /**
     * Time-weighted average backlog over the post-warmup observation
     * window for the most recent replication.  Cost calculators read
     * this in their REPLICATION_ENDED callback to compute the
     * continuous backorder-cost line — saves observers from having
     * to reach through to [amtBackLogged]'s within-replication
     * statistic.  Returns 0.0 if read before the first replication
     * end (no within-replication data yet).
     */
    val avgBacklogInQ: Double
        get() = amtBackLogged.withinReplicationStatistic.weightedAverage

    private var myInventory: Inventory = inventory

    init {
        // Bidirectional setup: Java does this from the constructor body.
        inventory.setBackLogPolicy(this)
    }

    override val amountBackLogged: Int
        get() = amtBackLogged.value.toInt()

    /** Place [demand] into this policy's backlog structure. */
    protected abstract fun backlog(demand: SupplyChainModel.Demand)

    /** Bridge so [Inventory] (not a subclass) can request backlog. */
    internal fun backlogInternal(demand: SupplyChainModel.Demand) = backlog(demand)

    /** Attempt to fill currently-backlogged demands. */
    protected abstract fun fillBackLogs()

    /** Bridge so [Inventory] can request a fill-backlogs sweep. */
    internal fun fillBackLogsInternal() = fillBackLogs()

    /** Subclasses provide the backlog statistic surface. */
    abstract fun getBackLogStatistics(): BackLogStatisticsIfc

    protected val inventory: Inventory get() = myInventory

    protected fun allocateInventory(demand: SupplyChainModel.Demand): Int =
        inventory.allocateInventory(demand)

    protected fun fillBackLog(demand: SupplyChainModel.Demand, amount: Int) {
        amtBackLogged.decrement(amount.toDouble())
        inventory.fillBackLog(demand, amount)
    }

    /**
     * Wire the amount-backlogged response into the upstream aggregate.
     * Subclasses can override to add their queue-level responses.
     */
    open fun attachAggregateInventoryResponse(r: AggregateInventoryResponseIfc) {
        r.aggregateAmountBackOrdered.observe(amtBackLogged)
    }

    open fun detachAggregateInventoryResponse(r: AggregateInventoryResponseIfc) {
        r.aggregateAmountBackOrdered.remove(amtBackLogged)
    }
}
