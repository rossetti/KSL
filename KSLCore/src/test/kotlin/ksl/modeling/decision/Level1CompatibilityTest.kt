package ksl.modeling.decision

import ksl.modeling.station.QObjectReceiverIfc
import ksl.modeling.station.SResource
import ksl.modeling.station.SingleQStation
import ksl.modeling.station.StationNetwork
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 *  The vertical slice of §8.1. It tests exactly one claim: that a decision element under
 *  HoldCurrentPolicy reproduces the same model with no decision element, response for
 *  response and counter for counter.
 *
 *  Four arms of one clinic model:
 *
 *   NONE            no decision element at all — the reference
 *   HOLD_WITH_READ  HoldCurrentPolicy over levers that declare how to read their own value
 *   HOLD_NO_READ    HoldCurrentPolicy over levers that declare only how to write
 *   FIXED_NO_READ   FixedPolicy(4, 4) — the values the model already has — over write-only
 *                   levers, so that every epoch issues a redundant write
 */
class Level1CompatibilityTest {

    enum class Arm { NONE, HOLD_WITH_READ, HOLD_NO_READ, FIXED_NO_READ }

    class Clinic(
        parent: ModelElement,
        exit: QObjectReceiverIfc,
        private val arm: Arm,
        name: String? = null
    ) : ModelElement(parent, name) {

        private val triageStaff = SResource(this, capacity = 4, name = "${this.name}:TriageStaff")
        private val examStaff = SResource(this, capacity = 4, name = "${this.name}:ExamStaff")

        private val exam = SingleQStation(
            this, activityTime = ExponentialRV(12.0, streamNum = 2),
            resource = examStaff, nextReceiver = exit, name = "${this.name}:Exam"
        )

        private val triage = SingleQStation(
            this, activityTime = ExponentialRV(6.0, streamNum = 1),
            resource = triageStaff, nextReceiver = exam, name = "${this.name}:Triage"
        )

        val shiftReview: DecisionElement? = when (arm) {
            Arm.NONE -> null
            Arm.HOLD_WITH_READ -> decisionElement("ShiftReview") {
                observe(triage.waitingQ.numInQ)
                observe(exam.waitingQ.numInQ)
                val t = lever(triageStaff, limits = 0..10,
                    read = { capacity.toDouble() }) { v -> changeCapacity(v.toInt()) }
                val e = lever(examStaff, limits = 0..10,
                    read = { capacity.toDouble() }) { v -> changeCapacity(v.toInt()) }
                budget(t, e, total = 8.0)
                every(480.0)
                policy = HoldCurrentPolicy
            }
            Arm.HOLD_NO_READ -> decisionElement("ShiftReview") {
                observe(triage.waitingQ.numInQ)
                observe(exam.waitingQ.numInQ)
                val t = lever(triageStaff, limits = 0..10) { v -> changeCapacity(v.toInt()) }
                val e = lever(examStaff, limits = 0..10) { v -> changeCapacity(v.toInt()) }
                budget(t, e, total = 8.0)
                every(480.0)
                policy = HoldCurrentPolicy
            }
            Arm.FIXED_NO_READ -> decisionElement("ShiftReview") {
                observe(triage.waitingQ.numInQ)
                observe(exam.waitingQ.numInQ)
                val t = lever(triageStaff, limits = 0..10) { v -> changeCapacity(v.toInt()) }
                val e = lever(examStaff, limits = 0..10) { v -> changeCapacity(v.toInt()) }
                budget(t, e, total = 8.0)
                every(480.0)
                policy = FixedPolicy(doubleArrayOf(4.0, 4.0))
            }
        }

        val entry: QObjectReceiverIfc get() = triage
    }

    private fun build(arm: Arm): Model {
        val model = Model("Clinic-$arm")
        val flow = StationNetwork(model, "ClinicFlow")
        val exit = flow.sink("Exit")
        val clinic = Clinic(model, exit = exit, arm = arm, name = "Clinic")
        flow.source("Patients", ExponentialRV(5.0, streamNum = 3), firstReceiver = clinic.entry)
        model.numberOfReplications = 30
        model.lengthOfReplication = 43_200.0
        model.lengthOfReplicationWarmUp = 4_320.0
        return model
    }

    /**
     *  Two grains, deliberately separated.
     *
     *  REPORTED is what a user sees: the across-replication statistics behind
     *  model.print(). This is the grain §6's Level 1 is written at.
     *
     *  FINE adds the within-replication observation counts of the last replication.
     *  Those do not appear in the default report, but they are visible to anyone who
     *  attaches a ResponseTrace or reads withinReplicationStatistic directly.
     */
    enum class Grain { REPORTED, FINE }

    private fun harvest(model: Model, grain: Grain): Map<String, List<Double>> {
        val out = LinkedHashMap<String, List<Double>>()
        for (r in model.responses) {
            val s = r.acrossReplicationStatistic
            val v = mutableListOf(s.count, s.average, s.standardDeviation, s.min, s.max)
            if (grain == Grain.FINE) v += r.withinReplicationStatistic.count
            out[r.name] = v
        }
        for (c in model.counters) {
            val s = c.acrossReplicationStatistic
            val v = mutableListOf(s.count, s.average, s.standardDeviation, s.min, s.max)
            if (grain == Grain.FINE) v += s.count
            out[c.name] = v
        }
        return out
    }

    private fun run(arm: Arm, grain: Grain = Grain.REPORTED): Map<String, List<Double>> {
        val model = build(arm)
        model.simulate()
        return harvest(model, grain)
    }

    private fun differences(a: Map<String, List<Double>>, b: Map<String, List<Double>>): List<String> {
        val diffs = mutableListOf<String>()
        val labels = listOf("count", "average", "stdDev", "min", "max", "withinRepCount")
        assertEquals(a.keys, b.keys, "the two arms report different response names")
        for ((name, av) in a) {
            val bv = b.getValue(name)
            for (i in av.indices) {
                // NaN == NaN here: a statistic with no observations reports NaN in both
                // arms and that is agreement, not disagreement.
                val same = av[i] == bv[i] || (av[i].isNaN() && bv[i].isNaN())
                if (!same) diffs += "$name.${labels[i]}: ${av[i]} vs ${bv[i]}"
            }
        }
        return diffs
    }

    /**
     *  The claim under test. A decision element that decides to change nothing must be
     *  invisible in every reported statistic.
     */
    @Test
    fun holdCurrentPolicyReproducesTheUnmodifiedModel() {
        val none = run(Arm.NONE, Grain.FINE)
        val hold = run(Arm.HOLD_WITH_READ, Grain.FINE)
        val diffs = differences(none, hold)
        assertTrue(diffs.isEmpty(), "HoldCurrentPolicy perturbed the model:\n${diffs.joinToString("\n")}")
    }

    /**
     *  Where the elision in DefaultActionBinding.prepare actually matters. This arm
     *  writes the capacity the resource already has, at every epoch. It turns out that
     *  every REPORTED statistic is unchanged — the time-weighted area is the same however
     *  many break points it is computed over — so Level 1 as §6 defines it survives a
     *  redundant write. The FINE grain does not: the extra assignments are counted.
     */
    @Test
    fun writingTheCurrentValueBackIsInvisibleInTheReportButNotUnderneathIt() {
        val reportedDiffs = differences(run(Arm.NONE), run(Arm.FIXED_NO_READ))
        assertTrue(
            reportedDiffs.isEmpty(),
            "Redundant writes changed a reported statistic:\n${reportedDiffs.joinToString("\n")}"
        )

        val fineDiffs = differences(run(Arm.NONE, Grain.FINE), run(Arm.FIXED_NO_READ, Grain.FINE))
        assertFalse(fineDiffs.isEmpty(), "Expected redundant writes to be visible at the fine grain")
        println("Redundant-write arm, reported grain: no differences.")
        println("Redundant-write arm, fine grain: ${fineDiffs.size} differences:")
        fineDiffs.take(20).forEach { println("  $it") }
    }

    /**
     *  The clinic above pins every stream with an explicit streamNum, which is the safe
     *  case. This arm uses default stream assignment, where adding a model element could
     *  in principle shift which stream each random variable draws from — a Level-1 failure
     *  that no amount of care inside the decision element would prevent.
     */
    @Test
    fun addingADecisionElementDoesNotShiftDefaultStreamAssignment() {
        fun buildDefaultStreams(arm: Arm): Model {
            val model = Model("DefaultStreams-$arm")
            val flow = StationNetwork(model, "ClinicFlow")
            val exit = flow.sink("Exit")
            val clinic = ClinicDefaultStreams(model, exit = exit, arm = arm, name = "Clinic")
            flow.source("Patients", ExponentialRV(5.0), firstReceiver = clinic.entry)
            model.numberOfReplications = 30
            model.lengthOfReplication = 43_200.0
            model.lengthOfReplicationWarmUp = 4_320.0
            return model
        }

        // The control: two IDENTICAL models with default streams. KSLRandom's
        // DefaultRNStreamProvider is a JVM-wide singleton and streamNum = 0 means "the
        // next stream I have not handed out yet", so a second model built in the same
        // JVM draws from different streams than the first — with or without a decision
        // element. If this control disagrees, the comparison below measures the provider,
        // not the decision element.
        val controlA = harvest(buildDefaultStreams(Arm.NONE).also { it.simulate() }, Grain.FINE)
        val controlB = harvest(buildDefaultStreams(Arm.NONE).also { it.simulate() }, Grain.FINE)
        val controlDiffs = differences(controlA, controlB)

        val none = harvest(buildDefaultStreams(Arm.NONE).also { it.simulate() }, Grain.FINE)
        val hold = harvest(buildDefaultStreams(Arm.HOLD_WITH_READ).also { it.simulate() }, Grain.FINE)
        val armDiffs = differences(none, hold)

        println("Default streams, two identical models:      ${controlDiffs.size} differences")
        println("Default streams, without vs with element:   ${armDiffs.size} differences")
        assertFalse(
            controlDiffs.isEmpty(),
            "Two identical default-stream models agreed. If the provider stops being a JVM-wide " +
                "counter, rewrite this test: the arm comparison below would then be meaningful."
        )
    }

    /** Same clinic, but no random variable names a stream. */
    class ClinicDefaultStreams(
        parent: ModelElement,
        exit: QObjectReceiverIfc,
        arm: Arm,
        name: String? = null
    ) : ModelElement(parent, name) {
        private val triageStaff = SResource(this, capacity = 4, name = "${this.name}:TriageStaff")
        private val examStaff = SResource(this, capacity = 4, name = "${this.name}:ExamStaff")
        private val exam = SingleQStation(
            this, activityTime = ExponentialRV(12.0), resource = examStaff,
            nextReceiver = exit, name = "${this.name}:Exam"
        )
        private val triage = SingleQStation(
            this, activityTime = ExponentialRV(6.0), resource = triageStaff,
            nextReceiver = exam, name = "${this.name}:Triage"
        )

        init {
            if (arm != Arm.NONE) {
                decisionElement("ShiftReview") {
                    observe(triage.waitingQ.numInQ)
                    observe(exam.waitingQ.numInQ)
                    val t = lever(triageStaff, limits = 0..10,
                        read = { capacity.toDouble() }) { v -> changeCapacity(v.toInt()) }
                    val e = lever(examStaff, limits = 0..10,
                        read = { capacity.toDouble() }) { v -> changeCapacity(v.toInt()) }
                    budget(t, e, total = 8.0)
                    every(480.0)
                    policy = HoldCurrentPolicy
                }
            }
        }

        val entry: QObjectReceiverIfc get() = triage
    }

    /**
     *  The complement of the Level-1 test: a rule that decides differently must actually
     *  move the model. Without this, an empty epoch loop would pass every test above.
     */
    @Test
    fun aStateDependentRuleActuallyChangesTheModel() {
        val model = build(Arm.HOLD_WITH_READ)
        val clinic = model.getModelElement("Clinic") as Clinic
        val element = clinic.shiftReview!!
        element.policy = ProportionalStaffingForTest
        model.simulate()
        val moved = harvest(model, Grain.FINE)
        val held = run(Arm.HOLD_WITH_READ, Grain.FINE)
        val diffs = differences(held, moved)
        assertFalse(diffs.isEmpty(), "The state-dependent rule changed nothing")
        assertTrue(element.epochCount > 0, "no epoch ever ran")
        println("ProportionalStaffing ran ${element.epochCount} epochs in the last replication " +
            "and moved ${diffs.size} statistics.")
        diffs.filter { it.contains(".average") }.take(10).forEach { println("  $it") }
    }

    /**
     *  The same rule, with the parameterization §4.1.4 applies to it: narrow each lever
     *  to 1..7 so neither station can be left with no staff. The narrowed run is expected
     *  to be dramatically better than the unnarrowed one, which makes narrowing a
     *  load-bearing parameter rather than a convenience.
     */
    @Test
    fun narrowingChangesTheOutcomeOfTheSameRule() {
        fun systemTime(narrow: Boolean): Double {
            val model = build(Arm.HOLD_WITH_READ)
            val clinic = model.getModelElement("Clinic") as Clinic
            val element = clinic.shiftReview!!
            if (narrow) {
                element.narrow(element.leverRef("Clinic:TriageStaff"), 1..7)
                element.narrow(element.leverRef("Clinic:ExamStaff"), 1..7)
            }
            element.policy = ProportionalStaffingForTest
            model.simulate()
            return model.responses.first { it.name == "ClinicFlow:SystemTime" }
                .acrossReplicationStatistic.average
        }
        val wide = systemTime(narrow = false)
        val narrowed = systemTime(narrow = true)
        println("ProportionalStaffing system time: unnarrowed = $wide, narrowed to 1..7 = $narrowed")
        assertTrue(narrowed < wide, "narrowing did not improve the same rule")
    }

    object ProportionalStaffingForTest : ShapeAwarePolicyIfc {
        override fun configure(surface: ksl.modeling.decision.descriptor.DecisionSurfaceDescriptor) {
            require(surface.levers.size == 2)
        }

        override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
            val budget = ctx.budgetTotal(0)!!
            val bounds = ctx.leverBounds[0]
            val q0 = observation[0]
            val q1 = observation[1]
            val t = if (q0 + q1 == 0.0) Math.rint(budget / 2.0)
            else Math.rint(budget * q0 / (q0 + q1)).coerceIn(bounds.start, bounds.endInclusive)
            return doubleArrayOf(t, budget - t)
        }
    }

    /**
     *  A write-only lever cannot answer "what is the current value?", so the baseline
     *  policy has nothing to return. This documents the failure rather than the fix.
     */
    @Test
    fun holdCurrentPolicyFailsOnWriteOnlyLevers() {
        val model = build(Arm.HOLD_NO_READ)
        val e = runCatching { model.simulate() }.exceptionOrNull()
        assertTrue(e != null, "expected HoldCurrentPolicy over write-only levers to fail")
        println("HOLD_NO_READ failed with: ${e!!::class.simpleName}: ${e.message}")
    }
}
