package ksl.examples.decision

import ksl.modeling.decision.*
import ksl.modeling.station.SResource
import ksl.modeling.station.SingleQStation
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import kotlin.math.roundToInt

class ClinicSubsystem(
    parent: ModelElement,
    name: String? = null
) : ModelElement(parent, name) {

    private val triageStaff = SResource(this, capacity = 4, name = "${this.name}:TriageStaff")
    private val examStaff   = SResource(this, capacity = 4, name = "${this.name}:ExamStaff")

    private val exam = SingleQStation(
        this, activityTime = ExponentialRV(12.0, streamNum = 2),
        resource = examStaff, name = "${this.name}:Exam")

    private val triage = SingleQStation(
        this, activityTime = ExponentialRV(6.0, streamNum = 1),
        resource = triageStaff, nextReceiver = exam, name = "${this.name}:Triage")

    val shiftReview = decisionElement("ShiftReview") {
        observe(triage.waitingQ.numInQ)
        observe(exam.waitingQ.numInQ)
        lever(triageStaff, limits = 0..10) { v -> changeCapacity(v.toInt()) }
        lever(examStaff,   limits = 0..10) { v -> changeCapacity(v.toInt()) }
        budget(triageStaff, examStaff, total = 8.0)
        every(480.0)
        policy = HoldCurrentPolicy
    }

    val triageStaffElement: ModelElement get() = triageStaff
    val examStaffElement: ModelElement get() = examStaff
}

class ProportionalStaffing(ctx: PolicyCreationContext) : PolicyIfc {
    private val budget = ctx.budgetTotal() ?: error("requires a declared budget")
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

    clinic.shiftReview.narrow(clinic.triageStaffElement, 1..7)
    clinic.shiftReview.narrow(clinic.examStaffElement, 1..7)

    model.experimentName = "baseline"
    model.simulate()

    model.resetStartStreamOption = true
    clinic.shiftReview.policyFrom { ctx -> ProportionalStaffing(ctx) }
    model.experimentName = "proportional"
    model.simulate()

    model.print()
}
