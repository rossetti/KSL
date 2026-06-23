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
import ksl.modeling.agent.ContinuousProjection
import ksl.modeling.agent.MovableAgentResource
import ksl.modeling.agent.NetworkProjection
import ksl.modeling.agent.Point2D
import ksl.modeling.agent.nonNegative
import ksl.modeling.agent.positive
import ksl.modeling.agent.sendMessage
import ksl.modeling.agent.travelThrough
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

/**
 *  A worked example combining every piece of Phase 4 (and most of
 *  the preceding agent-layer work) in a single coherent model:
 *  autonomous delivery agents on a road network.
 *
 *  ## What's exercised
 *
 *  | Capability | Source phase | Role in this model |
 *  |---|---|---|
 *  | `Context<A>` | 3 | Two contexts: intersections, trucks |
 *  | `ContinuousProjection<AgentLike>` | 3 | Truck positions in 2D space |
 *  | `NetworkProjection<Intersection>` | 3.2 | Road graph with weighted edges |
 *  | `NetworkProjection.shortestPath` | 3.5 | Routing trucks through the network |
 *  | `MovableAgentResource` | 4.4 | Trucks: seizable, positioned, drivable |
 *  | `travelThrough(waypoints)` | 4.2 | Path-following over a sequence of intersections |
 *  | Runtime `Agent` construction | 2.5 | Couriers spawned per-order at runtime |
 *  | `PermanentAgent` + `Statechart` | 2 + 1b | Dispatcher reacts to order arrivals |
 *  | Message passing via mailbox | 1a | Dispatcher receives orders from generator |
 *
 *  ## Topology
 *
 *  Six intersections at fixed positions in a 100×100 area:
 *  ```
 *  A(10,10) ────── B(50,10) ─────── C(90,10)
 *     │     ╲         │                │
 *     │      ╲        │                │
 *     │       ╲       │                │
 *     │        ╲      │                │
 *  D(10,50) ─── E(50,50) ─────── F(90,50)
 *                 [depot]
 *  ```
 *
 *  Roads (undirected, weight = Euclidean distance between endpoints):
 *  A-B, B-C, A-D, D-E, E-F, B-E, C-F, plus an A-E diagonal shortcut.
 *  Trucks based at E (depot). Pickups and dropoffs at any
 *  intersection.
 *
 *  ## Flow
 *
 *  1. An `OrderGenerator` agent creates `Order(pickup, dropoff)`
 *     events at exponential inter-arrival times and sends each one
 *     to the Dispatcher via mailbox.
 *  2. The `Dispatcher` (a `PermanentAgent` with a statechart) reacts
 *     to each incoming order by spawning a `Courier` agent at
 *     runtime — possible because Phase 2.5 lifted the
 *     ModelElement-at-construction restriction for transient Agents.
 *  3. Each `Courier` runs an end-to-end delivery process:
 *     - Pick a truck (closest idle, else closest period).
 *     - Seize it.
 *     - Compute shortest path through the road network from the
 *       truck's current intersection to the pickup; drive the truck
 *       along the path.
 *     - Delay (loading).
 *     - Drive to the dropoff.
 *     - Delay (unloading).
 *     - Drive back to depot.
 *     - Release the truck.
 *     - Record delivery time.
 *
 *  Three trucks; capacity 1 each; queueing happens naturally via
 *  `seize` when all trucks are busy.
 *
 *  ## What this demonstrates that the earlier examples don't
 *
 *  - **Composition** of all four spatial-related abstractions
 *    (continuous projection, network projection, movable agent
 *    resource, travel primitive) in one coherent model.
 *  - **Per-order runtime agent creation** at scale — every Order
 *    spawns a Courier. With Phase 2.5's POJO mailbox, this is the
 *    natural pattern.
 *  - **Mixed graph and continuous geometry**: routing decisions
 *    use the graph (`shortestPath`); the actual movement happens in
 *    continuous space (`travelThrough`). This is the canonical
 *    structure of real-world transportation simulation.
 *  - **Resource queueing on top of movement**: trucks are seizable
 *    capacity-1 resources, so when all three are busy a fourth
 *    courier waits in the truck's request queue.
 */
class AutonomousDeliveryExample(parent: ModelElement, name: String? = null) :
    AgentModel(parent, name) {

    // ── Road network: intersections + edges ─────────────────────────────────

    /** A named intersection with a fixed 2D position. */
    inner class Intersection(aName: String, val position: Point2D) : PermanentAgent(aName)

    private val intersectionContext: Context<Intersection> = Context("intersections")
    val roads: NetworkProjection<Intersection> =
        NetworkProjection(intersectionContext, directed = false)

    val a = Intersection("A", Point2D(10.0, 10.0))
    val b = Intersection("B", Point2D(50.0, 10.0))
    val c = Intersection("C", Point2D(90.0, 10.0))
    val d = Intersection("D", Point2D(10.0, 50.0))
    val e = Intersection("E", Point2D(50.0, 50.0))  // depot
    val f = Intersection("F", Point2D(90.0, 50.0))

    val intersections: List<Intersection> = listOf(a, b, c, d, e, f)

    /** Depot intersection — where trucks start and return after each delivery. */
    val depot: Intersection = e

    init {
        for (i in intersections) intersectionContext.add(i)

        fun connect(i: Intersection, j: Intersection) {
            roads.connect(i, j, weight = i.position.distanceTo(j.position))
        }
        // Grid-like backbone
        connect(a, b); connect(b, c)
        connect(a, d); connect(d, e); connect(e, f)
        connect(b, e); connect(c, f)
        // Diagonal shortcut
        connect(a, e)
    }

    // ── Truck fleet ─────────────────────────────────────────────────────────

    private val truckContext: Context<AgentLike> = Context("truck-positions")
    val space: ContinuousProjection<AgentLike> = ContinuousProjection(
        truckContext, xRange = 0.0..100.0, yRange = 0.0..100.0,
    )

    /** A delivery truck — seizable, position-tracked, with a remembered current intersection. */
    inner class Truck(aName: String) : MovableAgentResource(
        agentModel = this@AutonomousDeliveryExample,
        space = space,
        initPosition = depot.position,
        name = aName,
    ) {
        /**
         *  The intersection at which this truck currently sits (or
         *  was last at). Updated by the courier driving the truck.
         *  Trucks are only between intersections during a
         *  `travelThrough` call; this property is consistent
         *  between trips.
         */
        var currentIntersection: Intersection = depot
            internal set
    }

    val trucks: List<Truck> = listOf(Truck("truck-1"), Truck("truck-2"), Truck("truck-3"))

    // ── Order arrivals + Dispatcher ─────────────────────────────────────────

    /** A delivery order: pick up at one intersection, drop off at another. */
    data class Order(val pickup: Intersection, val dropoff: Intersection, val createdAt: Double)

    var orderArrivalRV: ExponentialRV =
        ExponentialRV(Defaults.orderInterarrivalMean, streamNum = 1, streamProvider = streamProvider)
    var truckVelocity: Double by positive(Defaults.truckVelocity)
    var pickupServiceTime: Double by nonNegative(Defaults.pickupServiceTime)
    var dropoffServiceTime: Double by nonNegative(Defaults.dropoffServiceTime)
    var travelStepSize: Double by positive(Defaults.travelStepSize)

    /** Mutable global defaults for [AutonomousDeliveryExample]. */
    companion object Defaults {
        /** Mean inter-arrival time between orders, simulated time units. Must be positive. */
        var orderInterarrivalMean: Double by positive(8.0)
        /** Truck velocity in coordinate units per simulated time unit. Must be positive. */
        var truckVelocity: Double by positive(5.0)
        /** Service-time delay at pickup. Must be non-negative. */
        var pickupServiceTime: Double by nonNegative(2.0)
        /** Service-time delay at dropoff. Must be non-negative. */
        var dropoffServiceTime: Double by nonNegative(2.0)
        /** Position-update granularity during travel, in coordinate units. Must be positive. */
        var travelStepSize: Double by positive(2.5)
    }

    // ── Responses ───────────────────────────────────────────────────────────

    val deliveryTime: Response = Response(this, "DeliveryTime")
    val numCouriersActive: TWResponse = TWResponse(this, "NumCouriersActive")
    val numOrdersDelivered: ksl.modeling.variable.Counter =
        ksl.modeling.variable.Counter(this, "NumOrdersDelivered")

    // ── OrderGenerator ──────────────────────────────────────────────────────

    inner class OrderGenerator(val dispatcherRef: () -> Dispatcher) : Agent("orderGen") {
        val script: KSLProcess = process(isDefaultProcess = true) {
            val rng = defaultRNStream
            while (true) {
                delay(orderArrivalRV.value)
                // Pick distinct pickup and dropoff intersections uniformly at random.
                val pickup = intersections[rng.randInt(0, intersections.size - 1)]
                var dropoff: Intersection
                do {
                    dropoff = intersections[rng.randInt(0, intersections.size - 1)]
                } while (dropoff === pickup)
                val order = Order(pickup, dropoff, currentTime)
                sendMessage(
                    AgentMessage.Request(this@OrderGenerator, order),
                    dispatcherRef().mailbox,
                )
            }
        }
    }

    // ── Dispatcher: receives orders, spawns Couriers ────────────────────────

    inner class Dispatcher : PermanentAgent("dispatcher") {
        init {
            statechart {
                initial("listening")
                state("listening") {
                    onMessage<AgentMessage.Request<Order>> { msg ->
                        spawnCourier(msg.payload)
                    }
                }
            }
        }

        private fun spawnCourier(order: Order) {
            val courier = Courier(order)
            activate(courier.script)
        }
    }

    // ── Courier: handles one delivery start-to-finish ───────────────────────

    private var nextCourierId: Int = 0

    inner class Courier(val order: Order) : Agent("courier-${++nextCourierId}") {

        val script: KSLProcess = process(isDefaultProcess = true) {
            numCouriersActive.increment()

            // Choose a truck: closest idle if any, else closest period
            // (the seize will block until one becomes free).
            val pickupPos = order.pickup.position
            val truck = (
                trucks.filter { !it.isBusy }
                    .minByOrNull { it.position.distanceTo(pickupPos) }
                    ?: trucks.minBy { it.position.distanceTo(pickupPos) }
                )

            val allocation = seize(truck)

            // Leg 1: depot/wherever to pickup.
            driveTruck(truck, order.pickup)
            delay(pickupServiceTime)

            // Leg 2: pickup to dropoff.
            driveTruck(truck, order.dropoff)
            delay(dropoffServiceTime)

            // Leg 3: dropoff back to depot.
            driveTruck(truck, depot)

            release(allocation)
            deliveryTime.value = currentTime - order.createdAt
            numOrdersDelivered.increment()
            numCouriersActive.decrement()
        }

        /**
         *  Drive [truck] from its current intersection to [dest]
         *  along the shortest path through the road network. Updates
         *  [Truck.currentIntersection] on arrival.
         */
        private suspend fun KSLProcessBuilder.driveTruck(truck: Truck, dest: Intersection) {
            if (truck.currentIntersection === dest) return
            val path = roads.shortestPath(truck.currentIntersection, dest)
                ?: error(
                    "no road path from ${truck.currentIntersection.name} to ${dest.name}; " +
                        "road network must be connected for the delivery model to function",
                )
            // path includes both endpoints; we're already at the first one.
            val waypoints = path.drop(1).map { it.position }
            travelThrough(
                agent = truck, space = space, waypoints = waypoints,
                velocity = truckVelocity, stepSize = travelStepSize,
            )
            truck.currentIntersection = dest
        }
    }

    // ── Wire-up ─────────────────────────────────────────────────────────────

    val dispatcher: Dispatcher = Dispatcher()
    private val orderGen: OrderGenerator = OrderGenerator({ dispatcher })

    override fun initialize() {
        super.initialize()
        // Reset truck intersections to depot in case of multi-replication.
        for (truck in trucks) {
            truck.currentIntersection = depot
            space.placeAt(truck, depot.position)
        }
        activate(orderGen.script)
    }
}

fun main() {
    val model = Model("AutonomousDeliveryExample")
    val sys = AutonomousDeliveryExample(model, "delivery")
    model.lengthOfReplication = 1000.0
    model.numberOfReplications = 1
    model.simulate()
    model.print()
    println("Orders delivered: ${sys.numOrdersDelivered.value}")
}
