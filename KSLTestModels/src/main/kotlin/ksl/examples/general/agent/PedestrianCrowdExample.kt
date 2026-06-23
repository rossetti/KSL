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
import ksl.modeling.agent.AgentModel
import ksl.modeling.agent.Cell
import ksl.modeling.agent.ContinuousProjection
import ksl.modeling.agent.Dynamics
import ksl.modeling.agent.FlowField
import ksl.modeling.agent.GridGraph
import ksl.modeling.agent.MovementRule
import ksl.modeling.agent.Point2D
import ksl.modeling.agent.desiredVelocity
import ksl.modeling.agent.nonNegative
import ksl.modeling.agent.peerRepulsion
import ksl.modeling.agent.positive
import ksl.modeling.agent.wallRepulsion
import ksl.modeling.entity.KSLProcess
import ksl.modeling.variable.Counter
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.Model
import ksl.simulation.ModelElement
import kotlin.math.exp

/**
 *  Crowd evacuation example using **social-force dynamics** (Helbing,
 *  Farkas & Vicsek 2000, *Nature* 407: 487–490) on top of a
 *  precomputed [FlowField]. This is the canonical "two-scale"
 *  pedestrian model: macro navigation comes from the flow field
 *  (each cell knows the direction toward the nearest exit); micro
 *  motion comes from summed forces (desired velocity toward the
 *  flow direction, exponential repulsion from peers, exponential
 *  repulsion from walls).
 *
 *  Run for long enough at high enough density and you'll see textbook
 *  crowd phenomena emerge with no code that explicitly implements
 *  them:
 *
 *   - **Arching / clogging** at the doorway as density rises.
 *   - **Lane formation** in any counter-flow you set up by adding a
 *     second flow field.
 *   - **Stop-and-go waves** at sufficient density along the path.
 *   - The "**faster is slower**" effect: increasing [desiredSpeed]
 *     past a threshold *reduces* throughput because the arch jams
 *     tighter.
 *
 *  ## Layout
 *
 *  25×25 grid (cellSize = 1.0, so 25 m × 25 m world). A vertical
 *  wall on column 15 separates a left "room" from a right
 *  "corridor"; a three-cell-wide doorway at rows 11–13 connects
 *  them. Exits are the three cells immediately east of the doorway.
 *
 *  ```
 *  col 0          15           24
 *      ┌──────────┐ ┌────────────┐
 *      │          │ │            │ row 0
 *      │          │ │            │
 *      │          │ │            │
 *      │   room   │ │            │
 *      │  (peds)  │ │            │
 *      │          │ │            │
 *      │          │  doorway     │ row 11  EXIT row 11
 *      │          │  (3 cells)   │ row 12  EXIT row 12
 *      │          │              │ row 13  EXIT row 13
 *      │          │ │            │
 *      │          │ │            │
 *      │          │ │            │ row 24
 *      └──────────┘ └────────────┘
 *  ```
 *
 *  ## What's exercised
 *
 *  | Capability | Source phase | Role |
 *  |---|---|---|
 *  | `GridGraph` with `block` | 3.6 | walls between room and corridor |
 *  | `FlowField` | 5.1 | per-cell "go toward exit" direction |
 *  | `ContinuousProjection` + spatial hash | 3 + 3.3 | continuous positions + O(local) peer queries |
 *  | `space.within(p, r)` per step | 3.3 | the workload the hash was built for |
 *  | `Context` / `Context.remove` | 3 | population membership, leave on evac |
 *  | Pattern C per-`dt` micro-step loop | (§11.2) | the integration loop |
 *  | `Response` / `Counter` / `TWResponse` | core | evacuation stats |
 *
 *  ## Reading order
 *
 *  Start with [Pedestrian.script] — the per-step loop is short and
 *  shows the whole shape. Then read the three force helpers
 *  ([desiredForce], [peerForce], [wallForce]), each of which is a
 *  textbook Helbing term. The flow field, graph, and population
 *  setup happen in [initialize] and are otherwise unsurprising.
 *
 *  Forces are written *inline*, deliberately — Phase 5.4 will lift
 *  them into a reusable `Force<A>` library once a second example
 *  (flocking) validates the abstraction shape.
 */
class PedestrianCrowdExample(parent: ModelElement, name: String? = null) :
    AgentModel(parent, name) {

    // ── World geometry ──────────────────────────────────────────────────────

    val gridSize: Int = 25
    val cellSize: Double = 1.0
    val worldSize: Double = gridSize * cellSize

    /** Wall column separating room from corridor. */
    private val wallCol: Int = 15

    /** Doorway: rows that remain passable in [wallCol]. */
    private val doorwayRows: IntRange = 11..13

    val graph: GridGraph = GridGraph(gridSize, gridSize, movementRule = MovementRule.MOORE)

    /** Exit cells: the row immediately east of the doorway. */
    val exits: Set<Cell> = doorwayRows.map { Cell(wallCol + 1, it) }.toSet()

    init {
        // Build the wall, leaving the doorway open.
        for (row in 0 until gridSize) {
            if (row in doorwayRows) continue
            graph.block(Cell(wallCol, row))
        }
    }

    // ── Continuous space ────────────────────────────────────────────────────

    private val crowd: Context<AgentLike> = Context("pedestrians")
    val space: ContinuousProjection<AgentLike> = ContinuousProjection(
        crowd, xRange = 0.0..worldSize, yRange = 0.0..worldSize,
    )

    /** Built per replication in [initialize]; sources = [exits]. */
    private lateinit var field: FlowField

    // ── Tunable parameters (initialized from Defaults; setters re-validate) ──

    var population: Int by positive(Defaults.population)
    var dt: Double by positive(Defaults.dt)
    var desiredSpeed: Double by positive(Defaults.desiredSpeed)
    var maxSpeed: Double by positive(Defaults.maxSpeed)
    var mass: Double by positive(Defaults.mass)
    var tau: Double by positive(Defaults.tau)
    var pedRadius: Double by positive(Defaults.pedRadius)
    var aPed: Double by nonNegative(Defaults.aPed)
    var bPed: Double by positive(Defaults.bPed)
    var aWall: Double by nonNegative(Defaults.aWall)
    var bWall: Double by positive(Defaults.bWall)
    var neighborRadius: Double by positive(Defaults.neighborRadius)
    var wallScanRadius: Double by positive(Defaults.wallScanRadius)
    var minDistance: Double by positive(Defaults.minDistance)

    /**
     *  Mutable global defaults for [PedestrianCrowdExample]. Override
     *  any value here once at model setup to change every subsequent
     *  instance's starting point. Each instance still owns mutable
     *  copies (the `var`s above) so per-instance tuning continues to
     *  work without contaminating other instances. All members validate
     *  via the property delegates from `PropertyValidation.kt` and
     *  throw `IllegalArgumentException` on invalid assignment.
     *
     *  Force-constant values follow Helbing, Farkas & Vicsek (2000)
     *  *Nature* 407: 487–490, with [aWall] doubled to keep walls
     *  dominant over peer repulsion in dense corners.
     */
    companion object Defaults {
        // Population
        /** Number of pedestrians to spawn per replication. Must be positive. */
        var population: Int by positive(50)

        // Integration
        /** Integration time step, s. Must be positive. */
        var dt: Double by positive(0.05)

        // Pedestrian physics
        /** Preferred ("comfortable") walking speed, m/s. Must be positive. */
        var desiredSpeed: Double by positive(1.3)
        /** Hard upper bound on velocity magnitude, m/s. Must be positive. */
        var maxSpeed: Double by positive(2.0)
        /** Pedestrian mass, kg. Must be positive. */
        var mass: Double by positive(80.0)
        /** Helbing relaxation time toward desired velocity, s. Must be positive. */
        var tau: Double by positive(0.5)
        /** Pedestrian radius (extent), m. Must be positive. */
        var pedRadius: Double by positive(0.3)
        /** Lower clamp on `d` in force expressions, m. Must be positive (else forces blow up at contact). */
        var minDistance: Double by positive(0.05)

        // Force constants (Helbing 2000 Nature). Zero is allowed (turns the force off).
        /** Peer-peer repulsion magnitude at contact, N. Must be non-negative. */
        var aPed: Double by nonNegative(2000.0)
        /** Peer-peer repulsion decay length, m. Must be positive. */
        var bPed: Double by positive(0.08)
        /** Wall repulsion magnitude at contact, N. Must be non-negative. */
        var aWall: Double by nonNegative(4000.0)
        /** Wall repulsion decay length, m. Must be positive. */
        var bWall: Double by positive(0.08)

        // Query radii
        /** Radius of the peer-query disk per step, m. Must be positive. */
        var neighborRadius: Double by positive(2.0)
        /** Distance beyond which wall cells stop contributing force, m. Must be positive. */
        var wallScanRadius: Double by positive(1.5)
    }

    // ── Responses ──────────────────────────────────────────────────────────

    val evacuationTime: Response = Response(this, "EvacuationTime")
    val numEvacuated: Counter = Counter(this, "NumEvacuated")
    val populationInRoom: TWResponse = TWResponse(this, "PopulationInRoom")

    // ── Pedestrian ──────────────────────────────────────────────────────────

    private var nextId: Int = 0

    inner class Pedestrian : Agent("ped-${++nextId}") {
        val spawnedAt: Double = currentTime

        val script: KSLProcess = process(isDefaultProcess = true) {
            while (true) {
                val pos = space.positionOf(this@Pedestrian) ?: break
                if (field.arrivedAt(pos)) {
                    evacuationTime.value = currentTime - spawnedAt
                    numEvacuated.increment()
                    populationInRoom.decrement()
                    dynamics.untrack(this@Pedestrian)
                    crowd.remove(this@Pedestrian)
                    break
                }

                // One Euler step from the composed forces.
                val (vNew, candidate) = dynamics.step(this@Pedestrian, dt)

                if (isValidPosition(candidate)) {
                    dynamics.setVelocity(this@Pedestrian, vNew)
                    space.moveTo(this@Pedestrian, candidate)
                } else {
                    // Blocked — kill velocity so forces have to push us
                    // back out next step rather than accumulating.
                    dynamics.setVelocity(this@Pedestrian, Point2D.ORIGIN)
                }
                delay(dt)
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     *  True if [pt] is inside the world and in a passable cell.
     *  Used to reject motion steps that would penetrate walls.
     */
    private fun isValidPosition(pt: Point2D): Boolean {
        if (pt.x < 0.0 || pt.x >= worldSize) return false
        if (pt.y < 0.0 || pt.y >= worldSize) return false
        return graph.isPassable(field.cellOf(pt))
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    val pedestrians: MutableList<Pedestrian> = mutableListOf()

    /** Built fresh in [initialize] from the current tunable values. */
    private lateinit var dynamics: Dynamics<AgentLike>

    override fun initialize() {
        super.initialize()

        // Build the flow field for this replication. (Cheap — one Dijkstra.)
        field = FlowField(graph, exits, cellSize = cellSize)

        // Build the dynamics and compose forces. Each force is a factory
        // call from `Forces.kt`; the falloff lambdas close over the
        // pedestrian-physics tunables, so changes between replications
        // are picked up by the next `initialize()`.
        dynamics = Dynamics(space, mass = { mass }, maxSpeed = maxSpeed)
        dynamics.addForce(desiredVelocity(speed = desiredSpeed, tau = tau) { agent, dyn ->
            val p = dyn.space.positionOf(agent) ?: return@desiredVelocity Point2D.ORIGIN
            field.directionAt(p)
        })
        dynamics.addForce(peerRepulsion(radius = neighborRadius, minDistance = minDistance) { d ->
            aPed * exp((2 * pedRadius - d) / bPed)
        })
        dynamics.addForce(
            wallRepulsion(
                graph = graph,
                cellSize = cellSize,
                origin = Point2D.ORIGIN,
                scanRadius = wallScanRadius,
                minDistance = minDistance,
            ) { d ->
                aWall * exp((pedRadius - d) / bWall)
            },
        )

        // (Re)populate. Place each pedestrian in a unique passable cell
        // in the room half (columns 0..wallCol-1), at the cell center
        // with small jitter to break symmetry.
        pedestrians.clear()
        val rng = defaultRNStream
        val claimed = HashSet<Cell>()
        var attempts = 0
        while (pedestrians.size < population) {
            attempts += 1
            if (attempts > 50_000) error("could not place full population — too dense?")
            val col = rng.randInt(1, wallCol - 1)
            val row = rng.randInt(1, gridSize - 2)
            val cell = Cell(col, row)
            if (cell in claimed) continue
            if (!graph.isPassable(cell)) continue
            claimed.add(cell)

            val center = field.centerOf(cell)
            // Small jitter so pedestrians at adjacent cells don't share
            // an x or y coordinate exactly (avoids forces being exactly
            // axis-aligned, which can cause stuck pairs).
            val jx = (rng.randU01() - 0.5) * cellSize * 0.4
            val jy = (rng.randU01() - 0.5) * cellSize * 0.4
            val start = Point2D(center.x + jx, center.y + jy)

            val p = Pedestrian()
            crowd.add(p)
            space.placeAt(p, start)
            dynamics.setVelocity(p, Point2D.ORIGIN)
            populationInRoom.increment()
            activate(p.script)
            pedestrians.add(p)
        }
    }
}

fun main() {
    val model = Model("PedestrianCrowdExample")
    val sys = PedestrianCrowdExample(model, "crowd")
    model.lengthOfReplication = 300.0
    model.numberOfReplications = 1
    model.simulate()
    model.print()
    println("Evacuated: ${sys.numEvacuated.value} of ${sys.population}")
}
