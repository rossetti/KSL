package ksl.modeling.agent

// SPIKE: extracted from GridGraph.kt line 34. Phase 1 must split this enum out of that 499-line
// file so the GridGeometrySpec DTO chain is shareable without dragging GridGraph along.
enum class MovementRule { MOORE, VON_NEUMANN }
