/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.general.agent

import ksl.modeling.agent.AgentLike
import ksl.modeling.agent.AgentMessage
import ksl.modeling.agent.AgentModel
import ksl.modeling.agent.AgentSignal
import ksl.modeling.agent.Cell
import ksl.modeling.agent.ContinuousProjection
import ksl.modeling.agent.GridGraph
import ksl.modeling.agent.MovableAgentResource
import ksl.modeling.agent.MovementRule
import ksl.modeling.agent.Point2D
import ksl.modeling.agent.contractNet
import ksl.modeling.agent.nonNegative
import ksl.modeling.agent.positive
import ksl.modeling.agent.probability
import ksl.modeling.agent.receiveMessage
import ksl.modeling.agent.receiveMessageOfType
import ksl.modeling.agent.sendMessage
import ksl.modeling.agent.travelThrough
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.variable.Counter
import ksl.modeling.variable.Response
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.ExponentialRV

/**
 *  Warehouse with autonomous AGVs — the canonical "agentic resources
 *  on a grid with obstacles" worked example. Ties together every
 *  shipped phase of `ksl.modeling.agent` except the spatial bridge
 *  (4.1), with a focus on the parts the other worked examples don't
 *  exercise:
 *
 *   - **Grid layout with rack obstacles** ([GridGraph], Phase 3.6).
 *     The warehouse floor is a 30×30 lattice with blocked-out rack
 *     rows; AGVs plan routes around them.
 *   - **Multi-source distance field** (Phase 3.6). Computed once at
 *     each replication's `initialize()` from the set of charging
 *     stations, then every AGV that needs to charge follows the
 *     gradient — O(neighbors) per step instead of a per-AGV
 *     shortest-path call.
 *   - **Brain / body separation.** Each AGV has two halves: a
 *     [MovableAgentResource] body (seizable, position-tracked) and
 *     an [AGVController] brain (an `Agent` with mailbox, statechart,
 *     and process body) that bids on tasks and drives the body. This
 *     is the standard pattern when a physical resource needs to be
 *     both *acted on* by other entities (seize / release) and
 *     *decide for itself* (bid in a protocol).
 *   - **Contract-Net Protocol for task allocation** ([contractNet],
 *     Phase 1c). Per task, the dispatcher broadcasts a
 *     call-for-proposals; each idle controller bids its shortest-path
 *     distance to the pickup cell; the dispatcher awards to the
 *     lowest bidder. Losers stay idle for the next round.
 *   - **Hierarchical statechart with stats** (Phase 1b.1 + 2.7). Each
 *     controller's statechart cycles `Idle → Bidding → Working →
 *     Idle` (or `Charging → Idle`). With `collectPerformance()`,
 *     time-in-state TWResponses appear in the report automatically.
 *
 *  ## Layout
 *
 *  30×30 grid (1.0×1.0 cells in continuous coordinates). Four
 *  horizontal rack-rows on rows 5–6, 10–11, 15–16, 20–21, each with a
 *  3-cell cross-aisle gap. Three charging stations on the left edge.
 *
 *  ```
 *  ......................... .........
 *  .........................  station
 *  ......................... .........
 *  ........................   <- aisle
 *  C........................
 *  RRRRRRRR.RRRRRRRR.RRRRRRR  rack-row
 *  RRRRRRRR.RRRRRRRR.RRRRRRR  rack-row
 *  .........................
 *  .........................
 *  ........................
 *  RRRRRRRR.RRRRRRRR.RRRRRRR  rack-row
 *  ...
 *  ```
 *
 *  ## Behavior
 *
 *  1. `TaskGenerator` produces `PalletTask`s at exponential intervals
 *     with random passable pickup/dropoff cells, posts them to the
 *     `Dispatcher`'s mailbox.
 *  2. `Dispatcher` per task: broadcasts a CFP via [contractNet],
 *     awards to the AGV that quotes the lowest pickup distance.
 *  3. Each `AGVController` listens for CFPs. On a live, biddable
 *     CFP (battery OK, pickup reachable), it Proposes its shortest
 *     path length. On Accept it drives to pickup, loads, drives to
 *     dropoff, unloads. Battery decays per cell traveled. When the
 *     battery falls below threshold after a task, the controller
 *     follows the precomputed charger-distance gradient to the
 *     nearest charger, takes the AGV off-shift while it recharges,
 *     then goes back on-shift.
 *
 *  ## Reading order
 *
 *  Start with [AGV] (the physical body), then [AGVController] (the
 *  brain), then [Dispatcher] (the protocol), then [TaskGenerator]
 *  (the workload). See `design/warehouse-agv-walkthrough.md` for a
 *  step-by-step incremental construction.
 */
class WarehouseAGVExample(parent: ModelElement, name: String? = null) :
    AgentModel(parent, name) {

    // ── World geometry ──────────────────────────────────────────────────────

    /** Square grid side length, in cells. */
    val gridSize: Int = 30

    /** Continuous-space size of one cell. */
    val cellSize: Double = 1.0

    /**
     *  The navigation lattice. Cells with rack obstacles are
     *  [GridGraph.block]ed and become unreachable.
     */
    val graph: GridGraph = GridGraph(gridSize, gridSize, movementRule = MovementRule.MOORE)

    /** Charging stations on the left wall, evenly spaced. */
    val chargers: List<Cell> = listOf(Cell(0, 4), Cell(0, 14), Cell(0, 24))

    init {
        // Build the rack obstacles. Four rack rows, each two cells
        // tall, separated by aisles. Each row has a 3-cell gap
        // (cross-aisle) at columns 9..11 and 19..21.
        val rackRowPairs = listOf(5 to 6, 10 to 11, 15 to 16, 20 to 21)
        val gapColumns = (9..11).toSet() + (19..21).toSet()
        for ((r1, r2) in rackRowPairs) {
            for (col in 0 until gridSize) {
                if (col in gapColumns) continue
                if (col == 0) continue  // leave left lane clear for charger access
                graph.block(Cell(col, r1))
                graph.block(Cell(col, r2))
            }
        }
    }

    // ── Continuous positions for AGV bodies ────────────────────────────────

    private val world: Context<AgentLike> = Context("agvWorld")

    /** Continuous projection: AGV positions are real-valued. */
    val space: ContinuousProjection<AgentLike> = ContinuousProjection(
        world,
        xRange = 0.0..(gridSize * cellSize),
        yRange = 0.0..(gridSize * cellSize),
    )

    /** Center-of-cell coordinates used as movement waypoints. */
    fun cellCenter(cell: Cell): Point2D =
        Point2D(cell.col * cellSize + cellSize / 2.0, cell.row * cellSize + cellSize / 2.0)

    // ── AGV: the seizable, position-tracked body ───────────────────────────

    /**
     *  Physical AGV. The body is a [MovableAgentResource] — seizable
     *  by its controller (or any other entity that wants exclusive
     *  use) and tracked in [space] for spatial queries. It also owns
     *  the lifecycle [statechart] (Idle/Bidding/Working/Charging) so
     *  that calling `collectPerformance()` produces the per-state
     *  TWResponses without any extra wiring.
     *
     *  Battery state and the controller-known [currentCell] live here
     *  rather than on the controller, so anyone holding a reference
     *  to the body can read them (in particular, the controller's
     *  inner-class enclosing-scope access).
     */
    inner class AGV(aName: String, initCell: Cell) : MovableAgentResource(
        agentModel = this@WarehouseAGVExample,
        space = space,
        initPosition = cellCenter(initCell),
        name = aName,
    ) {
        /** Fraction of full charge remaining, in [0, 1]. */
        var battery: Double = 1.0
            internal set

        /**
         *  Last cell the controller drove the AGV to. Updated at the
         *  end of each `driveTo`. Consistent between trips; during a
         *  `travelThrough` the continuous position may be mid-cell.
         */
        var currentCell: Cell = initCell
            internal set

        // Signal-driven statechart: state advertisement, not behavior.
        // Each signal is per-AGV (instance property) so other AGVs'
        // lifecycle changes don't trigger this one. The controller
        // fires these at the moment the corresponding logical event
        // occurs in its process body.
        val bidStarted: AgentSignal = AgentSignal("${aName}:bidStarted")
        val workStarted: AgentSignal = AgentSignal("${aName}:workStarted")
        val workFinished: AgentSignal = AgentSignal("${aName}:workFinished")
        val bidLost: AgentSignal = AgentSignal("${aName}:bidLost")
        val chargingStarted: AgentSignal = AgentSignal("${aName}:chargingStarted")
        val chargingFinished: AgentSignal = AgentSignal("${aName}:chargingFinished")

        init {
            statechart {
                initial("Idle")
                state("Idle") {
                    onSignal(bidStarted) { transitionTo("Bidding") }
                    onSignal(chargingStarted) { transitionTo("Charging") }
                }
                state("Bidding") {
                    onSignal(workStarted) { transitionTo("Working") }
                    onSignal(bidLost) { transitionTo("Idle") }
                }
                state("Working") {
                    onSignal(workFinished) { transitionTo("Idle") }
                }
                state("Charging") {
                    onSignal(chargingFinished) { transitionTo("Idle") }
                }
            }
        }
    }

    // ── Tasks ──────────────────────────────────────────────────────────────

    /** A pallet-move work item. */
    data class PalletTask(val pickup: Cell, val dropoff: Cell, val createdAt: Double)

    // ── Tunable parameters (initialized from Defaults; setters re-validate) ──

    val numAGVs: Int = Defaults.numAGVs
    var agvVelocity: Double by positive(Defaults.agvVelocity)
    var travelStepSize: Double by positive(Defaults.travelStepSize)
    var energyPerCell: Double by positive(Defaults.energyPerCell)
    var lowBatteryThreshold: Double by probability(Defaults.lowBatteryThreshold)
    var chargeRate: Double by positive(Defaults.chargeRate)
    var bidDeadline: Double by positive(Defaults.bidDeadline)
    var loadTime: Double by nonNegative(Defaults.loadTime)
    var unloadTime: Double by nonNegative(Defaults.unloadTime)

    /** Mutable global defaults for [WarehouseAGVExample]. */
    companion object Defaults {
        /** Number of AGVs in the fleet. Must be positive. */
        var numAGVs: Int by positive(4)
        /** Travel velocity in coordinate-units per simulated time unit. Must be positive. */
        var agvVelocity: Double by positive(2.0)
        /** Position-update granularity during travel, in coordinate units. Must be positive. */
        var travelStepSize: Double by positive(0.5)
        /** Battery consumed per cell traversed. Must be positive. */
        var energyPerCell: Double by positive(0.012)
        /** Battery threshold below which a controller routes to a charger. Must be in [0, 1]. */
        var lowBatteryThreshold: Double by probability(0.25)
        /** Charge restored per simulated time unit while at a charger. Must be positive. */
        var chargeRate: Double by positive(0.05)
        /** Time the dispatcher waits for bids on each Contract-Net round. Must be positive. */
        var bidDeadline: Double by positive(1.0)
        /** Time delay during pickup. Must be non-negative. */
        var loadTime: Double by nonNegative(1.5)
        /** Time delay during dropoff. Must be non-negative. */
        var unloadTime: Double by nonNegative(1.5)
    }

    /** Mean inter-arrival time between pallet tasks. */
    var taskArrivalRV: ExponentialRV =
        ExponentialRV(3.0, streamNum = 1, streamProvider = streamProvider)

    // ── Responses ──────────────────────────────────────────────────────────

    val taskCompletionTime: Response = Response(this, "TaskCompletionTime")
    val numTasksCompleted: Counter = Counter(this, "NumTasksCompleted")
    val numChargingEvents: Counter = Counter(this, "NumChargingEvents")
    val numCFPsBroadcast: Counter = Counter(this, "NumCFPsBroadcast")
    val numCFPsUnassigned: Counter = Counter(this, "NumCFPsUnassigned")

    // ── AGV fleet construction ─────────────────────────────────────────────

    val agvs: List<AGV> = (1..numAGVs).map { i ->
        // Spread initial positions across chargers + one extra cell.
        val initCell = if (i <= chargers.size) chargers[i - 1] else Cell(0, 0)
        AGV("agv-$i", initCell = initCell)
    }

    /**
     *  Precomputed at each [initialize]: distance from each passable
     *  cell to the nearest charger. Controllers driving to a charger
     *  follow this gradient cell-by-cell rather than running a
     *  per-trip shortest-path search.
     */
    private lateinit var chargerDistanceField: Map<Cell, Double>

    // ── AGV controller (the brain) ─────────────────────────────────────────

    /**
     *  Decision-maker for one [AGV]. Listens for Contract-Net CFPs,
     *  bids when available, drives the AGV through accepted tasks,
     *  and manages charging.
     *
     *  Why split brain from body? The body is a `Resource` so it can
     *  be `seize`d (the controller seizes its own AGV during a task
     *  to model "AGV committed; not available for other work"). The
     *  brain needs to be an `Agent` because [contractNet] requires
     *  bidders to be `Agent`s (their `mailbox.from` field is typed
     *  `ProcessModel.Entity`, which `PermanentAgent`s don't satisfy).
     *
     *  The body owns the [statechart]; the brain drives it by firing
     *  the AGV's signals at the moment each logical transition would
     *  occur. State semantics are *not* enforced by the statechart;
     *  it advertises lifecycle for stats (via `collectPerformance()`
     *  on the AGV) and for diagnostics.
     */
    inner class AGVController(val agv: AGV) : Agent("brain-${agv.name}") {

        val script: KSLProcess = process(isDefaultProcess = true) {
            while (true) {
                val cfp = receiveMessageOfType<AgentMessage.Request<PalletTask>, AgentMessage>(mailbox)

                // Stale-CFP guard. Skip without bidding — the dispatcher's
                // contractNet only Rejects bidders, so a stale bid would
                // wait forever for an Accept/Reject. The age check ensures
                // we only bid on conversations whose deadline is still
                // open (and adds a half-step margin against same-time
                // ordering at the boundary).
                val age = currentTime - cfp.payload.createdAt
                if (age >= bidDeadline) continue

                // Battery / reachability gate.
                if (agv.battery < lowBatteryThreshold) continue
                val pathLen = graph.shortestPathLength(agv.currentCell, cfp.payload.pickup)
                if (!pathLen.isFinite()) continue

                // Bid: distance is the proposal payload.
                val convId = cfp.conversationId!!
                val initiatorMailbox = (cfp.from as AgentModel.Agent).mailbox
                sendMessage(
                    AgentMessage.Propose(this@AGVController, pathLen, conversationId = convId),
                    initiatorMailbox,
                )
                agv.bidStarted.fire()

                // Wait for the dispatcher's decision on this conversation.
                val decision = receiveMessage(mailbox) { msg ->
                    msg.conversationId == convId &&
                        (msg is AgentMessage.Accept || msg is AgentMessage.Reject)
                }

                if (decision is AgentMessage.Accept) {
                    agv.workStarted.fire()
                    executeTask(cfp.payload)
                    agv.workFinished.fire()

                    // Battery follow-up: if low, charge before the next CFP.
                    if (agv.battery < lowBatteryThreshold) {
                        agv.chargingStarted.fire()
                        chargeAtNearestStation()
                        agv.chargingFinished.fire()
                    }
                } else {
                    agv.bidLost.fire()
                }
            }
        }

        private suspend fun KSLProcessBuilder.executeTask(task: PalletTask) {
            val allocation = seize(agv)
            driveTo(task.pickup)
            delay(loadTime)
            driveTo(task.dropoff)
            delay(unloadTime)
            release(allocation)
            taskCompletionTime.value = currentTime - task.createdAt
            numTasksCompleted.increment()
        }

        /**
         *  Drive the AGV to [target] along the cell-graph shortest
         *  path, updating the body's [AGV.battery] (one [energyPerCell]
         *  per cell traversed) and [AGV.currentCell] on arrival.
         */
        private suspend fun KSLProcessBuilder.driveTo(target: Cell) {
            if (agv.currentCell == target) return
            val path = graph.shortestPath(agv.currentCell, target)
                ?: error("no path from ${agv.currentCell} to $target in warehouse graph")
            val waypoints = path.nodes.drop(1).map { cellCenter(it) }
            travelThrough(
                agent = agv, space = space,
                waypoints = waypoints,
                velocity = agvVelocity,
                stepSize = travelStepSize,
            )
            // path.nodes.size - 1 == number of cell transitions.
            agv.battery = (agv.battery - energyPerCell * (path.nodes.size - 1)).coerceAtLeast(0.0)
            agv.currentCell = target
        }

        /**
         *  Walk the chargerDistanceField gradient until we reach a
         *  charger cell, then take the AGV off-shift and `delay` for
         *  enough time to refill the battery.
         *
         *  Following the gradient (rather than calling shortestPath
         *  to a specific charger) is the canonical multi-source
         *  distance-field idiom: the field encodes "distance to
         *  nearest source," so descending it always reaches the
         *  *closest* source from the current cell with no extra
         *  pathfinding.
         */
        private suspend fun KSLProcessBuilder.chargeAtNearestStation() {
            val pathCells = ArrayList<Cell>()
            var cur = agv.currentCell
            var safetyCount = 0
            while (cur !in chargers) {
                val neighbors = graph.passableNeighbors(cur)
                val next = neighbors.minByOrNull { chargerDistanceField[it] ?: Double.POSITIVE_INFINITY }
                    ?: error("AGV ${agv.name} has no passable neighbors at cell $cur — graph corruption")
                pathCells.add(next)
                cur = next
                safetyCount += 1
                if (safetyCount > graph.cellCount) {
                    error("Gradient descent did not converge — chargerDistanceField is inconsistent")
                }
            }
            if (pathCells.isNotEmpty()) {
                val waypoints = pathCells.map { cellCenter(it) }
                travelThrough(
                    agent = agv, space = space,
                    waypoints = waypoints,
                    velocity = agvVelocity,
                    stepSize = travelStepSize,
                )
                agv.battery = (agv.battery - energyPerCell * pathCells.size).coerceAtLeast(0.0)
                agv.currentCell = pathCells.last()
            }

            // Recharge.
            agv.goOffShift()
            val deficit = 1.0 - agv.battery
            val chargeTime = deficit / chargeRate
            delay(chargeTime)
            agv.battery = 1.0
            agv.goOnShift()
            numChargingEvents.increment()
        }
    }

    val controllers: List<AGVController> = agvs.map { AGVController(it) }

    // ── Dispatcher ─────────────────────────────────────────────────────────

    /**
     *  Receives tasks from the generator, runs one Contract-Net round
     *  per task, and discards (without retry) any task that gets no
     *  bids. In a production model, a no-bid task would be queued and
     *  retried; we omit that for clarity.
     */
    inner class Dispatcher : Agent("dispatcher") {
        val script: KSLProcess = process(isDefaultProcess = true) {
            while (true) {
                val taskMsg = receiveMessageOfType<AgentMessage.Inform<PalletTask>, AgentMessage>(mailbox)
                numCFPsBroadcast.increment()
                val outcome = contractNet<PalletTask, Double>(
                    bidders = controllers,
                    callForProposals = taskMsg.payload,
                    deadline = bidDeadline,
                    selectBest = { proposals -> proposals.minByOrNull { it.proposal } },
                )
                if (outcome == null) numCFPsUnassigned.increment()
            }
        }
    }

    val dispatcher: Dispatcher = Dispatcher()

    // ── Task generator ─────────────────────────────────────────────────────

    inner class TaskGenerator : Agent("taskGen") {
        val script: KSLProcess = process(isDefaultProcess = true) {
            val rng = defaultRNStream
            while (true) {
                delay(taskArrivalRV.value)
                val pickup = randomPassableCell(rng)
                val dropoff = randomPassableCell(rng, avoid = pickup)
                val task = PalletTask(pickup, dropoff, currentTime)
                sendMessage(
                    AgentMessage.Inform(this@TaskGenerator, task),
                    dispatcher.mailbox,
                )
            }
        }

        private fun randomPassableCell(rng: RNStreamIfc, avoid: Cell? = null): Cell {
            // The warehouse has plenty of passable cells; rejection
            // sampling terminates fast.
            repeat(10_000) {
                val c = rng.randInt(0, gridSize - 1)
                val r = rng.randInt(0, gridSize - 1)
                val cell = Cell(c, r)
                if (cell != avoid && graph.isPassable(cell)) return cell
            }
            error("could not sample a passable cell after 10000 tries")
        }
    }

    val taskGen: TaskGenerator = TaskGenerator()

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun initialize() {
        super.initialize()

        // Compute the charger distance field once per replication.
        chargerDistanceField = graph.distanceField(chargers.toSet())

        // Reset AGV positions & battery for multi-replication runs.
        for ((i, agv) in agvs.withIndex()) {
            val initCell = if (i < chargers.size) chargers[i] else Cell(0, 0)
            agv.currentCell = initCell
            agv.battery = 1.0
            space.placeAt(agv, cellCenter(initCell))
        }

        activate(taskGen.script)
        activate(dispatcher.script)
        for (c in controllers) activate(c.script)
    }
}

fun main() {
    val model = Model("WarehouseAGVExample")
    val sys = WarehouseAGVExample(model, "warehouse")
    // Enable per-state TWResponses for each AGV (TimeInIdle,
    // TimeInBidding, TimeInWorking, TimeInCharging).
    for (agv in sys.agvs) agv.collectPerformance()
    model.lengthOfReplication = 1000.0
    model.numberOfReplications = 1
    model.simulate()
    model.print()
    println("Tasks completed: ${sys.numTasksCompleted.value}")
    println("Charging events: ${sys.numChargingEvents.value}")
}
