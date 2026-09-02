package ksl.examples.decision

import ksl.modeling.decision.DecisionElement
import ksl.modeling.decision.FixedPolicy
import ksl.modeling.decision.Neutral
import ksl.modeling.decision.NeutralPolicy
import ksl.modeling.decision.PolicyIfc
import ksl.modeling.decision.decisionElement
import ksl.modeling.station.QObjectReceiverIfc
import ksl.modeling.station.SResource
import ksl.modeling.station.SingleQStation
import ksl.modeling.station.StationNetwork
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Evidence for the fix to [ProportionalStaffing], and for the claim that the fix is the
 *  signal rather than the arithmetic.
 *
 *  The clinic runs eight staff against an offered load of 1.2 server-units at triage and
 *  2.4 at exam — 3.6 of 8 — so both queues sit near zero in equilibrium and the queue
 *  ratio is dominated by noise. Every arm below runs the identical model and the identical
 *  apportionment; they differ only in what the element declares as the allocation weight.
 */
class StaffingPolicyBenchmarkTest {

    /** What an arm declares as the weight for triage and exam. */
    enum class Basis {
        QUEUE,          // numInQ, instantaneous — the original
        BUSY,           // numBusyUnits, instantaneous
        IN_SYSTEM,      // numBusyUnits + numInQ, instantaneous
        BUSY_AVERAGE,   // the time-average of numBusyUnits since the warm-up
        MIXED           // triage weighted in jobs, exam in server-units — incommensurable
    }

    private class BenchClinic(
        parent: ModelElement,
        exit: QObjectReceiverIfc,
        basis: Basis,
        rule: PolicyIfc,
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

        val shiftReview: DecisionElement = decisionElement("ShiftReview") {
            // Each basis declares what it is measured in (§4.2.4). Note what this does and
            // does not buy: QUEUE and BUSY_AVERAGE are both internally consistent, so units
            // cannot tell you that one of them scores 68.84 and the other 19.03. What they
            // can tell you is that MIXED is not a quantity at all.
            when (basis) {
                Basis.QUEUE -> {
                    observe(triage.waitingQ.numInQ, unit = "jobs")
                    observe(exam.waitingQ.numInQ, unit = "jobs")
                }
                Basis.BUSY -> {
                    observe(triageStaff.numBusyUnits, unit = "server-units")
                    observe(examStaff.numBusyUnits, unit = "server-units")
                }
                Basis.IN_SYSTEM -> {
                    observe("Triage:InSystem", unit = "jobs") {
                        triageStaff.numBusyUnits.value + triage.waitingQ.numInQ.value
                    }
                    observe("Exam:InSystem", unit = "jobs") {
                        examStaff.numBusyUnits.value + exam.waitingQ.numInQ.value
                    }
                }
                Basis.BUSY_AVERAGE -> {
                    observe("Triage:Load", unit = "server-units") {
                        triageStaff.numBusyUnits.withinReplicationStatistic.weightedAverage
                    }
                    observe("Exam:Load", unit = "server-units") {
                        examStaff.numBusyUnits.withinReplicationStatistic.weightedAverage
                    }
                }
                // Two stations weighted on different things. Nothing about the model forbids
                // it, both readings are legal, and the sum ProportionalStaffing forms from
                // them is meaningless (§4.2.4, G.9 row 7).
                Basis.MIXED -> {
                    observe(triage.waitingQ.numInQ, unit = "jobs")
                    observe(examStaff.numBusyUnits, unit = "server-units")
                }
            }
            val t = lever(triageStaff, limits = 0..10, unit = "staff",
                neutral = Neutral.Current { capacity.toDouble() }) { v -> changeCapacity(v.toInt()) }
            val e = lever(examStaff, limits = 0..10, unit = "staff",
                neutral = Neutral.Current { capacity.toDouble() }) { v -> changeCapacity(v.toInt()) }
            budget(t, e, total = 8.0)
            policy = rule
        }.reviewEvery(this, 480.0)

        val entry: QObjectReceiverIfc get() = triage
    }

    private fun meanSystemTime(
        basis: Basis = Basis.BUSY_AVERAGE,
        rule: PolicyIfc = ProportionalStaffing,
        narrowTo: IntRange? = 1..7
    ): Double {
        val model = Model("Bench")
        val flow = StationNetwork(model, "ClinicFlow")
        val exit = flow.sink("Exit")
        val clinic = BenchClinic(model, exit, basis, rule, name = "Clinic")
        flow.source("Patients", ExponentialRV(5.0, streamNum = 3), firstReceiver = clinic.entry)
        model.numberOfReplications = 30
        model.lengthOfReplication = 43_200.0
        model.lengthOfReplicationWarmUp = 4_320.0
        if (narrowTo != null) {
            val e = clinic.shiftReview
            e.narrow(e.leverRef("Clinic:TriageStaff"), narrowTo)
            e.narrow(e.leverRef("Clinic:ExamStaff"), narrowTo)
        }
        model.simulate()
        return model.responses.first { it.name == "ClinicFlow:SystemTime" }
            .acrossReplicationStatistic.average
    }

    /**
     *  The measurement the fix rests on. Allocating on demand must beat allocating on
     *  congestion, and must not be worse than leaving the staff alone.
     */
    @Test
    fun proportionalToDemandBeatsProportionalToCongestion() {
        val doNothing = meanSystemTime(rule = NeutralPolicy)                    // static 4/4
        val optimum = meanSystemTime(rule = FixedPolicy(doubleArrayOf(3.0, 5.0)))   // M/M/c optimum
        val queue = meanSystemTime(basis = Basis.QUEUE)
        val busy = meanSystemTime(basis = Basis.BUSY)
        val inSystem = meanSystemTime(basis = Basis.IN_SYSTEM)
        val busyAvg = meanSystemTime(basis = Basis.BUSY_AVERAGE)

        println()
        println("Mean system time, 30 replications, levers narrowed to 1..7:")
        println("  do nothing (static 4/4)              %9.2f".format(doNothing))
        println("  static 3/5 (M/M/c optimum)           %9.2f".format(optimum))
        println("  proportional to QUEUE           (was) %9.2f".format(queue))
        println("  proportional to BUSY, snapshot       %9.2f".format(busy))
        println("  proportional to IN SYSTEM            %9.2f".format(inSystem))
        println("  proportional to BUSY, time-avg (now) %9.2f".format(busyAvg))
        println()

        // Both ingredients are load-bearing, and the middle row is why. Switching the
        // signal from congestion to demand is a large improvement and not a fix; the
        // instantaneous read still leaves a station below its own offered load for a
        // whole shift. Averaging the demand signal is what closes the gap.
        assertTrue(busy < queue, "the demand basis did not beat the congestion basis")
        assertTrue(busyAvg < busy, "averaging the demand signal did not help")
        assertTrue(busyAvg < doNothing, "the fixed rule is no better than leaving the staff alone")
        assertTrue(busyAvg < 1.05 * optimum, "the fixed rule is not within 5% of the static optimum")
    }

    /**
     *  The rule must also survive the wide limits the model declares. The original could
     *  not: with 0..10 it took a station to zero staff and the clinic collapsed.
     */
    @Test
    fun theFixedRuleIsStableWithoutNarrowing() {
        val wide = meanSystemTime(narrowTo = null)
        val narrowed = meanSystemTime()
        val doNothing = meanSystemTime(rule = NeutralPolicy)
        println("time-averaged busy basis: unnarrowed = %.2f, narrowed to 1..7 = %.2f, do nothing = %.2f"
            .format(wide, narrowed, doNothing))
        assertTrue(wide < doNothing, "the rule needs narrowing to be safe; it should not")
    }

    /**
     *  G.9 row 7. `unit` used to be a field nothing read — the shape of fault D.10 names —
     *  and this is the whole of what making it load-bearing bought, stated so the claim can
     *  be checked rather than believed.
     *
     *  Three arms, and the middle one is the finding:
     *
     *   - **MIXED is refused at assignment.** Triage weighted in jobs, exam in server-units.
     *     Both readings are legal, the model is fine, and the sum `ProportionalStaffing`
     *     divides by is not a quantity. Nothing else in the design could catch this: the two
     *     values have the same type, the same domain, and similar magnitudes.
     *   - **QUEUE is not refused, and scores 68.84 against BUSY_AVERAGE's 19.03.** This is
     *     the §8.1.2 defect, and units are blind to it, because "jobs" is a perfectly
     *     consistent basis — just the wrong one. Units answer *are these the same kind of
     *     thing*, never *is this the right thing*.
     *   - **A surface that declares no units passes every unit check trivially**, and looks
     *     identical in a green run to one where every check fired. `unitCoverage()` is what
     *     tells the two apart.
     */
    @Test
    fun unitsCatchIncommensurableWeightsAndNothingElse() {
        val mixed = runCatching { meanSystemTime(basis = Basis.MIXED) }.exceptionOrNull()
        println()
        println("MIXED basis (triage in jobs, exam in server-units):")
        println("  ${mixed?.let { it::class.simpleName } ?: "ACCEPTED"}")
        println("  ${mixed?.message}")
        assertTrue(
            mixed is IllegalArgumentException,
            "weighting two levers on incommensurable observations should be refused at " +
                "configure(); it ran instead"
        )

        // The defect units cannot see. Both bases are internally consistent.
        val queue = meanSystemTime(basis = Basis.QUEUE)
        val load = meanSystemTime(basis = Basis.BUSY_AVERAGE)
        println()
        println("Consistent but wrong is still accepted, and still costs:")
        println("  QUEUE        (jobs, consistent)         mean system time %6.2f".format(queue))
        println("  BUSY_AVERAGE (server-units, consistent) mean system time %6.2f".format(load))
        assertTrue(queue > load, "the §8.1.2 result no longer holds; re-read this test's premise")

        // What the optionality costs, made countable rather than argued about.
        val model = Model("UnitCoverage")
        val flow = StationNetwork(model, "ClinicFlow")
        val clinic = BenchClinic(model, flow.sink("Exit"), Basis.BUSY_AVERAGE, ProportionalStaffing, name = "Clinic")
        flow.source("Patients", ExponentialRV(5.0, streamNum = 3), firstReceiver = clinic.entry)
        val coverage = clinic.shiftReview.unitCoverage()
        println()
        println("Declared unit coverage on this surface: $coverage")
        assertTrue(coverage.fullyChecked,
            "this model declares a unit on every lever, so every constraint should have been " +
                "fully checked; got $coverage")
        assertEquals(2, coverage.observationsDeclared)
        assertEquals(2, coverage.leversDeclared)
    }
}
