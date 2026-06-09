package ksl.modeling.supplychain.cost

import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.ModelElement

/**
 * A cost calculator observes **one source [ModelElement]** and
 * produces one or more line-item Responses populated at the source's
 * `REPLICATION_ENDED` notification.
 *
 * **Implementation pattern** (canonical shape — see Phase 3's
 * concrete calculators):
 *
 * 1. Declare the source as a constructor parameter; read any other
 *    context the calculator needs from the source's public surface
 *    (e.g., `inv.itemType`) — no redundant arguments.
 * 2. Allocate one `Response` per line item the calculator produces,
 *    exposing them via [lineResponses].
 * 3. Declare an inner-class `ksl.observers.ModelElementObserver`
 *    whose `replicationEnded(modelElement)` reads the source's
 *    stabilized within-replication statistics and writes the
 *    line-item values.
 * 4. Call `source.attachModelElementObserver(...)` in the `init`
 *    block.
 *
 * Once attached, the calculator's observer fires automatically at
 * the moment the source's `replicationEnded()` completes — KSL
 * guarantees the source's within-replication statistics are stable
 * (and post-warmup) at that point.  The calculator performs pure
 * multiplication on those observables; the framework does no
 * time-unit conversion and makes no presumption about a rate's time
 * basis (see `docs/supply-chain-cost-redesign.md` §2 "Note on
 * warmup handling" and §1 "Goals and non-goals").
 *
 * @see ksl.observers.ModelElementObserver
 * @see CostFormulation
 */
interface CostCalculator {
    /** The single source [ModelElement] this calculator observes. */
    val source: ModelElement

    /**
     * The line-item Responses this calculator produces, keyed by
     * [CostLine].  A calculator may produce a Response for every
     * line its source can express; values for unproduced lines are
     * absent from the map rather than zero.
     */
    val lineResponses: Map<CostLine, ResponseCIfc>

    /**
     * Tier this calculator's source belongs to.  Used by
     * [CostFormulation.byTierResponse] to partition rollups across
     * IHP / CD / ES.  A calculator that attributes to multiple tiers
     * (rare) returns null and is excluded from per-tier rollups.
     */
    val tier: NodeTier?
}
