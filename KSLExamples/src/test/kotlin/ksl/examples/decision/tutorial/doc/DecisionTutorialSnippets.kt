package ksl.examples.decision.tutorial.doc

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.modeling.decision.DecisionContext
import ksl.modeling.decision.DecisionElement
import ksl.modeling.decision.Neutral
import ksl.modeling.decision.NeutralPolicy
import ksl.modeling.decision.PolicyIfc
import ksl.modeling.decision.decisionElement
import ksl.modeling.decision.descriptor.DecisionSurfaceDescriptor
import ksl.modeling.decision.descriptor.LeverDomain
import ksl.modeling.decision.descriptor.RewardSense
import ksl.modeling.decision.descriptor.SumEquals
import ksl.modeling.variable.Counter
import ksl.modeling.variable.TWResponse
import ksl.examples.decision.tutorial.BuildStockRoomDecisionModel
import ksl.sdm.capture.DecisionCapture
import ksl.sdm.capture.MemorySink
import ksl.sdm.capture.TrajectoryFile
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.Solver
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.Interval
import java.nio.file.Path
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Compile-only host for every code snippet in `docs/guides/ksl-decision-tutorial.md`.
 *
 * Each `fun` body (or class) is a verbatim snippet, so compiling this file proves every
 * example in the tutorial references real public APIs. `DecisionTutorialSnippetsTest`
 * additionally proves the tutorial's blocks actually APPEAR here — without that second
 * check this file can compile perfectly while the tutorial shows an API that no longer
 * exists, which is a green check measuring nothing.
 *
 * This file is not run as a test; the build only needs to compile it.
 */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER", "unused")
private object DecisionTutorialSnippets {

    // -- Part II: the stock room ---------------------------------------

    class StockRoom(parent: ModelElement, name: String? = null) : ModelElement(parent, name) {

        val onHand = TWResponse(this, name = "${this.name}:OnHand", initialValue = 50.0)
        val ordersPlaced = Counter(this, name = "${this.name}:Orders")

        private var onOrder: Double = 0.0

        /** The model's own operation. A lever writes through this; it is not decision code. */
        fun placeOrder(quantity: Double) {
            if (quantity <= 0.0) return
            onOrder += quantity
            ordersPlaced.increment()
        }

        val review: DecisionElement = decisionElement("${this.name}:Review") {
            observe(onHand, unit = "units")                       // observation 0
            lever(
                this@StockRoom, limits = 0..200,
                neutral = Neutral.Value(0.0),                     // ordering nothing IS the no-op
                alias = "OrderQty", unit = "units"
            ) { q -> placeOrder(q) }
            reward(onHand, rate = 0.5, sense = RewardSense.COST, alias = "Holding")
            every(5.0)
            policy = NeutralPolicy
        }
    }

    fun quickStartRun() {
        val model = Model("StockRoomDemo")
        val room = StockRoom(model, "Room")
        model.numberOfReplications = 10
        model.lengthOfReplication = 500.0
        model.simulate()
        model.print()
    }

    /** An (s, S) rule: order up to [bigS] whenever the position is at or below [s]. */
    class OrderUpTo(private val s: Double, private val bigS: Double) : PolicyIfc {
        init { require(s < bigS) { "The reorder point s=$s must be below the order-up-to level S=$bigS" } }
        override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
            val position = observation[0]
            return if (position <= s) doubleArrayOf(bigS - position) else doubleArrayOf(0.0)
        }
    }

    fun swapTheRule(room: StockRoom, model: Model) {
        room.review.policy = OrderUpTo(12.0, 32.0)
        room.review.policyLabel = "(12, 32)"
        model.simulate()
    }

    // -- Part III: the clinic ------------------------------------------

    class Clinic(parent: ModelElement, name: String? = null) : ModelElement(parent, name) {

        private val triageStaff = ksl.modeling.station.SResource(
            this, capacity = 4, name = "${this.name}:TriageStaff")
        private val examStaff = ksl.modeling.station.SResource(
            this, capacity = 4, name = "${this.name}:ExamStaff")
        private val exam = SingleQStationStub(this, "${this.name}:Exam")
        private val triage = SingleQStationStub(this, "${this.name}:Triage")

        val shiftReview: DecisionElement = decisionElement("${this.name}:ShiftReview") {
            observe("Triage:Load") { triageStaff.numBusyUnits.withinReplicationStatistic.weightedAverage }
            observe("Exam:Load") { examStaff.numBusyUnits.withinReplicationStatistic.weightedAverage }

            val t = lever(triageStaff, limits = 0..10,
                neutral = Neutral.Current { capacity.toDouble() }) { v -> changeCapacity(v.toInt()) }
            val e = lever(examStaff, limits = 0..10,
                neutral = Neutral.Current { capacity.toDouble() }) { v -> changeCapacity(v.toInt()) }
            budget(t, e, total = 8.0)

            reward(exam.numProcessed, rate = 25.0, sense = RewardSense.REWARD, alias = "Revenue")
            reward(triage.waitingQ.numInQ, rate = 10.0, sense = RewardSense.COST, alias = "TriageWait")
            reward(exam.waitingQ.numInQ, rate = 10.0, sense = RewardSense.COST, alias = "ExamWait")

            every(480.0)
            policy = NeutralPolicy
        }
    }

    /** Stands in for `SingleQStation`, whose constructor needs a downstream receiver. */
    class SingleQStationStub(parent: ModelElement, name: String) : ModelElement(parent, name) {
        val numProcessed = Counter(this, name = "${this.name}:NumProcessed")
        val waitingQ = QueueStub(this, "${this.name}:Q")
    }

    class QueueStub(parent: ModelElement, name: String) : ModelElement(parent, name) {
        val numInQ = TWResponse(this, name = "${this.name}:NumInQ")
    }

    object ProportionalStaffingSnippet : ksl.modeling.decision.ShapeAwarePolicyIfc {
        override fun configure(surface: DecisionSurfaceDescriptor) {
            require(surface.observations.size == surface.levers.size) {
                "ProportionalStaffing weights each lever by one observation, so it needs " +
                    "${surface.levers.size} observations; the element declares ${surface.observations.size}."
            }
            require(surface.constraints.any { it is SumEquals }) {
                "ProportionalStaffing divides a fixed budget, so it needs a declared budget() " +
                    "over its levers. The element declares: ${surface.constraints}"
            }
        }

        override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray =
            DoubleArray(ctx.leverNames.size)
    }

    // -- Part IV: the depot --------------------------------------------

    class ShipmentDepot(parent: ModelElement, name: String? = null) : ModelElement(parent, name) {

        private val truckCapacity = 100
        private val regionNames = listOf("North", "Central", "South")
        private val myBacklog = regionNames.map { r -> TWResponse(this, name = "${this.name}:$r:Backlog") }
        private val myOnHand = TWResponse(this, name = "${this.name}:OnHand", initialValue = 20.0)

        fun backlog(i: Int): Double = myBacklog[i].value
        val shippableNow: Double get() = min(myOnHand.value, truckCapacity.toDouble())
        private fun ship(i: Int, qty: Int) { }

        val allocation: DecisionElement = decisionElement("${this.name}:Allocation") {
            for (i in regionNames.indices) observe("${regionNames[i]}:Backlog") { backlog(i) }
            observe("Stock") { shippableNow }
            val refs = regionNames.indices.map { i ->
                lever(
                    this@ShipmentDepot, limits = 0..truckCapacity,
                    neutral = Neutral.Value(0.0),
                    alias = "Ship:${regionNames[i]}",
                    bounds = { 0.0..backlog(i) }
                ) { q -> ship(i, q.toInt()) }
            }
            atMost(*refs.toTypedArray(), envelope = truckCapacity.toDouble()) { shippableNow }
            every(10.0)
            policy = NeutralPolicy
        }
    }

    class GreedySnippet(private val order: List<Int>) : PolicyIfc {
        override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
            val n = ctx.leverNames.size
            val plan = DoubleArray(n)
            var remaining = ctx.budgetTotal(0) ?: Double.MAX_VALUE
            for (i in order) {
                val want = ctx.actions.bounds(i).endInclusive          // what this region is owed
                val give = max(0.0, minOf(want, remaining))
                plan[i] = Math.rint(give)
                remaining -= plan[i]
            }
            return plan
        }
    }

    class ReDerivedSnippet : PolicyIfc {
        override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
            val n = ctx.leverNames.size
            var remaining = min(observation[n], 100.0)
            return DoubleArray(n)
        }
    }

    // -- Part V: capture and off-line training -------------------------

    fun attachFromOutside(model: Model, element: DecisionElement) {
        val sink = MemorySink()
        element.attachTransitionSink(sink)
        model.simulate()
        element.detachTransitionSink(sink)

        println("${sink.records.size} transitions, and the model never mentioned capture")
    }

    private val outputDir: Path = java.nio.file.Paths.get("out")

    fun captureAWholeModel(model: Model) {
        DecisionCapture.toDirectory(model, outputDir).use {
            model.simulate()
        }
    }

    /** Fits an order-up-to level from a captured trajectory, reading only the file. */
    fun bestOrderUpTo(rowsPath: Path): Double =
        TrajectoryFile(rowsPath).use { trajectory ->
            val surface = trajectory.descriptor
            val position = surface.observations.indexOfFirst { it.name.endsWith(":Position") }
            val lever = surface.levers[0]

            val best = trajectory.transitions()
                .groupBy { floor((it.state[position] + it.action[0]) / 5.0) * 5.0 }   // post-decision position
                .filterValues { it.size >= 20 }                                        // ignore thin buckets
                .maxByOrNull { (_, rows) -> rows.sumOf { it.reward } / rows.size }!!
                .key + 2.5                                                             // bucket midpoint

            // The descriptor says what a LEGAL order is; the rows do not.
            if (lever.domain == LeverDomain.CONTINUOUS) best else Math.rint(best)
        }

    // -- Part VI: the simopt handoff -----------------------------------

    class ParameterizedOrderUpTo(
        parent: ModelElement,
        s: Double = 10.0,
        sDelta: Double = 20.0,
        name: String? = null
    ) : ModelElement(parent, name), PolicyIfc {

        @set:KSLControl(controlType = ControlType.DOUBLE, lowerBound = 0.0, upperBound = 60.0)
        var s: Double = s
            set(value) {
                require(value.isFinite() && value >= 0.0) { "s must be finite and >= 0, was $value" }
                field = value
            }

        @set:KSLControl(controlType = ControlType.DOUBLE, lowerBound = 0.0, upperBound = 80.0)
        var sDelta: Double = sDelta
            set(value) {
                require(value.isFinite() && value >= 0.0) { "sDelta must be finite and >= 0, was $value" }
                field = value
            }

        val orderUpToLevel: Double get() = s + sDelta

        override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
            val position = observation[0]
            val quantity = if (position <= s) orderUpToLevel - position else 0.0
            return doubleArrayOf(Math.rint(maxOf(quantity, 0.0)))
        }
    }

    private val STOCK_ROOM_DECISION_ID = ksl.examples.decision.tutorial.STOCK_ROOM_DECISION_ID
    private val STOCK_ROOM_OBJECTIVE = ksl.examples.decision.tutorial.STOCK_ROOM_OBJECTIVE
    private val STOCK_ROOM_S = ksl.examples.decision.tutorial.STOCK_ROOM_S
    private val STOCK_ROOM_S_DELTA = ksl.examples.decision.tutorial.STOCK_ROOM_S_DELTA

    fun makeStockRoomProblem(): ProblemDefinition {
        val problem = ProblemDefinition(
            problemName = "StockRoomOrderUpTo",
            modelIdentifier = STOCK_ROOM_DECISION_ID,
            objFnResponseName = STOCK_ROOM_OBJECTIVE,
            inputNames = listOf(STOCK_ROOM_S, STOCK_ROOM_S_DELTA),
            optimizationType = ksl.simopt.problem.OptimizationType.MAXIMIZE
        )
        problem.inputVariable(name = STOCK_ROOM_S, interval = Interval(0.0, 60.0), granularity = 1.0)
        problem.inputVariable(name = STOCK_ROOM_S_DELTA, interval = Interval(0.0, 80.0), granularity = 1.0)
        return problem
    }

    fun runTheSearch(problem: ProblemDefinition, maxIterations: Int, replicationsPerEvaluation: Int) {
        val solver = Solver.createStochasticHillClimberSolver(
            problemDefinition = problem,
            modelBuilder = BuildStockRoomDecisionModel,
            startingPoint = null,
            maxIterations = maxIterations,
            replicationsPerEvaluation = replicationsPerEvaluation
        )
        solver.runAllIterations()
    }
}
