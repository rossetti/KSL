package ksl.modeling.supplychain.cost

/**
 * The dimensional basis of a [CostLine] — what its value is denominated in.
 *
 * The cost package forms its line items in two different units, and each is the
 * natural one for its line: a holding charge really is a rate, and an ordering
 * charge really is a per-event amount. The two cannot be added without first
 * being brought to a common basis, which is what this enum exists to make
 * checkable rather than a matter of reading the calculators.
 *
 * Declaring the basis on the line itself, rather than in prose, is what lets a
 * rollup compute a dimensionally consistent total from `line.basis` and what
 * makes it impossible to add a line without saying which kind it is.
 */
enum class CostBasis {

    /**
     * Denominated per modeler-chosen time unit. Formed as a time-weighted
     * average times a rate, so its value does not depend on how long the
     * replication observed. Multiply by observed time to obtain dollars.
     */
    RatePerTime,

    /**
     * A per-replication dollar total, already dimensioned in dollars. Formed as
     * a counter times a $/event constant, so its value grows with how long the
     * replication observed. Divide by observed time to obtain a rate.
     */
    PerReplicationTotal
}
