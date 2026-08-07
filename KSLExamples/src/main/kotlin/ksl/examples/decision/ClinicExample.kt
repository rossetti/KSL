package ksl.examples.decision

import ksl.modeling.decision.*
import ksl.modeling.decision.descriptor.DecisionSurfaceDescriptor
import ksl.modeling.decision.descriptor.SumEquals
import ksl.modeling.station.QObjectReceiverIfc
import ksl.modeling.station.SResource
import ksl.modeling.station.SingleQStation
import ksl.modeling.station.StationNetwork
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * A two-station clinic sharing a fixed pool of staff, with a shift review that may
 * reallocate them every eight hours. Nothing about the clinic's internals is public:
 * the decision surface and two named parameters are the entire public API.
 */
class ClinicSubsystem(
    parent: ModelElement,
    exit: QObjectReceiverIfc,          // where patients go after the exam
    name: String? = null
) : ModelElement(parent, name) {

    private val triageStaff = SResource(this, capacity = 4, name = "${this.name}:TriageStaff")
    private val examStaff   = SResource(this, capacity = 4, name = "${this.name}:ExamStaff")

    // exam is declared first: SingleQStation takes its successor as a constructor parameter.
    private val exam = SingleQStation(
        this, activityTime = ExponentialRV(12.0, streamNum = 2),
        resource = examStaff, nextReceiver = exit, name = "${this.name}:Exam")

    private val triage = SingleQStation(
        this, activityTime = ExponentialRV(6.0, streamNum = 1),
        resource = triageStaff, nextReceiver = exam, name = "${this.name}:Triage")

    val shiftReview = decisionElement("ShiftReview") {
        // Observation i is the allocation weight for lever i. Each is the TIME-AVERAGE
        // number of busy units — an estimate of the work arriving at that station, in
        // server-units. Two properties matter and neither is incidental: it measures
        // demand rather than congestion, and it is an average rather than a snapshot.
        // See the note on ProportionalStaffing for what each one buys.
        observe("Triage:Load") {                                            // index 0
            triageStaff.numBusyUnits.withinReplicationStatistic.weightedAverage
        }
        observe("Exam:Load") {                                              // index 1
            examStaff.numBusyUnits.withinReplicationStatistic.weightedAverage
        }

        // lever(...) returns the lever's own identity; budget names levers, not targets.
        // A capacity is a SETTING: doing nothing means leaving it where it stands, so the
        // neutral carries the reader (§8.2.3). That reader is what lets the element answer
        // "where does this lever stand now?" and skip a write that would change nothing.
        val t = lever(triageStaff, limits = 0..10,
            neutral = Neutral.Current { capacity.toDouble() }) { v -> changeCapacity(v.toInt()) }
        val e = lever(examStaff, limits = 0..10,
            neutral = Neutral.Current { capacity.toDouble() }) { v -> changeCapacity(v.toInt()) }
        budget(t, e, total = 8.0)

        every(480.0)
        policy = NeutralPolicy
    }

    // Resolved once, after the element exists. Private: these are identity tokens
    // the subsystem uses to mediate its own parameters.
    private val triageLever = shiftReview.leverFor(triageStaff)
    private val examLever   = shiftReview.leverFor(examStaff)

    /** How many staff this run may put on triage. Narrowing only — the model allows 0..10. */
    var triageStaffRange: IntRange
        get() = shiftReview.limitsOf(triageLever)
        set(value) { shiftReview.narrow(triageLever, value) }

    /** How many staff this run may put on exam. */
    var examStaffRange: IntRange
        get() = shiftReview.limitsOf(examLever)
        set(value) { shiftReview.narrow(examLever, value) }

    /** Where arrivals enter. QObjectReceiverIfc is all a source needs — publishing the
     *  station itself would hand out its queue and its resource. */
    val entry: QObjectReceiverIfc get() = triage
}

/**
 * Split the declared staffing budget in proportion to observed demand.
 *
 * Observation i is the weight for lever i, so what this rule allocates *on* is chosen by
 * the model's `observe` declarations rather than restated here — see the block in
 * [ClinicSubsystem], which declares each resource's busy units.
 *
 * **Why the observations are time-averaged busy units.** The first version of this rule
 * allocated in proportion to instantaneous queue length and oscillated hard. The clinic
 * runs eight staff against an offered load of 3.6 server-units, so both queues sit near
 * zero in equilibrium and one patient waiting at triage with none at exam reads as "all
 * eight to triage" — which starves exam, fills exam's queue, and starves triage at the
 * next epoch. The arithmetic below was never the problem; the signal was, in two separate
 * ways, and fixing either one alone is not enough.
 *
 * *Demand, not congestion.* **Queue length is a consequence of the allocation**, so
 * allocating on it closes a positive feedback loop with a 480-unit delay. Busy units are
 * what the arrival process makes them: while a station has enough capacity, its busy count
 * does not depend on what this rule decided. Allocating on a quantity the rule does not
 * itself move is what breaks the loop.
 *
 * *An average, not a snapshot.* Instantaneous busy units at triage are 0, 1, 2 or 3 at any
 * moment around a mean of 1.2 — the station is completely idle about 30% of the time. A
 * snapshot reading of (0, 3) sends every spare unit to exam and leaves triage at its floor,
 * below its own offered load, for a full 480-unit shift. The time-average estimates the
 * load rather than sampling it.
 *
 * Measured over 30 replications, mean system time (`StaffingPolicyBenchmarkTest`):
 *
 * ```
 *   do nothing, static 4/4                 20.28
 *   static 3/5, the M/M/c optimum          19.01
 *   proportional to queue length          187.45     <- the original
 *   proportional to instantaneous busy     68.84     <- demand, but still a snapshot
 *   proportional to time-averaged busy     19.03     <- this rule
 * ```
 *
 * Damping the original — moving partway toward the target each epoch, or capping the step
 * size — would reduce the amplitude without removing the loop. Feeding it a signal from
 * outside the loop, and estimating rather than sampling that signal, removes it.
 *
 * **What this rule cannot show.** The clinic's arrivals are stationary, so the best
 * possible rule is a constant and this one converges to it. It earns 19.03 against the
 * optimum's 19.01 and does not need the levers narrowed to stay safe, which is the point
 * — but a shift review is only genuinely worth making when demand varies by shift, and
 * this model's does not.
 */
object ProportionalStaffing : ShapeAwarePolicyIfc {

    /**
     * Called once when this rule is assigned, before any replication.
     *
     * Only invariants that cannot subsequently change are checked here. Lever bounds are
     * deliberately not: `narrow(...)` is replication-initial too and may be called after
     * this policy is assigned, so a bounds check made here could be stale by the first
     * epoch. Bounds are honoured in [action], where they are current.
     */
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

    override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
        val budget = ctx.budgetTotal(leverIndex = 0)!!      // configure() guaranteed it
        val n = ctx.leverNames.size
        val lo = DoubleArray(n) { ceil(ctx.leverBounds[it].start) }
        val hi = DoubleArray(n) { floor(ctx.leverBounds[it].endInclusive) }

        // Each lever's ideal real-valued share. Equal shares when there is no demand to
        // divide, which is also what keeps the rule from dividing by zero.
        val weight = DoubleArray(n) { max(observation[it], 0.0) }
        val total = weight.sum()
        val ideal = DoubleArray(n) { if (total > 0.0) budget * weight[it] / total else budget / n }

        // Integer apportionment. Give every lever its lower bound, then hand out the
        // remaining units one at a time to whichever lever is furthest below its ideal
        // share and not already at its upper bound. This lands on the budget exactly and
        // never proposes a value outside a lever's own limits — both of which the
        // declaration requires and would otherwise reject the action for.
        val allocation = lo.copyOf()
        var remaining = budget - allocation.sum()
        while (remaining >= 1.0) {
            var best = -1
            var largestShortfall = Double.NEGATIVE_INFINITY
            for (i in 0 until n) {
                if (allocation[i] + 1.0 > hi[i]) continue
                val shortfall = ideal[i] - allocation[i]
                if (shortfall > largestShortfall) {
                    largestShortfall = shortfall
                    best = i
                }
            }
            if (best < 0) break         // every lever is at its ceiling
            allocation[best] += 1.0
            remaining -= 1.0
        }
        return allocation
    }
}

fun main() {
    val model = Model("ClinicStudy")

    // Arrivals and departures are ordinary ksl.modeling.station wiring, unrelated to
    // the decision. SourceStation and SinkStation have internal constructors, so both
    // come from a StationNetwork.
    val flow   = StationNetwork(model, "ClinicFlow")
    val exit   = flow.sink("Exit")
    val clinic = ClinicSubsystem(model, exit = exit, name = "Clinic")
    flow.source("Patients", ExponentialRV(5.0, streamNum = 3), firstReceiver = clinic.entry)

    model.numberOfReplications = 30
    model.lengthOfReplication = 43_200.0
    model.lengthOfReplicationWarmUp = 4_320.0

    // Parameterization: named properties on the subsystem, like reviewPolicy.
    clinic.triageStaffRange = 1..7
    clinic.examStaffRange   = 1..7

    model.experimentName = "baseline"
    model.simulate()

    model.resetStartStreamOption = true
    clinic.shiftReview.policy = ProportionalStaffing
    model.experimentName = "proportional"
    model.simulate()

    model.print()
}
