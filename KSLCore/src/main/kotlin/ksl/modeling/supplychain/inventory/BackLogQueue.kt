package ksl.modeling.supplychain.inventory

import ksl.modeling.supplychain.*

import ksl.modeling.queue.Queue
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse

/**
 * FIFO (or other-disciplined) backlog policy backed by a KSL [Queue]
 * of [SupplyChainModel.Demand]. The queue itself is a child model
 * element of this policy.
 *
 * @param inventory the inventory this policy serves
 * @param discipline the queue discipline; defaults to FIFO
 * @param name optional model-element name
 *
 * See `sc.inventorylayer.BackLogQueue`
 */
open class BackLogQueue @JvmOverloads constructor(
    inventory: Inventory,
    discipline: Queue.Discipline = Queue.Discipline.FIFO,
    name: String? = null,
) : BackLogPolicyAbstract(inventory, name) {

    protected val myBackLogQ: Queue<SupplyChainModel.Demand> =
        Queue(this, "${this.name}:DemandBacklogQ", discipline)

    /** Read-only view of the underlying queue. */
    val queue: Queue<SupplyChainModel.Demand> get() = myBackLogQ

    override val numberOfDemandsBackLogged: Int get() = myBackLogQ.size

    override fun backlog(demand: SupplyChainModel.Demand) {
        demand.backlog()
        amtBackLogged.increment(demand.remainingDemand.toDouble())
        myBackLogQ.enqueue(demand)
    }

    override fun fillBackLogs() {
        var stop = false
        while (myBackLogQ.isNotEmpty && !stop) {
            val d = myBackLogQ.peekNext() ?: break
            val canSupply = allocateInventory(d)
            if (canSupply <= 0) {
                stop = true
                continue
            }
            if (canSupply == d.remainingDemand) {
                // full fill — dequeue
                myBackLogQ.removeNext()
            } else {
                // partial fill on this head; stop after applying
                stop = true
            }
            fillBackLog(d, canSupply)
        }
    }

    override fun getBackLogStatistics(): BackLogStatisticsIfc = BackLogStatistics(this)

    /**
     * Chains the queue's `numInQ` time-weighted response to the
     * aggregate's `aggregateNumberBackOrdered`, and the queue's
     * `timeInQ` per-observation response to the aggregate's
     * `aggregateAvgCustomerWaitTime`.
     *
     * The runtime `is` checks downcast from the queue's exposed
     * read-only `TWResponseCIfc` / `ResponseCIfc` views to the concrete
     * `TWResponse` / `Response` types — required to call `observe()`.
     * Safe because KSL `Queue` always uses `TWResponse` / `Response`
     * internally.
     */
    override fun attachAggregateInventoryResponse(r: AggregateInventoryResponseIfc) {
        super.attachAggregateInventoryResponse(r)
        val numInQResponse = myBackLogQ.numInQ
        if (numInQResponse is TWResponse) {
            r.aggregateNumberBackOrdered.observe(numInQResponse)
        }
        val timeInQResponse = myBackLogQ.timeInQ
        if (timeInQResponse is Response) {
            r.aggregateAvgCustomerWaitTime.observe(timeInQResponse)
        }
    }

    override fun detachAggregateInventoryResponse(r: AggregateInventoryResponseIfc) {
        super.detachAggregateInventoryResponse(r)
        val numInQResponse = myBackLogQ.numInQ
        if (numInQResponse is TWResponse) {
            r.aggregateNumberBackOrdered.remove(numInQResponse)
        }
        val timeInQResponse = myBackLogQ.timeInQ
        if (timeInQResponse is Response) {
            r.aggregateAvgCustomerWaitTime.remove(timeInQResponse)
        }
    }
}
