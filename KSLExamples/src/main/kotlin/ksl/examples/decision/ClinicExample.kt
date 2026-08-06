package ksl.examples.decision

import ksl.modeling.decision.*
import ksl.modeling.station.QObjectReceiverIfc
import ksl.modeling.station.SResource
import ksl.modeling.station.SingleQStation
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import kotlin.math.roundToInt

/**
 * A two-station clinic sharing a fixed pool of staff, with a shift review that may
 * reallocate them every eight hours. Nothing about the clinic's internals is public:
 * the decision surface and two named parameters are the entire public API.
 */
class ClinicSubsystem(
    parent: ModelElement,
    name: String? = null
) : ModelElement(parent, name) {

    private val triageStaff = SResource(this, capacity = 4, name = "${this.name}:TriageStaff")
    private val examStaff   = SResource(this, capacity = 4, name = "${this.name}:ExamStaff")

    // exam is declared first: SingleQStation takes its successor as a constructor parameter.
    private val exam = SingleQStation(
        this, activityTime = ExponentialRV(12.0, streamNum = 2),
        resource = examStaff, name = "${this.name}:Exam")

    private val triage = SingleQStation(
        this, activityTime = ExponentialRV(6.0, streamNum = 1),
        resource = triageStaff, nextReceiver = exam, name = "${this.name}:Triage")

    val shiftReview = decisionElement("ShiftReview") {
        observe(triage.waitingQ.numInQ)                                     // index 0
        observe(exam.waitingQ.numInQ)                                       // index 1

        // lever(...) returns the lever's own identity; budget names levers, not targets.
        val t = lever(triageStaff, limits = 0..10) { v -> changeCapacity(v.toInt()) }
        val e = lever(examStaff,   limits = 0..10) { v -> changeCapacity(v.toInt()) }
        budget(t, e, total = 8.0)

        every(480.0)
        policy = HoldCurrentPolicy
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

class ProportionalStaffing(ctx: PolicyCreationContext) : PolicyIfc {
    private val budget = ctx.budgetTotal(leverIndex = 0) ?: error("triage lever is in no budget")
    private val bounds = ctx.leverBounds[0]
    override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
        val qTriage = observation[0]
        val qExam = observation[1]
        val t = when {
            qTriage + qExam == 0.0 -> (budget / 2.0).roundToInt().toDouble()
            else -> (budget * qTriage / (qTriage + qExam)).roundToInt().toDouble()
                .coerceIn(bounds.start, bounds.endInclusive)
        }
        return doubleArrayOf(t, budget - t)
    }
}

fun main() {
    val model = Model("ClinicStudy")
    val clinic = ClinicSubsystem(model, name = "Clinic")

    model.numberOfReplications = 30
    model.lengthOfReplication = 43_200.0
    model.lengthOfReplicationWarmUp = 4_320.0

    // Parameterization: named properties on the subsystem, like reviewPolicy.
    clinic.triageStaffRange = 1..7
    clinic.examStaffRange   = 1..7

    model.experimentName = "baseline"
    model.simulate()

    model.resetStartStreamOption = true
    clinic.shiftReview.policyFrom { ctx -> ProportionalStaffing(ctx) }
    model.experimentName = "proportional"
    model.simulate()

    model.print()
}
