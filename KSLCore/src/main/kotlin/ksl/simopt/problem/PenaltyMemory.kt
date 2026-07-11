package ksl.simopt.problem

/**
 *  Per-(design-point, constraint) memory carried by a solution to support memoryful penalty
 *  functions such as the Park and Kim (2015) Penalty Function with Memory. Each penalty type defines
 *  its own concrete subtype, so new penalty families carry whatever state they need. Snapshots are
 *  immutable and ride on the (immutable) solution, persisted across visits by the solution cache.
 */
interface PenaltyMemory
