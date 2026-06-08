package ksl.modeling.agent.doc

import ksl.modeling.agent.AgentLike
import ksl.modeling.agent.AgentMessage
import ksl.modeling.agent.AgentModel
import ksl.modeling.agent.AgentResource
import ksl.modeling.agent.AgentSignal
import ksl.modeling.agent.Cell
import ksl.modeling.agent.ContinuousProjection
import ksl.modeling.agent.ContinuousVolume
import ksl.modeling.agent.Dynamics
import ksl.modeling.agent.Dynamics3D
import ksl.modeling.agent.FlowField3D
import ksl.modeling.agent.GridProjection
import ksl.modeling.agent.NetworkProjection
import ksl.modeling.agent.Point2D
import ksl.modeling.agent.Point3D
import ksl.modeling.agent.Population
import ksl.modeling.agent.Voxel
import ksl.modeling.agent.VoxelProjection
import ksl.modeling.agent.alignment
import ksl.modeling.agent.cohesion
import ksl.modeling.agent.contractNet
import ksl.modeling.agent.nonNegative
import ksl.modeling.agent.positive
import ksl.modeling.agent.probability
import ksl.modeling.agent.sendMessage
import ksl.modeling.agent.separation
import ksl.modeling.agent.travelTo
import ksl.modeling.entity.KSLProcess
import ksl.simulation.Model
import ksl.simulation.ModelElement

/**
 * Compile-only host for every code snippet in `docs/guides/ksl-agent.md`.
 * Each `fun` body is a verbatim snippet (or its body); compiling this file
 * proves every example in the guide references real public APIs.
 *
 * This file is not run as a test — the build only needs to compile it.
 */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER", "unused")
private object AgentGuideSnippets {

    // -- Quick start: a heartbeat agent toggling between two states -----

    class HeartbeatModel(parent: ModelElement, name: String? = null) :
        AgentModel(parent, name) {

        inner class Beat(aName: String) : PermanentAgent(aName) {
            init {
                statechart {
                    initial("Off")
                    state("Off") { onTimeout(1.0) { transitionTo("On") } }
                    state("On") { onTimeout(1.0) { transitionTo("Off") } }
                }
            }
        }
        val beat = Beat("Beat-1")
    }

    fun runHeartbeat() {
        val model = Model("Heartbeat")
        HeartbeatModel(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 20.0
        model.simulate()
        model.print()
    }

    // -- Recipe: transient Agent (created during a replication) ---------

    class WorkflowModel(parent: ModelElement) : AgentModel(parent) {
        // A transient Agent created from inside a process body
        inner class Job(aName: String) : Agent(aName) {
            val script: KSLProcess = process(isDefaultProcess = true) {
                delay(5.0)
                // ... work ...
            }
        }
    }

    // -- Recipe: statechart with hierarchical state + signal + condition --

    class ControllerModel(parent: ModelElement) : AgentModel(parent) {

        val signal: AgentSignal = AgentSignal("WakeUp")

        inner class Controller(aName: String) : PermanentAgent(aName) {
            var temperature: Double = 0.0

            init {
                statechart {
                    initial("Idle")
                    state("Idle") {
                        onEntry { /* log entry */ }
                        onSignal(signal) { transitionTo("Active") }
                    }
                    state("Active") {
                        initial("Warmup")
                        state("Warmup") {
                            onTimeout(2.0) { transitionTo("Running") }
                        }
                        state("Running") {
                            onCondition({ temperature > 100.0 }) {
                                transitionTo("Cooling")
                            }
                        }
                        state("Cooling") {
                            onTimeout(5.0) { transitionTo("Idle") }
                        }
                    }
                    final("Done")
                    onCompletion { name -> /* react to final state */ }
                }
            }
        }
    }

    // -- Recipe: send / receive AgentMessage ---------------------------

    class TwoAgentChat(parent: ModelElement) : AgentModel(parent) {

        inner class Listener(aName: String) : PermanentAgent(aName) {
            init {
                statechart {
                    initial("Listen")
                    state("Listen") {
                        onMessage<AgentMessage.Inform<String>> { msg ->
                            val payload: String = msg.payload
                            // react to the message
                        }
                    }
                }
            }
        }
        val listener = Listener("Listener")

        inner class Talker(aName: String) : Agent(aName) {
            val script: KSLProcess = process(isDefaultProcess = true) {
                delay(1.0)
                sendMessage(
                    AgentMessage.Inform(this@Talker, payload = "hello"),
                    listener.mailbox,
                )
            }
        }
    }

    // -- Recipe: Context + Population ----------------------------------

    class PopulationModel(parent: ModelElement) : AgentModel(parent) {
        inner class Walker(aName: String) : Agent(aName)

        val walkers: Context<Walker> = Context("walkers")

        override fun initialize() {
            super.initialize()
            for (i in 1..50) walkers.add(Walker("w-$i"))
        }

        fun headcount(): Int = walkers.size

        // Population: a live view filtered by type, iterable / queryable.
        val population: Population<Walker> = Population(this, Walker::class.java)
    }

    // -- Recipe: spatial projections (Grid / Network / Continuous) ------

    class GridDemo(parent: ModelElement) : AgentModel(parent) {
        inner class Bug(aName: String) : Agent(aName)

        val bugs: Context<Bug> = Context("bugs")
        val grid = GridProjection(bugs, columns = 20, rows = 20, torus = true)

        init {
            val a = Bug("a"); val b = Bug("b")
            bugs.add(a); bugs.add(b)
            grid.placeAt(a, Cell(0, 0))
            grid.placeAt(b, col = 1, row = 0)

            val neighbors = grid.mooreNeighborhood(Cell(0, 0))
            val others = grid.agentsAt(0, 0)
            val nearby = grid.agentsWithin(Cell(0, 0), radius = 2)
        }
    }

    class NetworkDemo(parent: ModelElement) : AgentModel(parent) {
        inner class Person(aName: String) : Agent(aName)

        val people: Context<Person> = Context("people")
        val friendships = NetworkProjection(people, directed = false)

        init {
            val a = Person("a"); val b = Person("b")
            people.add(a); people.add(b)
            friendships.connect(a, b)

            val friends = friendships.neighborsOf(a)
            val reachable = friendships.reachableFrom(a)
        }
    }

    class ContinuousDemo(parent: ModelElement) : AgentModel(parent) {
        inner class Boid(aName: String) : Agent(aName)

        val boids: Context<Boid> = Context("boids")
        val space = ContinuousProjection(
            context = boids,
            xRange = 0.0..100.0,
            yRange = 0.0..100.0,
            torus = true,
        )

        init {
            val b = Boid("b1")
            boids.add(b)
            space.placeAt(b, x = 10.0, y = 20.0)
            space.moveTo(b, Point2D(11.0, 20.5))
            val pos: Point2D? = space.positionOf(b)
        }
    }

    // -- Recipe: travelTo from inside a process body -------------------

    class TravelerModel(parent: ModelElement) : AgentModel(parent) {
        inner class Traveler(aName: String) : Agent(aName)

        val travelers = Context<Traveler>("travelers")
        val space = ContinuousProjection(
            travelers, 0.0..100.0, 0.0..100.0,
        )

        fun makeTraveler(): Traveler {
            val t = Traveler("t")
            travelers.add(t)
            space.placeAt(t, 0.0, 0.0)
            // launch its journey from a transient agent's process body
            object : Agent("driver") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    travelTo(t, space, destination = Point2D(50.0, 50.0), velocity = 1.0)
                }
            }
            return t
        }
    }

    // -- Recipe: force-based dynamics (flocking) -----------------------

    class FlockModel(parent: ModelElement) : AgentModel(parent) {
        inner class Bird(aName: String) : Agent(aName)

        val birds: Context<Bird> = Context("birds")
        val space = ContinuousProjection(birds, 0.0..200.0, 0.0..200.0, torus = true)
        val dynamics = Dynamics(space)

        init {
            dynamics.addForce(separation<Bird>(radius = 5.0))
            dynamics.addForce(alignment<Bird>(radius = 15.0))
            dynamics.addForce(cohesion<Bird>(radius = 30.0))
            // Each tick: dynamics.stepAll(birds.members, dt = 0.1)
        }
    }

    // -- Recipe: Contract-Net delegation -------------------------------
    // The bidders are transient Agents whose own process bodies (or
    // statecharts) reply with AgentMessage.Propose; this snippet shows
    // only the initiator-side call.

    class TaskAuctionModel(parent: ModelElement) : AgentModel(parent) {
        data class Bid(val priceCents: Int)

        inner class Bidder(aName: String) : Agent(aName)
        inner class Initiator(aName: String) : Agent(aName) {
            val script: KSLProcess = process(isDefaultProcess = true) {
                val a = Bidder("A"); val b = Bidder("B")
                val outcome = contractNet<String, Bid>(
                    bidders = listOf(a, b),
                    callForProposals = "haul-load-#42",
                    deadline = 1.0,
                    selectBest = { ps -> ps.minByOrNull { it.proposal.priceCents } },
                )
                outcome?.winner?.let { /* award the job */ }
            }
        }
    }

    // -- Recipe: an agent that's also a seizable resource --------------

    class ForkliftModel(parent: ModelElement) : AgentModel(parent) {

        inner class Forklift(aName: String) : AgentResource(
            agentModel = this@ForkliftModel,
            name = aName,
            capacity = 1,
        ) {
            init {
                statechart {
                    initial("on")
                    state("on") {
                        onMessage<AgentMessage.Inform<String>>({ it.payload == "break" }) {
                            goOffShift()
                            transitionTo("off")
                        }
                    }
                    state("off") {
                        onMessage<AgentMessage.Inform<String>>({ it.payload == "resume" }) {
                            goOnShift()
                            transitionTo("on")
                        }
                    }
                }
            }
        }
        val forklift = Forklift("F-1")
        // Process-view code can now `seize(forklift)` / `release(forklift)`.
    }

    // -- Recipe: 3D variants ------------------------------------------

    class SkyDemo(parent: ModelElement) : AgentModel(parent) {
        inner class Drone(aName: String) : Agent(aName)

        val drones: Context<Drone> = Context("drones")
        val sky = ContinuousVolume(
            drones,
            xRange = 0.0..100.0,
            yRange = 0.0..100.0,
            zRange = 0.0..50.0,
        )

        init {
            val d = Drone("d1")
            drones.add(d)
            sky.placeAt(d, Point3D(10.0, 20.0, 5.0))
            val pos: Point3D? = sky.positionOf(d)
        }
    }

    class VoxelDemo(parent: ModelElement) : AgentModel(parent) {
        inner class Block(aName: String) : Agent(aName)

        val blocks: Context<Block> = Context("blocks")
        val voxels = VoxelProjection(blocks, columns = 10, rows = 10, layers = 5)

        init {
            val b = Block("b1")
            blocks.add(b)
            voxels.placeAt(b, Voxel(0, 0, 0))
        }
    }

    // -- Recipe: property delegates for validated configuration --------

    @Suppress("MemberVisibilityCanBePrivate")
    class TunableModel(parent: ModelElement) : AgentModel(parent) {

        var stepSize: Double by positive(1.0)
        var populationCount: Int by positive(50)
        var infectionProb: Double by probability(0.10)
        var initialInfected: Int by nonNegative(3)

        companion object Defaults {
            var defaultStepSize: Double by positive(1.0)
            var defaultPopulation: Int by positive(50)
        }
    }

    // -- Recipe: read per-agent statistics + observe agent registration -

    fun readStats(model: AgentModel) {
        val total: Int = model.agentCount
        for (agent in model.agents) {
            // Per-agent state-time and mailbox stats are exposed by
            // PermanentAgent.performance if it was opted in.
        }
        model.attachRegistryObserver(object : AgentModel.AgentRegistryObserver {
            override fun onAgentRegistered(agent: AgentLike) { /* log */ }
        })
    }
}
