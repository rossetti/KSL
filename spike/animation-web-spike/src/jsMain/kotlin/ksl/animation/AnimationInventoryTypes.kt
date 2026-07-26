package ksl.animation

import kotlinx.serialization.Serializable

/**
 * PHASE S SPIKE — the DTOs `ReplayModel` needs from `AnimationInventory.kt`.
 *
 * FINDING: these are pure @Serializable DTOs, but they live inside AnimationInventory.kt, which imports
 * Model, ModelElement, Conveyor, Resource, Queue, AgentModel, the station types AND
 * kotlin.reflect.full.declaredMemberProperties (JVM-only reflection). The file cannot go to commonMain,
 * but ReplayModel.conveyorDefinedEvents() needs these two types.
 *
 * Same shape of problem as GridGeometrySpec.kt. Phase 1 therefore needs a DTO-extraction pass, not just a
 * file-move pass: split the pure DTOs out of the model-coupled files into their own shareable files.
 */

/** One chained segment of a conveyor: its named entry->exit anchors and cell length. */
@Serializable
data class SegmentInfo(val entryLocation: String, val exitLocation: String, val lengthCells: Int)

/** A conveyor's structure exposed pre-run: cell size, accumulating flag, and ordered chained segments. */
@Serializable
data class ConveyorInfo(
    val name: String,
    val cellSize: Int,
    val accumulating: Boolean,
    val segments: List<SegmentInfo> = emptyList()
)
