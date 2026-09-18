package ksl.modeling.supplychain.cost

import ksl.modeling.variable.ResponseCIfc

/**
 * A cost formulation is a container of [CostCalculator]s plus the
 * rollup Responses (per line, per tier, and the formulation's total)
 * that summarize them at every replication's end.  Every rollup that
 * spans lines is denominated by a [CostBasis] the caller asks for.
 *
 * **Lifecycle.** A formulation is constructed before
 * `model.simulate()` runs, walks the `MultiEchelonNetwork` at
 * construction time to instantiate one calculator per source kind
 * (Phase 3 detail), and pre-allocates every rollup Response.  Each
 * calculator subscribes to its own source via a
 * `ksl.observers.ModelElementObserver`; the formulation itself sums
 * the calculators' per-line Responses in its own
 * `ksl.simulation.ModelElement.replicationEnded()` override —
 * KSL's tree walk guarantees the calculator children have already
 * fired by then because children are visited before parent.
 *
 * **Multi-attach.** A single `MultiEchelonNetwork` may carry
 * multiple formulations.  Each is independent — different formulations
 * may use different [CostParams], different physical-unit
 * conventions, even different line-item structures, without
 * interference.  All formulations report at every replication end;
 * the half-width summary shows confidence intervals for every
 * formulation's Responses in parallel.
 *
 * @see CostCalculator
 * @see DefaultMultiEchelonCostFormulation
 */
interface CostFormulation {
    /**
     * The calculators this formulation manages.  Phase 2's skeleton
     * formulation returns an empty collection; Phase 3 populates it
     * with concrete calculator instances.
     */
    val calculators: Collection<CostCalculator>

    /**
     * Per-line rollup Response — the sum of every calculator's
     * `lineResponses[line]` for the most recent replication, or
     * null if this formulation does not produce that line.
     */
    fun byLineResponse(line: CostLine): ResponseCIfc?

    /**
     * Per-tier rollup Response — the sum of every calculator's
     * `lineResponses[*]` whose [CostCalculator.tier] matches
     * [tier], or null if this formulation does not produce a
     * per-tier rollup for [tier].
     */
    fun byTierResponse(tier: NodeTier, basis: CostBasis): ResponseCIfc?

    /**
     * Per-(tier, line) rollup Response — the sum of every calculator's
     * `lineResponses[line]` whose [CostCalculator.tier] matches
     * [tier], or null if this formulation does not produce a rollup
     * for this combination.  Used by Phase-4 to re-point the legacy
     * 16 cost responses on [ksl.modeling.supplychain.network.MultiEchelonNetwork]
     * onto formulation-managed Responses.
     */
    fun byTierAndLineResponse(tier: NodeTier, line: CostLine): ResponseCIfc?

    /**
     * Per-node (location) rollup Response — the sum across every cost
     * line of every calculator attributed to the named owning node
     * (an inventory's / backlog's / load-builder's holder, an outbound
     * edge's supplier, an inbound edge's customer), or null when the
     * formulation tracks no node with that name. The external
     * supplier's own outbound has no owning node and reaches the
     * formulation's totals through its tier rather than through a node.
     */
    fun byNodeResponse(nodeName: String, basis: CostBasis): ResponseCIfc?

    /** The names of the owning nodes this formulation tracks per-node totals for. */
    val trackedNodeNames: Set<String>

    /**
     * Total-cost rollup Response across every line and every tier this
     * formulation produces, in the requested [CostBasis].
     *
     * A basis is required rather than assumed. This rollup spans lines of both
     * denominations, so until the caller says which one they want there is no
     * number it can return that means anything — which is exactly what the
     * former mixed grand total returned.
     */
    fun totalCostResponse(basis: CostBasis): ResponseCIfc
}
