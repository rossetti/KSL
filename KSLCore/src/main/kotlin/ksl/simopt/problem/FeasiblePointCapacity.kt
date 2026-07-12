package ksl.simopt.problem

/**
 *  A structured description of whether a problem's input lattice can supply a requested number of
 *  distinct feasible input points — for example a solver population or a space-filling design.
 *  Produced by `ProblemDefinition.feasiblePointCapacity`, it lets a caller decide programmatically
 *  (e.g. cap a population, flag a benchmark cell, drive a UI) rather than parse a log message.
 *
 *  `latticeSize` is the number of distinct points on the input grid (see
 *  `ProblemDefinition.inputLatticeSize`), or `null` when that is effectively unbounded (a continuous
 *  input, or a grid too large to count). It is an **upper bound** on the number of distinct
 *  input-feasible points: linear and functional constraints can only reduce it. Consequently
 *  `sufficient == true` means the grid is large enough for the request but constraints may still
 *  reduce the feasible set below it, whereas `sufficient == false` means the request cannot be met on
 *  grid size alone.
 *
 *  @param requestedCount the number of distinct feasible points requested; must be positive
 *  @param latticeSize the input-grid size, or `null` when effectively unbounded; must be non-negative
 */
data class FeasiblePointCapacity(
    val requestedCount: Int,
    val latticeSize: Long?
) {
    init {
        require(requestedCount > 0) { "The requested count must be positive." }
        require(latticeSize == null || latticeSize >= 0L) { "The lattice size must be non-negative." }
    }

    /**
     *  True when the input lattice is effectively unbounded (a continuous input, or a grid too large to
     *  count). Such a problem always has ample distinct feasible points on grid size alone.
     */
    val unbounded: Boolean
        get() = latticeSize == null

    /**
     *  True when the input grid has at least `requestedCount` distinct points — i.e. the request is not
     *  impossible on grid size alone (always true when the lattice is unbounded). Note that linear and
     *  functional constraints can still reduce the feasible set below `requestedCount`.
     */
    val sufficient: Boolean
        get() = latticeSize == null || requestedCount <= latticeSize

    /**
     *  The number of points by which the request exceeds the grid — `requestedCount - latticeSize` when
     *  insufficient, and 0 when sufficient or unbounded.
     */
    val shortfall: Long
        get() = if (latticeSize == null || requestedCount <= latticeSize) 0L else requestedCount - latticeSize
}
