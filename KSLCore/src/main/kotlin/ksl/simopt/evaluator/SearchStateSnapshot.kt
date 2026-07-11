package ksl.simopt.evaluator

/**
 *  A snapshot of solver-level search state captured at evaluation time and carried on a [Solution],
 *  for self-scaling penalty functions (e.g., near-feasibility-threshold schemes) that need global
 *  context rather than only per-solution memory. Null until such penalties are used; populating it
 *  is a future (solver-level) concern.
 */
class SearchStateSnapshot(
    val bestFeasibleObjective: Double? = null,
    val bestOverallObjective: Double? = null,
)
