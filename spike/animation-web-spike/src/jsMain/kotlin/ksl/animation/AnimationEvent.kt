/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.animation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The root of the strongly-typed animation event hierarchy. Each concrete
 * subclass describes one thing that happened during a simulation run that a
 * replay renderer may want to visualize.
 *
 * Design contract (see the animation implementation plan, requirement F3):
 *  - Every event records the simulated time at which it occurred via [simTime].
 *  - Every field is a primitive, a `String`, or an enum-like `String` tag. No
 *    field references a live KSL object. This keeps events safe to hand to
 *    another thread and trivial to serialize.
 *  - An entity (or any `QObject`) is identified by its native numeric id as a
 *    `Long` (e.g. `entityId`); no integer-to-string conversion is performed on
 *    the simulation thread. Model elements (queues, resources, stations,
 *    signals, ...) and agents are identified by their `String` name, which is
 *    already their natural identity. No identifier incurs a per-event
 *    numeric-to-string conversion.
 *  - Spatial events carry `x`/`y`/`z`. `z` defaults to `0.0` so 2D models read
 *    naturally while 3D agent models (Dynamics3D, Voxel, Travel3D) are supported
 *    without a later breaking change (decision D7).
 *
 * The hierarchy is `sealed` so the compiler can check that event-handling
 * `when` blocks are exhaustive, and so `kotlinx.serialization` can emit and read
 * the events polymorphically. Each subclass is tagged with a stable, short
 * [SerialName] that becomes the value of the `"event"` discriminator field in the
 * JSON form. The serial names are part of the on-disk trace format and must not
 * be changed casually; the Kotlin class names may be refactored freely.
 *
 * @property simTime the simulated time, in the model's base time unit, at which
 *           the event occurred
 */
/** Why a movable/transport resource is moving (10.8/C2): repositioning empty, carrying an entity, or going home. */
@Serializable
enum class MoverMode { EMPTY, TRANSPORTING, RETURNING_HOME }

@Serializable
sealed class AnimationEvent {

    abstract val simTime: Double

    // ──────────────────────────────────────────────────────────────────────
    //  Simulation lifecycle
    // ──────────────────────────────────────────────────────────────────────

    /** The experiment (a set of replications) has begun. */
    @Serializable
    @SerialName("ExperimentStarted")
    data class ExperimentStarted(
        override val simTime: Double,
        val experimentName: String,
        val numberOfReplications: Int
    ) : AnimationEvent()

    /** A replication has begun. Delimits a replication block in the trace stream. */
    @Serializable
    @SerialName("ReplicationStarted")
    data class ReplicationStarted(
        override val simTime: Double,
        val replicationNumber: Int
    ) : AnimationEvent()

    /** A replication has ended. */
    @Serializable
    @SerialName("ReplicationEnded")
    data class ReplicationEnded(
        override val simTime: Double,
        val replicationNumber: Int
    ) : AnimationEvent()

    /** The experiment has ended; no further events follow for this run. */
    @Serializable
    @SerialName("ExperimentEnded")
    data class ExperimentEnded(
        override val simTime: Double,
        val experimentName: String
    ) : AnimationEvent()

    // ──────────────────────────────────────────────────────────────────────
    //  Entity lifecycle
    // ──────────────────────────────────────────────────────────────────────

    /** A new entity has been created. [entityType] is the entity's class/type label. */
    @Serializable
    @SerialName("EntityCreated")
    data class EntityCreated(
        override val simTime: Double,
        val entityId: Long,
        val entityType: String
    ) : AnimationEvent()

    /** An entity has been disposed normally (it finished its process). */
    @Serializable
    @SerialName("EntityDisposed")
    data class EntityDisposed(
        override val simTime: Double,
        val entityId: Long
    ) : AnimationEvent()

    /** An entity's process ended abnormally (terminated/failed). */
    @Serializable
    @SerialName("EntityTerminated")
    data class EntityTerminated(
        override val simTime: Double,
        val entityId: Long
    ) : AnimationEvent()

    // ──────────────────────────────────────────────────────────────────────
    //  Process lifecycle
    // ──────────────────────────────────────────────────────────────────────

    /** A process coroutine has been activated for an entity. */
    @Serializable
    @SerialName("ProcessActivated")
    data class ProcessActivated(
        override val simTime: Double,
        val entityId: Long,
        val processName: String
    ) : AnimationEvent()

    /** A process coroutine has run to completion for an entity. */
    @Serializable
    @SerialName("ProcessCompleted")
    data class ProcessCompleted(
        override val simTime: Double,
        val entityId: Long,
        val processName: String
    ) : AnimationEvent()

    // ──────────────────────────────────────────────────────────────────────
    //  Delays
    // ──────────────────────────────────────────────────────────────────────

    /**
     * An entity has started a timed delay. [arrivalTime] is the simulated time at
     * which the delay will end (`simTime + duration`); carrying it lets the
     * renderer drive a progress indicator without re-deriving it.
     */
    @Serializable
    @SerialName("DelayStarted")
    data class DelayStarted(
        override val simTime: Double,
        val entityId: Long,
        val duration: Double,
        val arrivalTime: Double,
        val suspensionName: String? = null
    ) : AnimationEvent()

    /** An entity's delay has ended. */
    @Serializable
    @SerialName("DelayEnded")
    data class DelayEnded(
        override val simTime: Double,
        val entityId: Long,
        val suspensionName: String? = null
    ) : AnimationEvent()

    // ──────────────────────────────────────────────────────────────────────
    //  Seize / release of resources
    // ──────────────────────────────────────────────────────────────────────

    /** An entity's seize request was enqueued waiting for a resource. */
    @Serializable
    @SerialName("SeizeQueued")
    data class SeizeQueued(
        override val simTime: Double,
        val entityId: Long,
        val resourceName: String,
        val queueName: String,
        val amountRequested: Int
    ) : AnimationEvent()

    /** An entity is suspended waiting for units of a resource to become available. */
    @Serializable
    @SerialName("SeizeWaiting")
    data class SeizeWaiting(
        override val simTime: Double,
        val entityId: Long,
        val resourceName: String
    ) : AnimationEvent()

    /** Units of a resource have been allocated to an entity. */
    @Serializable
    @SerialName("SeizeAllocated")
    data class SeizeAllocated(
        override val simTime: Double,
        val entityId: Long,
        val resourceName: String,
        val amountAllocated: Int
    ) : AnimationEvent()

    /** An entity has released units of a resource. */
    @Serializable
    @SerialName("Released")
    data class Released(
        override val simTime: Double,
        val entityId: Long,
        val resourceName: String,
        val amountReleased: Int
    ) : AnimationEvent()

    // ──────────────────────────────────────────────────────────────────────
    //  Resource and queue state
    // ──────────────────────────────────────────────────────────────────────

    /**
     * A resource changed state. Covers both the process-view `Resource` and the
     * station-package `SResource`. [state] is a `String` tag (e.g. "Busy",
     * "Idle", "Failed", "Inactive") so the renderer needs no enum dependency.
     */
    @Serializable
    @SerialName("ResourceStateChanged")
    data class ResourceStateChanged(
        override val simTime: Double,
        val resourceName: String,
        val state: String,
        val busyUnits: Int,
        val capacity: Int
    ) : AnimationEvent()

    /** A queue's length changed. Covers every queue type (one shared event). */
    @Serializable
    @SerialName("QueueLengthChanged")
    data class QueueLengthChanged(
        override val simTime: Double,
        val queueName: String,
        val length: Int
    ) : AnimationEvent()

    /**
     * A specific QObject/entity entered a queue. Lets a renderer show the *identified* members of any
     * queue (and style them by type), not just a count (8C.2). Covers every queue type.
     */
    @Serializable
    @SerialName("QObjectEnqueued")
    data class QObjectEnqueued(
        override val simTime: Double,
        val entityId: Long,
        val queueName: String
    ) : AnimationEvent()

    /** A specific QObject/entity left a queue (8C.2). */
    @Serializable
    @SerialName("QObjectDequeued")
    data class QObjectDequeued(
        override val simTime: Double,
        val entityId: Long,
        val queueName: String
    ) : AnimationEvent()

    // ──────────────────────────────────────────────────────────────────────
    //  Hold / signal
    // ──────────────────────────────────────────────────────────────────────

    /** An entity entered a hold queue. */
    @Serializable
    @SerialName("HoldEntered")
    data class HoldEntered(
        override val simTime: Double,
        val entityId: Long,
        val holdName: String
    ) : AnimationEvent()

    /** An entity was released from a hold queue. */
    @Serializable
    @SerialName("HoldReleased")
    data class HoldReleased(
        override val simTime: Double,
        val entityId: Long,
        val holdName: String
    ) : AnimationEvent()

    /** An entity is waiting for a signal. */
    @Serializable
    @SerialName("WaitingForSignal")
    data class WaitingForSignal(
        override val simTime: Double,
        val entityId: Long,
        val signalName: String
    ) : AnimationEvent()

    /** An entity received the signal it was waiting for. */
    @Serializable
    @SerialName("SignalReceived")
    data class SignalReceived(
        override val simTime: Double,
        val entityId: Long,
        val signalName: String
    ) : AnimationEvent()

    // ──────────────────────────────────────────────────────────────────────
    //  Spatial movement (process view)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * An entity began a point-to-point move at constant [velocity]. The renderer
     * reconstructs the entity's position at any replay time by linearly
     * interpolating from `(fromX, fromY, fromZ)` to `(toX, toY, toZ)` between
     * [simTime] and [arrivalTime]. This same contract covers the agent-layer
     * travel primitives, whose legs are also straight-line constant-velocity.
     */
    @Serializable
    @SerialName("MoveStarted")
    data class MoveStarted(
        override val simTime: Double,
        val entityId: Long,
        val fromX: Double,
        val fromY: Double,
        val toX: Double,
        val toY: Double,
        val velocity: Double,
        val duration: Double,
        val arrivalTime: Double,
        val fromZ: Double = 0.0,
        val toZ: Double = 0.0,
        /**
         * Names of the origin/destination locations, when the move is between named locations.
         * Carried so a renderer can place the move by **name** for spatial models that have no
         * coordinates (e.g. `DistancesModel`, whose `from*`/`to*` are `NaN`): the renderer resolves
         * the names against the layout (decision 8H.3). Null for coordinate-only moves.
         */
        val fromLocationName: String? = null,
        val toLocationName: String? = null
    ) : AnimationEvent()

    /** An entity completed a move and is at rest at `(toX, toY, toZ)`. */
    @Serializable
    @SerialName("MoveCompleted")
    data class MoveCompleted(
        override val simTime: Double,
        val entityId: Long,
        val toX: Double,
        val toY: Double,
        val toZ: Double = 0.0
    ) : AnimationEvent()

    /**
     * A named spatial element (e.g. a movable/transport resource) started moving (8K.5). Mirrors
     * [MoveStarted] but is keyed by [name] (the spatial element's name) rather than an entity id, so
     * the renderer can animate the resource itself — including *empty* repositioning that carries no
     * entity. Carries location names so coordinate-free spaces (DistancesModel) resolve by name (8H.3).
     */
    @Serializable
    @SerialName("SpatialElementMoved")
    data class SpatialElementMoved(
        override val simTime: Double,
        val name: String,
        val fromX: Double, val fromY: Double, val fromZ: Double = 0.0,
        val toX: Double, val toY: Double, val toZ: Double = 0.0,
        val velocity: Double,
        val duration: Double,
        val arrivalTime: Double,
        val fromLocationName: String? = null,
        val toLocationName: String? = null,
        /** Why the mover is moving (10.8/C2): EMPTY repositioning, TRANSPORTING an entity, or RETURNING_HOME. */
        val mode: MoverMode = MoverMode.EMPTY,
        /** The carried entity when [mode] is TRANSPORTING (else null), so the renderer can draw it on the mover. */
        val carriedEntityId: Long? = null,
        val carriedEntityType: String? = null
    ) : AnimationEvent()

    /** A named spatial element finished moving (8K.5). */
    @Serializable
    @SerialName("SpatialElementMoveCompleted")
    data class SpatialElementMoveCompleted(
        override val simTime: Double,
        val name: String,
        val toX: Double, val toY: Double, val toZ: Double = 0.0
    ) : AnimationEvent()

    /**
     * Records an agent projection's spatial dimensions (8K.6a), so the renderer can draw the space
     * backdrop **from the trace** when the layout omits a `gridSpace`/`continuousSpace`. [kind] is
     * "Grid" (uses [cols]/[rows]/[cellSize]) or "Continuous" (uses [xMin]/[xMax]/[yMin]/[yMax]).
     */
    @Serializable
    @SerialName("SpaceDefined")
    data class SpaceDefined(
        override val simTime: Double,
        val name: String,
        val kind: String,
        val cols: Int = 0, val rows: Int = 0, val cellSize: Double = 1.0,
        val xMin: Double = 0.0, val xMax: Double = 0.0, val yMin: Double = 0.0, val yMax: Double = 0.0,
        val torus: Boolean = false
    ) : AnimationEvent()

    /**
     * Defines a network (graph) backdrop with auto-laid-out [nodes] and weighted [edges] (G7). Emitted once per
     * replication after the model wires the graph (e.g. in `initialize()`), since a
     * [ksl.modeling.agent.NetworkProjection] is non-spatial — node positions are assigned by a layout at
     * snapshot time. Agents are emitted at the same node positions ([AgentPositionChanged]) so their state
     * colors render on top of the edges.
     */
    @Serializable
    @SerialName("NetworkDefined")
    data class NetworkDefined(
        override val simTime: Double,
        val name: String,
        val nodes: List<NetworkNodeDef> = emptyList(),
        val edges: List<NetworkEdgeDef> = emptyList()
    ) : AnimationEvent()

    /**
     * A flow-field distance gradient over the grid space [spaceName] (G11): a one-time per-replication
     * snapshot (the field is computed once at initialize()). [cells] gives reachable cells with their
     * distance-to-goal; [maxDistance] anchors the heatmap color ramp; [cellSize]/[originX]/[originY] place
     * the grid in world coordinates. Opt-in (off by default) — an "agent debugging / teaching" overlay.
     */
    @Serializable
    @SerialName("FlowFieldDefined")
    data class FlowFieldDefined(
        override val simTime: Double,
        val spaceName: String,
        val cols: Int,
        val rows: Int,
        val cellSize: Double,
        val originX: Double,
        val originY: Double,
        val cells: List<FlowCell> = emptyList(),
        val maxDistance: Double = 0.0
    ) : AnimationEvent()

    /**
     * A route the model planned for an agent (G12): the polyline [points] in world coordinates, reported by the
     * model right after it computes a path (e.g. A-star / network shortest path). Opt-in (off by default), emitted
     * only when the model calls reportPlannedPath — a teaching/debugging overlay showing intended vs actual.
     */
    @Serializable
    @SerialName("PlannedPath")
    data class PlannedPath(
        override val simTime: Double,
        val agentName: String,
        val points: List<PathPoint> = emptyList()
    ) : AnimationEvent()

    /**
     * A transient highlight ("pulse") the model reports at a world location when something noteworthy happens
     * there — e.g. a delivery completes at a drop-off point (G-animated). Reported by the model via
     * `reportMarkerPulse`; opt-in (off by default), so a normal run pays zero cost. The renderer draws an
     * expanding, fading ring centered on ([x],[y]) over the window `[simTime, simTime + holdTime]` (model time);
     * [label] and [colorHex] (e.g. "#1f77b4") are optional styling. A teaching/demo overlay — it visualizes a
     * domain event, not agent internals, but rides the same capture/display gates as G10–G12.
     */
    @Serializable
    @SerialName("MarkerPulsed")
    data class MarkerPulsed(
        override val simTime: Double,
        val x: Double,
        val y: Double,
        val z: Double = 0.0,
        val holdTime: Double = 1.0,
        val label: String? = null,
        val colorHex: String? = null
    ) : AnimationEvent()

    /**
     * A sampled per-agent velocity ([vx],[vy]) and/or net steering force ([fx],[fy]) for the vector overlay
     * (G10). Rate-limited by the sampler's interval (default 5/sec), decoupled from the model's integration
     * step. A NaN component means it wasn't captured. Opt-in (off by default) — the volume-sensitive overlay.
     */
    @Serializable
    @SerialName("AgentVectorSampled")
    data class AgentVectorSampled(
        override val simTime: Double,
        val agentName: String,
        val projectionName: String,
        val vx: Double = Double.NaN,
        val vy: Double = Double.NaN,
        val fx: Double = Double.NaN,
        val fy: Double = Double.NaN
    ) : AnimationEvent()

    // ──────────────────────────────────────────────────────────────────────
    //  Conveyor
    // ──────────────────────────────────────────────────────────────────────

    /** An entity requested access to a conveyor at an entry location. */
    @Serializable
    @SerialName("ConveyorAccessRequested")
    data class ConveyorAccessRequested(
        override val simTime: Double,
        val entityId: Long,
        val conveyorName: String,
        val entryLocation: String
    ) : AnimationEvent()

    /** An entity's conveyor entry was blocked (could not board yet). */
    @Serializable
    @SerialName("ConveyorEntryBlocked")
    data class ConveyorEntryBlocked(
        override val simTime: Double,
        val entityId: Long,
        val conveyorName: String,
        val entryLocation: String
    ) : AnimationEvent()

    /** An entity began riding a conveyor from one location toward another. */
    @Serializable
    @SerialName("ConveyorRideStarted")
    data class ConveyorRideStarted(
        override val simTime: Double,
        val entityId: Long,
        val conveyorName: String,
        val fromLocation: String,
        val toLocation: String
    ) : AnimationEvent()

    /** An entity reached its conveyor destination. */
    @Serializable
    @SerialName("ConveyorDestinationReached")
    data class ConveyorDestinationReached(
        override val simTime: Double,
        val entityId: Long,
        val conveyorName: String,
        val location: String
    ) : AnimationEvent()

    /** An entity exited (fully off) a conveyor. */
    @Serializable
    @SerialName("ConveyorExited")
    data class ConveyorExited(
        override val simTime: Double,
        val entityId: Long,
        val conveyorName: String,
        val location: String
    ) : AnimationEvent()

    /**
     * The static cell structure of a conveyor (emitted once per replication, at the conveyor's
     * initialize). [anchorLocations] and [anchorCells] are parallel lists giving each named entry/
     * exit location and its cell index along the belt, so a renderer can map any cell index to a
     * world position by interpolating between the (layout-positioned) named anchors (8G.6).
     */
    @Serializable
    @SerialName("ConveyorDefined")
    data class ConveyorDefined(
        override val simTime: Double,
        val conveyorName: String,
        val anchorLocations: List<String>,
        val anchorCells: List<Int>
    ) : AnimationEvent()

    /**
     * A conveyed item advanced to cell [cellIndex] of its conveyor (the single
     * `ConveyorRequest.moveForwardOneCell` chokepoint). The renderer maps the cell index to a world
     * position via the conveyor's [ConveyorDefined] anchors and interpolates between consecutive
     * samples — the conveyor analog of the agent position stream (8G.5). Captures accumulation and
     * blocking for free: when an item cannot advance, no event fires and it holds on the belt.
     */
    @Serializable
    @SerialName("ConveyorItemMoved")
    data class ConveyorItemMoved(
        override val simTime: Double,
        val entityId: Long,
        val conveyorName: String,
        val cellIndex: Int
    ) : AnimationEvent()

    // ──────────────────────────────────────────────────────────────────────
    //  Statistics
    // ──────────────────────────────────────────────────────────────────────

    /**
     * A statistical quantity was observed. Drives live bar charts and time-series
     * plots. Covers `Response`, `TWResponse`, and `Counter` (the renderer does not
     * need to distinguish which). For responses, the current **within-replication**
     * statistics ([count]/[average]/[min]/[max]) are carried so a renderer can show
     * a live summary without recomputing (decision D11); they are `NaN` for counters
     * and any source without within-replication statistics.
     */
    @Serializable
    @SerialName("ResponseObserved")
    data class ResponseObserved(
        override val simTime: Double,
        val responseName: String,
        val value: Double,
        val count: Double = Double.NaN,
        val average: Double = Double.NaN,
        val min: Double = Double.NaN,
        val max: Double = Double.NaN
    ) : AnimationEvent()

    // ──────────────────────────────────────────────────────────────────────
    //  Process interaction
    // ──────────────────────────────────────────────────────────────────────

    /** An entity is waiting for a child process to complete. */
    @Serializable
    @SerialName("WaitingForProcess")
    data class WaitingForProcess(
        override val simTime: Double,
        val entityId: Long,
        val childProcessName: String
    ) : AnimationEvent()

    /** A child process an entity was waiting on has completed. */
    @Serializable
    @SerialName("WaitForProcessCompleted")
    data class WaitForProcessCompleted(
        override val simTime: Double,
        val entityId: Long,
        val childProcessName: String
    ) : AnimationEvent()

    // ──────────────────────────────────────────────────────────────────────
    //  Batching
    // ──────────────────────────────────────────────────────────────────────

    /** A batch of entities was formed into a single batch entity of [size] members. */
    @Serializable
    @SerialName("BatchFormed")
    data class BatchFormed(
        override val simTime: Double,
        val batchEntityId: Long,
        val batchName: String,
        val size: Int
    ) : AnimationEvent()

    // ──────────────────────────────────────────────────────────────────────
    //  Agent-based modeling (ABM)
    // ──────────────────────────────────────────────────────────────────────

    /** An agent joined the model registry. */
    @Serializable
    @SerialName("AgentRegistered")
    data class AgentRegistered(
        override val simTime: Double,
        val agentName: String,
        val agentType: String
    ) : AnimationEvent()

    /** An agent left the model registry. */
    @Serializable
    @SerialName("AgentRemoved")
    data class AgentRemoved(
        override val simTime: Double,
        val agentName: String
    ) : AnimationEvent()

    /** An agent's statechart entered a state. */
    @Serializable
    @SerialName("AgentStateEntered")
    data class AgentStateEntered(
        override val simTime: Double,
        val agentName: String,
        val stateName: String
    ) : AnimationEvent()

    /** An agent's statechart exited a state. */
    @Serializable
    @SerialName("AgentStateExited")
    data class AgentStateExited(
        override val simTime: Double,
        val agentName: String,
        val stateName: String
    ) : AnimationEvent()

    /** An agent's statechart transitioned between two states. */
    @Serializable
    @SerialName("AgentTransition")
    data class AgentTransition(
        override val simTime: Double,
        val agentName: String,
        val fromState: String,
        val toState: String
    ) : AnimationEvent()

    /** A message was delivered into an agent's mailbox. [mailboxSize] is the post-delivery size. */
    @Serializable
    @SerialName("AgentMessageDelivered")
    data class AgentMessageDelivered(
        override val simTime: Double,
        val agentName: String,
        val messageType: String,
        val mailboxSize: Int
    ) : AnimationEvent()

    /** A message was consumed from an agent's mailbox. [mailboxSize] is the post-consumption size. */
    @Serializable
    @SerialName("AgentMessageConsumed")
    data class AgentMessageConsumed(
        override val simTime: Double,
        val agentName: String,
        val messageType: String,
        val mailboxSize: Int
    ) : AnimationEvent()

    /**
     * An agent's position in a projection changed. Emitted from the projection's
     * `placeAt`/`moveTo` chokepoint, so it uniformly captures travel, steering
     * dynamics, and manual moves (decision D6, Phase 3A.1).
     */
    @Serializable
    @SerialName("AgentPositionChanged")
    data class AgentPositionChanged(
        override val simTime: Double,
        val agentName: String,
        val projectionName: String,
        val x: Double,
        val y: Double,
        val z: Double = 0.0
    ) : AnimationEvent()

    /**
     * An agent's position as read by a timed sampler (the non-invasive position
     * option, decision D6 option b, Phase 2A.4).
     */
    @Serializable
    @SerialName("AgentPositionSampled")
    data class AgentPositionSampled(
        override val simTime: Double,
        val agentName: String,
        val projectionName: String,
        val x: Double,
        val y: Double,
        val z: Double = 0.0
    ) : AnimationEvent()

    // ──────────────────────────────────────────────────────────────────────
    //  Station (flow-network) package
    // ──────────────────────────────────────────────────────────────────────

    /**
     * An entity entered a station network through an ingress. [qObjectType] is the QObject's
     * integer type id (1 by default; a `QObjectClass.typeId` in multi-class networks) and
     * [qObjectTypeName] is the resolved class name when one is registered (else null), so a
     * renderer can style station entities by class (8G.1).
     */
    @Serializable
    @SerialName("EnteredNetwork")
    data class EnteredNetwork(
        override val simTime: Double,
        val entityId: Long,
        val networkName: String,
        val ingressName: String,
        val qObjectType: Int = 1,
        val qObjectTypeName: String? = null
    ) : AnimationEvent()

    /** An entity left a station network through an egress (disposed). */
    @Serializable
    @SerialName("ExitedNetwork")
    data class ExitedNetwork(
        override val simTime: Double,
        val entityId: Long,
        val networkName: String,
        val egressName: String
    ) : AnimationEvent()

    /** An entity was transferred (handed off) out of a network at an egress. */
    @Serializable
    @SerialName("Transferred")
    data class Transferred(
        override val simTime: Double,
        val entityId: Long,
        val networkName: String,
        val egressName: String
    ) : AnimationEvent()

    /** An entity entered a station. */
    @Serializable
    @SerialName("StationEntered")
    data class StationEntered(
        override val simTime: Double,
        val entityId: Long,
        val stationName: String
    ) : AnimationEvent()

    /** An entity exited a station. */
    @Serializable
    @SerialName("StationExited")
    data class StationExited(
        override val simTime: Double,
        val entityId: Long,
        val stationName: String
    ) : AnimationEvent()

    companion object {
        /**
         * The version of the `.atf` trace format. Written into the
         * [AnimationTraceHeader] that begins every trace file so a renderer can
         * reject or adapt to a file produced by a different format generation.
         * Increment this whenever a change to the event hierarchy or the JSON
         * encoding would break an existing renderer.
         */
        const val FORMAT_VERSION: Int = 1

        /**
         * The canonical JSON configuration for the animation trace format.
         *
         *  - `classDiscriminator = "event"` names the type-tag field, so each line
         *    reads like `{"event":"DelayStarted", ...}`.
         *  - `encodeDefaults = true` writes defaulted fields (e.g. `z = 0.0`,
         *    `suspensionName = null`) so the renderer never has to know the Kotlin
         *    defaults to interpret a line.
         *  - `prettyPrint = false` keeps each event on a single line, as required
         *    by the JSON Lines (`.atf`) format.
         */
        val format: Json = Json {
            classDiscriminator = "event"
            encodeDefaults = true
            prettyPrint = false
            // Coordinate-free spatial models (e.g. DistancesModel) emit NaN positions; the renderer
            // resolves those by location name (8H.3). Allow NaN/Infinity so such traces serialize.
            allowSpecialFloatingPointValues = true
        }

        /** Serializes [event] to a single-line JSON string (one `.atf` record). */
        fun encodeToLine(event: AnimationEvent): String = format.encodeToString(event)

        /** Parses one `.atf` record [line] back into a strongly-typed event. */
        fun decodeFromLine(line: String): AnimationEvent = format.decodeFromString(line)
    }
}

/**
 * True for the four experiment/replication lifecycle markers ([AnimationEvent.ExperimentStarted],
 * [AnimationEvent.ReplicationStarted], [AnimationEvent.ReplicationEnded], [AnimationEvent.ExperimentEnded]).
 * These delimit the trace structure and are kept regardless of a capture window (the run's framing is true
 * at its real times); the filtering decorators consult this so markers bypass time-windowing while content
 * events do not.
 */
internal val AnimationEvent.isLifecycleMarker: Boolean
    get() = this is AnimationEvent.ExperimentStarted ||
        this is AnimationEvent.ReplicationStarted ||
        this is AnimationEvent.ReplicationEnded ||
        this is AnimationEvent.ExperimentEnded

/**
 * The id of the entity (a `ProcessModel.Entity`, including transient agents and station QObjects) this event is
 * about, or `null` for events not keyed to an entity (lifecycle markers, resource/queue/response state, named
 * spatial elements, agents-by-name, conveyor/space definitions). Lets capture filters that gate by an entity's
 * type/process recover the entity from any of its events (10.1d). `BatchFormed` reports its batch entity's id.
 */
internal val AnimationEvent.entityIdOrNull: Long?
    get() = when (this) {
        is AnimationEvent.EntityCreated -> entityId
        is AnimationEvent.EntityDisposed -> entityId
        is AnimationEvent.EntityTerminated -> entityId
        is AnimationEvent.ProcessActivated -> entityId
        is AnimationEvent.ProcessCompleted -> entityId
        is AnimationEvent.DelayStarted -> entityId
        is AnimationEvent.DelayEnded -> entityId
        is AnimationEvent.SeizeQueued -> entityId
        is AnimationEvent.SeizeWaiting -> entityId
        is AnimationEvent.SeizeAllocated -> entityId
        is AnimationEvent.Released -> entityId
        is AnimationEvent.QObjectEnqueued -> entityId
        is AnimationEvent.QObjectDequeued -> entityId
        is AnimationEvent.HoldEntered -> entityId
        is AnimationEvent.HoldReleased -> entityId
        is AnimationEvent.WaitingForSignal -> entityId
        is AnimationEvent.SignalReceived -> entityId
        is AnimationEvent.MoveStarted -> entityId
        is AnimationEvent.MoveCompleted -> entityId
        is AnimationEvent.ConveyorAccessRequested -> entityId
        is AnimationEvent.ConveyorEntryBlocked -> entityId
        is AnimationEvent.ConveyorRideStarted -> entityId
        is AnimationEvent.ConveyorDestinationReached -> entityId
        is AnimationEvent.ConveyorExited -> entityId
        is AnimationEvent.ConveyorItemMoved -> entityId
        is AnimationEvent.WaitingForProcess -> entityId
        is AnimationEvent.WaitForProcessCompleted -> entityId
        is AnimationEvent.BatchFormed -> batchEntityId
        is AnimationEvent.EnteredNetwork -> entityId
        is AnimationEvent.ExitedNetwork -> entityId
        is AnimationEvent.Transferred -> entityId
        is AnimationEvent.StationEntered -> entityId
        is AnimationEvent.StationExited -> entityId
        else -> null
    }

/** A laid-out node in a [AnimationEvent.NetworkDefined]: an agent [id] at world position ([x],[y]) — G7. */
@Serializable
data class NetworkNodeDef(val id: String, val x: Double, val y: Double)

/** A weighted edge in a [AnimationEvent.NetworkDefined], referencing node ids [from] and [to] — G7. */
@Serializable
data class NetworkEdgeDef(val from: String, val to: String, val weight: Double = 1.0)

/** One cell of a [AnimationEvent.FlowFieldDefined] heatmap: grid ([col],[row]) with its [distance] to the
 *  nearest source/goal (G11). */
@Serializable
data class FlowCell(val col: Int, val row: Int, val distance: Double)

/** One world-coordinate vertex of a [AnimationEvent.PlannedPath] route polyline (G12). */
@Serializable
data class PathPoint(val x: Double, val y: Double)
