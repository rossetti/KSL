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
import ksl.modeling.agent.ContinuousVolume
import ksl.modeling.agent.Point3D
import ksl.modeling.agent.Voxel
import ksl.modeling.agent.VoxelGraph
import ksl.modeling.agent.VoxelHeuristics
import ksl.modeling.agent.VoxelMovementRule
import ksl.modeling.agent.nonNegative
import ksl.modeling.agent.positive
import ksl.modeling.agent.probability
import ksl.modeling.agent.receiveMessageOfType
import ksl.modeling.agent.sendMessage
import ksl.modeling.agent.travelThrough3D
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.variable.Counter
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

/**
 *  Phase 6.6 worked example — autonomous drone delivery over a 3D
 *  airspace with no-fly zones, demonstrating the full agent-layer 3D
 *  stack from Phase 6.1–6.5.
 *
 *  ## Scope
 *
 *  "Demonstrably plausible drone simulation," per §13.6 Q6. Not a
 *  research-grade UAS Traffic Management testbed — no Detect-and-
 *  Avoid, no FAA Part 107 rules, no continuous-conflict resolution.
 *  What's modeled:
 *
 *   - 3D airspace with buildings as blocked-voxel no-fly zones.
 *   - Fleet of drones flying between a depot and randomly-chosen
 *     delivery points.
 *   - Per-drone battery management: drains with each voxel of travel,
 *     recharges at the depot when below a threshold.
 *   - Direct dispatch by a `Dispatcher` agent: pick the first idle
 *     drone with sufficient battery; queue tasks otherwise.
 *   - 3D pathfinding around no-fly zones via `VoxelGraph.shortestPath`
 *     with the `VoxelHeuristics.OCTILE` heuristic.
 *   - Continuous-position visualization at sub-voxel granularity via
 *     `travelThrough3D` over the cell-center waypoints of each
 *     planned path.
 *
 *  ## What's exercised
 *
 *  | Capability | Source phase | Role in this model |
 *  |---|---|---|
 *  | `Voxel` + `Point3D` | 6.1 | airspace coordinates |
 *  | `ContinuousVolume<AgentLike>` | 6.2 | continuous drone positions |
 *  | `VoxelProjection` | 6.3 | tracks which voxel each drone is "in" |
 *  | `VoxelGraph` + `shortestPath` + `VoxelHeuristics.OCTILE` | 6.3 | route planning around no-fly zones |
 *  | `travelThrough3D` | 6.4 | continuous-time motion along the planned path |
 *  | `AgentMessage` / mailbox | 1a | order dispatch |
 *  | `Defaults` companion with property delegates | core convention | all tunables validated |
 *
 *  Not exercised here (would suit extensions): `FlowField3D`,
 *  `Dynamics3D` + 3D forces, Contract-Net for bidding. The
 *  walkthrough (`design/drone-delivery-walkthrough.md`) discusses
 *  each as a natural next step.
 *
 *  ## Topology
 *
 *  - **Airspace**: 30 × 30 × 10 voxels at 10 m per voxel (300 m × 300 m
 *    × 100 m). Z-axis is altitude.
 *  - **Buildings** (blocked no-fly zones):
 *    - A wide 5×5 building from layer 0 up to layer 7 (~80 m tall),
 *      centered at columns 8–12, rows 8–12. Drones can fly over it
 *      at layer 8 or above.
 *    - A thinner tower 3×3 wide from layer 0 to layer 8, at columns
 *      18–20, rows 18–20.
 *  - **Depot**: ground-level voxel (1, 1, 0). Drones launch from
 *    here, deliver, and return for charging.
 *  - **Delivery points**: three ground-level voxels in different
 *    corners of the airspace.
 *
 *  ## Flow
 *
 *  1. `OrderGenerator` produces delivery orders at exponential
 *     inter-arrival times, each targeting a random delivery point.
 *  2. `Dispatcher` receives each order; picks the first drone that
 *     is idle and has enough battery for a round trip; sends the
 *     order via mailbox.
 *  3. Each `Drone` runs a simple wait-deliver loop:
 *     - `receiveMessageOfType<Inform<Order>>` blocks until an order
 *       arrives.
 *     - Plan a 3D path to the delivery point, fly via
 *       `travelThrough3D`.
 *     - Unload (delay).
 *     - Plan a path back to depot, fly home.
 *     - Battery drains by `energyPerVoxel` per voxel traversed
 *       throughout the trip.
 *     - If battery < `lowBatteryThreshold` after the trip, recharge
 *       at the depot before signaling idle again.
 *
 *  ## Tunables and validation
 *
 *  All tunables in the [Defaults] companion use property delegates
 *  from `PropertyValidation.kt` and throw `IllegalArgumentException`
 *  on invalid assignment. Per-instance `var`s mirror the same
 *  constraints, so `sys.droneVelocity = -1.0` also throws.
 */
class DroneDeliveryExample(parent: ModelElement, name: String? = null) :
    AgentModel(parent, name) {

    // ── Tunable parameters ──────────────────────────────────────────────────

    var numDrones: Int by positive(Defaults.numDrones)
    var droneVelocity: Double by positive(Defaults.droneVelocity)
    var travelStepSize: Double by positive(Defaults.travelStepSize)
    var energyPerVoxel: Double by positive(Defaults.energyPerVoxel)
    var lowBatteryThreshold: Double by probability(Defaults.lowBatteryThreshold)

    /**
     *  Minimum battery to accept a new delivery. Set above
     *  `lowBatteryThreshold` so the dispatcher doesn't send a drone
     *  that would immediately have to abort and recharge.
     */
    var minDispatchBattery: Double by probability(Defaults.minDispatchBattery)
    var chargeRate: Double by positive(Defaults.chargeRate)
    var unloadTime: Double by nonNegative(Defaults.unloadTime)
    var dispatcherPollInterval: Double by positive(Defaults.dispatcherPollInterval)
    var orderArrivalRV: ExponentialRV =
        ExponentialRV(Defaults.orderInterarrivalMean, streamNum = 1, streamProvider = streamProvider)

    /** Mutable global defaults for [DroneDeliveryExample]. */
    companion object Defaults {
        var numDrones: Int by positive(3)
        /** Drone cruise velocity (coord units / time, i.e., m/s). Must be positive. */
        var droneVelocity: Double by positive(15.0)
        /** Position-update granularity during travel, in coord units. Must be positive. */
        var travelStepSize: Double by positive(2.5)
        /** Battery consumed per voxel traversed. Must be positive. */
        var energyPerVoxel: Double by positive(0.012)
        /** Battery threshold below which a drone recharges before its next assignment. Must be in [0, 1]. */
        var lowBatteryThreshold: Double by probability(0.30)
        /** Minimum battery the dispatcher requires before assigning a delivery. Must be in [0, 1]. */
        var minDispatchBattery: Double by probability(0.40)
        /** Charge restored per simulated time unit at the depot. Must be positive. */
        var chargeRate: Double by positive(0.05)
        /** Unload delay at the delivery point. Must be non-negative. */
        var unloadTime: Double by nonNegative(5.0)
        /** Dispatcher poll interval while waiting for an idle drone. Must be positive. */
        var dispatcherPollInterval: Double by positive(1.0)
        /** Mean inter-arrival time between delivery orders. Must be positive. */
        var orderInterarrivalMean: Double by positive(20.0)
    }

    // ── World geometry ──────────────────────────────────────────────────────

    val voxelSize: Double = 10.0      // 10 m per voxel
    val gridCols: Int = 30            // 300 m east–west
    val gridRows: Int = 30            // 300 m north–south
    val gridLayers: Int = 10          // 100 m altitude
    val worldXSize: Double = gridCols * voxelSize
    val worldYSize: Double = gridRows * voxelSize
    val worldZSize: Double = gridLayers * voxelSize

    /** The 3D navigation lattice. Blocked voxels are no-fly zones. */
    val airspace: VoxelGraph = VoxelGraph(
        gridCols, gridRows, gridLayers,
        movementRule = VoxelMovementRule.MOORE_26,
    )

    init {
        // No-fly zone #1: a wide 5×5 building 8 voxels tall (80 m).
        // Drones can fly over it from layer 8 or higher.
        for (col in 8..12) for (row in 8..12) for (layer in 0..7) {
            airspace.block(Voxel(col, row, layer))
        }
        // No-fly zone #2: a 3×3 tower 9 voxels tall (90 m).
        for (col in 18..20) for (row in 18..20) for (layer in 0..8) {
            airspace.block(Voxel(col, row, layer))
        }
    }

    /** Continuous-space anchor and helpers. */
    val origin: Point3D = Point3D.ORIGIN
    fun voxelCenter(v: Voxel): Point3D = Point3D(
        origin.x + (v.col + 0.5) * voxelSize,
        origin.y + (v.row + 0.5) * voxelSize,
        origin.z + (v.layer + 0.5) * voxelSize,
    )

    // ── Continuous-space projection ────────────────────────────────────────

    private val sky: Context<AgentLike> = Context("airspace")
    val space: ContinuousVolume<AgentLike> = ContinuousVolume(
        sky,
        xRange = 0.0..worldXSize,
        yRange = 0.0..worldYSize,
        zRange = 0.0..worldZSize,
    )

    // ── Depot and delivery points ──────────────────────────────────────────

    val depot: Voxel = Voxel(1, 1, 0)
    val deliveryPoints: List<Voxel> = listOf(
        Voxel(25, 5, 0),    // east edge, low row
        Voxel(5, 25, 0),    // low col, north edge
        Voxel(28, 28, 0),   // far corner
        Voxel(15, 28, 0),   // middle of north edge
    )

    // ── Order type ─────────────────────────────────────────────────────────

    data class Order(val deliveryVoxel: Voxel, val createdAt: Double)

    // ── Responses ──────────────────────────────────────────────────────────

    val numDeliveries: Counter = Counter(this, "NumDeliveries")
    val numCharges: Counter = Counter(this, "NumCharges")
    val deliveryTime: Response = Response(this, "DeliveryTime")
    val numIdleDrones: TWResponse = TWResponse(this, "NumIdleDrones")

    // ── Drone ──────────────────────────────────────────────────────────────

    /**
     *  A drone. Holds its own state (battery, current voxel, idle
     *  flag) and runs a simple wait-deliver loop in its process body.
     *
     *  Drones are plain [AgentModel.Agent]s — they don't extend
     *  `MovableAgentResource` because (a) no entity outside the
     *  drone ever needs `seize` semantics on it and (b)
     *  `MovableAgentResource3D` doesn't exist yet (was deferred to
     *  Phase 7+). If a future model needs drone seize/release (e.g.,
     *  a maintenance worker taking a drone offline), that's the
     *  signal to build the 3D resource type.
     */
    inner class Drone(aName: String) : Agent(aName) {
        var battery: Double = 1.0
            internal set
        var currentVoxel: Voxel = depot
            internal set
        var isIdle: Boolean = true
            internal set

        val script: KSLProcess = process(isDefaultProcess = true) {
            while (true) {
                val msg = receiveMessageOfType<AgentMessage.Inform<Order>, AgentMessage>(mailbox)
                isIdle = false
                numIdleDrones.decrement()
                executeDelivery(msg.payload)
                // Recharge if low; only signal idle once truly ready.
                if (battery < lowBatteryThreshold) {
                    rechargeAtDepot()
                }
                isIdle = true
                numIdleDrones.increment()
            }
        }

        private suspend fun KSLProcessBuilder.executeDelivery(order: Order) {
            // Out to the delivery point.
            flyTo(order.deliveryVoxel)
            delay(unloadTime)
            // Back to depot.
            flyTo(depot)
            // Record stats.
            numDeliveries.increment()
            deliveryTime.value = currentTime - order.createdAt
        }

        /**
         *  Fly to [target] along a 3D shortest path (A* with octile
         *  heuristic). Drains battery by [energyPerVoxel] per voxel
         *  traversed; if there's no path, throws (the caller should
         *  ensure a path exists before assigning).
         */
        private suspend fun KSLProcessBuilder.flyTo(target: Voxel) {
            if (currentVoxel == target) return
            val path = airspace.shortestPath(currentVoxel, target, VoxelHeuristics.OCTILE)
                ?: error("no 3D path from $currentVoxel to $target in airspace")
            val waypoints = path.nodes.drop(1).map { voxelCenter(it) }
            travelThrough3D(
                agent = this@Drone, space = space,
                waypoints = waypoints,
                velocity = droneVelocity,
                stepSize = travelStepSize,
            )
            battery = (battery - energyPerVoxel * (path.nodes.size - 1)).coerceAtLeast(0.0)
            currentVoxel = target
        }

        private suspend fun KSLProcessBuilder.rechargeAtDepot() {
            if (currentVoxel != depot) flyTo(depot)
            val deficit = 1.0 - battery
            val chargeTime = deficit / chargeRate
            delay(chargeTime)
            battery = 1.0
            numCharges.increment()
        }
    }

    val drones: List<Drone> = (1..Defaults.numDrones).map { Drone("drone-$it") }

    // ── Dispatcher ─────────────────────────────────────────────────────────

    /**
     *  Receives delivery orders, finds an idle drone with enough
     *  battery for the round trip, and forwards the order to the
     *  drone's mailbox. If no drone is ready, polls every
     *  [dispatcherPollInterval] until one becomes available.
     */
    inner class Dispatcher : Agent("dispatcher") {
        val script: KSLProcess = process(isDefaultProcess = true) {
            while (true) {
                val taskMsg = receiveMessageOfType<AgentMessage.Inform<Order>, AgentMessage>(mailbox)
                // Wait until some drone is idle with enough battery.
                while (true) {
                    val ready = drones.firstOrNull {
                        it.isIdle && it.battery >= minDispatchBattery
                    }
                    if (ready != null) {
                        sendMessage(taskMsg, ready.mailbox)
                        break
                    }
                    delay(dispatcherPollInterval)
                }
            }
        }
    }

    val dispatcher: Dispatcher = Dispatcher()

    // ── Order generator ────────────────────────────────────────────────────

    inner class OrderGenerator : Agent("orderGen") {
        val script: KSLProcess = process(isDefaultProcess = true) {
            val rng = defaultRNStream
            while (true) {
                delay(orderArrivalRV.value)
                val targetIndex = rng.randInt(0, deliveryPoints.size - 1)
                val order = Order(deliveryPoints[targetIndex], currentTime)
                sendMessage(
                    AgentMessage.Inform(this@OrderGenerator, order),
                    dispatcher.mailbox,
                )
            }
        }
    }

    val orderGen: OrderGenerator = OrderGenerator()

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun initialize() {
        super.initialize()
        // Place each drone at the depot.
        for (drone in drones) {
            sky.add(drone)
            space.placeAt(drone, voxelCenter(depot))
            drone.currentVoxel = depot
            drone.battery = 1.0
            drone.isIdle = true
            activate(drone.script)
            numIdleDrones.increment()
        }
        activate(dispatcher.script)
        activate(orderGen.script)
    }
}

fun main() {
    val model = Model("DroneDeliveryExample")
    val sys = DroneDeliveryExample(model, "delivery")
    model.lengthOfReplication = 1000.0
    model.numberOfReplications = 1
    model.simulate()
    model.print()
    println("Deliveries: ${sys.numDeliveries.value}")
    println("Recharges: ${sys.numCharges.value}")
}
