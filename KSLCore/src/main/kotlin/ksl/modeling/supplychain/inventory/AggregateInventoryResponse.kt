package ksl.modeling.supplychain.inventory

import ksl.modeling.supplychain.*

import ksl.modeling.variable.AggregateCounter
import ksl.modeling.variable.AggregateResponse
import ksl.modeling.variable.AggregateTWResponse
import ksl.simulation.ModelElement

/**
 * Default implementation of [AggregateInventoryResponseIfc]. Each
 * field is a KSL aggregate or response parented to the supplied
 * [parent] — the wrapper itself is not a [ModelElement].
 *
 * All seven aggregates are now chained from their per-inventory
 * sources via the standard `observe()` pattern, including the
 * two per-observation responses (first-fill rate, customer wait
 * time) that previously had no chained aggregator and stood as
 * always-zero stubs. The per-observation responses chain via the
 * new [AggregateResponse] in `ksl.modeling.variable`.
 *
 * @param parent the parent model element under which the underlying
 *        aggregate/response children are constructed
 * @param baseName name prefix for the underlying responses; defaults
 *        to [parent]'s name
 *
 * @see sc.inventorylayer.AggregateInventoryResponse
 */
class AggregateInventoryResponse @JvmOverloads constructor(
    parent: ModelElement,
    baseName: String = parent.name,
) : AggregateInventoryResponseIfc {

    override val aggregateOnHandInventory: AggregateTWResponse =
        AggregateTWResponse(parent, "$baseName : On Hand")

    override val aggregateAmountOnOrder: AggregateTWResponse =
        AggregateTWResponse(parent, "$baseName : On Order")

    override val aggregateAmountBackOrdered: AggregateTWResponse =
        AggregateTWResponse(parent, "$baseName : Amt Backordered")

    override val aggregateNumberBackOrdered: AggregateTWResponse =
        AggregateTWResponse(parent, "$baseName : # Backorders")

    override val aggregateAvgFirstFillRate: AggregateResponse =
        AggregateResponse(parent, "$baseName : First Fill Rate")

    override val aggregateAvgCustomerWaitTime: AggregateResponse =
        AggregateResponse(parent, "$baseName : Backorder Wait Time")

    override val aggregateNumberOfReplenishmentDemands: AggregateCounter =
        AggregateCounter(parent, "$baseName : #Replenishments")

    /**
     * Subscribe **this** aggregate's data to a higher-level aggregate
     * [r] — i.e., make [r] observe (roll up) every line item this
     * aggregate exposes.  Data flows `this → r`.
     *
     * Direction note (regression-fixed): the original implementation
     * had this reversed (`this.observe(r)`), which silently starved
     * the higher-level aggregate of all observations and made
     * network-level inventory aggregates report 0 / NaN.  The
     * convention now matches
     * [ksl.modeling.supplychain.inventory.Inventory.attachAggregateInventoryResponse],
     * which also writes `r.aggregateXxx.observe(myXxx)`.
     */
    override fun subscribeTo(r: AggregateInventoryResponseIfc) {
        // r observes this aggregate.  For TW aggregates this
        // propagates value deltas; for AggregateResponse /
        // AggregateCounter it echoes each observation.
        r.aggregateOnHandInventory.observe(aggregateOnHandInventory)
        r.aggregateAmountOnOrder.observe(aggregateAmountOnOrder)
        r.aggregateAmountBackOrdered.observe(aggregateAmountBackOrdered)
        r.aggregateNumberBackOrdered.observe(aggregateNumberBackOrdered)
        r.aggregateAvgFirstFillRate.observe(aggregateAvgFirstFillRate)
        r.aggregateAvgCustomerWaitTime.observe(aggregateAvgCustomerWaitTime)
        r.aggregateNumberOfReplenishmentDemands.observe(
            aggregateNumberOfReplenishmentDemands
        )
    }

    override fun unsubscribeFrom(r: AggregateInventoryResponseIfc) {
        r.aggregateOnHandInventory.remove(aggregateOnHandInventory)
        r.aggregateAmountOnOrder.remove(aggregateAmountOnOrder)
        r.aggregateAmountBackOrdered.remove(aggregateAmountBackOrdered)
        r.aggregateNumberBackOrdered.remove(aggregateNumberBackOrdered)
        r.aggregateAvgFirstFillRate.remove(aggregateAvgFirstFillRate)
        r.aggregateAvgCustomerWaitTime.remove(aggregateAvgCustomerWaitTime)
        r.aggregateNumberOfReplenishmentDemands.remove(
            aggregateNumberOfReplenishmentDemands
        )
    }
}
