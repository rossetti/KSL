package ksl.modeling.supplychain.inventory

import ksl.modeling.supplychain.*

import ksl.modeling.variable.AggregateCounter
import ksl.modeling.variable.AggregateResponse
import ksl.modeling.variable.AggregateTWResponse

/**
 * Read-only view of an aggregate set of inventory-layer responses.
 * Backing types are KSL aggregates ([AggregateTWResponse],
 * [AggregateResponse], [AggregateCounter]). Every aggregate is now
 * chained from its per-inventory source(s) via the standard
 * `observe()` pattern.
 *
 * See `sc.inventorylayer.AggregateInventoryResponseIfc`
 */
interface AggregateInventoryResponseIfc {
    val aggregateOnHandInventory: AggregateTWResponse
    val aggregateAmountOnOrder: AggregateTWResponse
    val aggregateAmountBackOrdered: AggregateTWResponse
    val aggregateNumberBackOrdered: AggregateTWResponse
    val aggregateAvgFirstFillRate: AggregateResponse
    val aggregateAvgCustomerWaitTime: AggregateResponse
    val aggregateNumberOfReplenishmentDemands: AggregateCounter

    /**
     * Wire this aggregate as a downstream observer of [r]: every
     * observation on [r]'s underlying responses is mirrored onto
     * this aggregate's matching field.
     */
    fun subscribeTo(r: AggregateInventoryResponseIfc)

    /** Undo [subscribeTo]. */
    fun unsubscribeFrom(r: AggregateInventoryResponseIfc)
}

