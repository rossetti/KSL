package ksl.modeling.supplychain.cost

/**
 * Which dimensionally consistent form a cost rollup should be reported in.
 *
 * A rollup that spans more than one [CostLine] spans more than one [CostBasis],
 * so there is no single number it can report without first choosing a form.
 * Both forms below are valid and neither is preferred: the rate is independent
 * of how long the replication observed and is the one to compare across runs of
 * unequal length, while the total is the more intuitive reading and matches the
 * way the inventory trade-off is usually posed.
 *
 * Asking for a form is therefore not a nuisance parameter. It is the question
 * the caller has to answer before the number means anything.
 */
enum class TotalForm {

    /**
     * Dollars accumulated over the observed window — the replication's length
     * less its warm-up. Rate lines are multiplied by that window; per-event
     * lines are already in dollars and enter unchanged.
     */
    HorizonTotal,

    /**
     * Dollars per unit time. Per-event lines are divided by the observed window;
     * rate lines are already rates and enter unchanged. Invariant to the length
     * of the run once the system is stationary, which is what makes it the form
     * to compare runs of different lengths with.
     */
    Rate
}
