package ksl.modeling.supplychain.cost

/**
 * What a cost quantity is denominated in.
 *
 * The cost package forms its line items in two different denominations, and each
 * is the natural one for its line: a holding charge really is a rate, and an
 * ordering charge really is a per-event amount. The two cannot be added without
 * first being brought to a common denomination, which is what this enum exists to
 * make checkable rather than a matter of reading the calculators.
 *
 * It serves two purposes, which are the same question asked twice. On a
 * [CostLine] it states what that line is denominated in. Passed to a rollup, it
 * asks for the total in that denomination — and since a rollup spans lines of
 * both kinds, there is no total it can report until the caller says which.
 *
 * Both denominations are valid and neither is preferred. The per-unit-time form
 * is independent of how long the replication ran and is the one to compare runs
 * of unequal length with; the per-replication form is the more intuitive reading
 * and matches the way the inventory trade-off is usually posed.
 */
enum class CostBasis {

    /**
     * Dollars per unit of the modeler's time unit. A line denominated this way is
     * formed as a time-weighted average times a rate, so its value does not depend
     * on how long the replication observed. Multiply by the observed window to
     * obtain dollars.
     */
    PerUnitTime,

    /**
     * Dollars accumulated over one replication's observed window — its ending time
     * less its warm-up. A line denominated this way is formed as a counter times a
     * $/event constant, so its value grows with how long the replication observed.
     * Divide by the observed window to obtain a rate.
     */
    PerReplication
}
