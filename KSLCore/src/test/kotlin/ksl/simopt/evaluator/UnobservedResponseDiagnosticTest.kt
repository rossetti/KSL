package ksl.simopt.evaluator

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.modeling.variable.Response
import ksl.simopt.problem.InequalityType
import ksl.simopt.problem.ProblemDefinition
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A response that is never assigned a value during a replication has no average, and the NaN
 * that results is rejected when the replication data is summarized. That rejection is correct --
 * there is nothing to optimize against -- but it used to arrive as "The average was not a
 * number." from several frames inside the evaluator, naming neither the response nor the design
 * point that produced it.
 *
 * It is not an exotic situation. A response conditioned on an event can be perfectly well defined
 * over most of a feasible region and undefined in a corner of it: a service level measured only
 * over served customers has nothing to average at a design point that serves nobody. A search
 * will find such a corner, and when it does the run dies. What the reader needs at that moment is
 * which response and which design point, and that is what this test pins.
 */
class UnobservedResponseDiagnosticTest {

    private companion object {
        const val MODEL_ID = "unobservedProbe"
        const val OBJECTIVE = "observedResponse"
        const val NEVER = "neverObservedResponse"
        const val INPUT = "Probe.setting"
    }

    private object ProbeBuilder : ModelBuilderIfc {
        override fun build(
            modelConfiguration: Map<String, String>?,
            experimentRunParameters: ExperimentRunParametersIfc?
        ): Model {
            val model = Model(MODEL_ID, autoCSVReports = false)
            Probe(model)
            model.numberOfReplications = 3
            model.lengthOfReplication = 10.0
            return model
        }
    }

    private fun makeProblem(): ProblemDefinition {
        val pd = ProblemDefinition(
            problemName = "unobservedProbe",
            modelIdentifier = MODEL_ID,
            objFnResponseName = OBJECTIVE,
            inputNames = listOf(INPUT),
            responseNames = listOf(NEVER)
        )
        pd.inputVariable(INPUT, 1.0, 10.0, granularity = 1.0)
        pd.responseConstraint(NEVER, rhsValue = 1.0, inequalityType = InequalityType.LESS_THAN)
        return pd
    }

    @Test
    @DisplayName("An unobserved response is reported by name, with the design point")
    fun unobservedResponseIsDiagnosed() {
        val pd = makeProblem()
        val evaluator = Evaluator.createProblemEvaluator(pd, ProbeBuilder)
        val inputs = pd.toInputMap(mutableMapOf(INPUT to 4.0))
        val request = EvaluationRequest(
            modelIdentifier = MODEL_ID,
            modelInputs = listOf(ModelInputs(MODEL_ID, 3, inputs, pd.allResponseNames.toSet()))
        )

        val exception = assertThrows(IllegalArgumentException::class.java) {
            evaluator.evaluate(request)
        }
        val message = exception.message!!
        assertTrue(message.contains(NEVER)) { "the response was not named: $message" }
        assertTrue(message.contains(MODEL_ID)) { "the model was not named: $message" }
        assertTrue(message.contains("never observed")) { "the cause was not explained: $message" }
        assertTrue(message.contains("3 replications")) { "the replication count was not given: $message" }
        // the design point is what makes it actionable
        assertTrue(message.contains("4")) { "the design point was not reported: $message" }
    }
}

/** Records one response every replication and leaves the other untouched. */
class Probe(parent: ModelElement) : ModelElement(parent, "Probe") {
    private val myObserved = Response(this, "observedResponse")

    @Suppress("unused")
    private val myNeverObserved = Response(this, "neverObservedResponse")

    @set:KSLControl(controlType = ControlType.DOUBLE, lowerBound = 1.0, upperBound = 10.0)
    var setting: Double = 1.0
        set(value) {
            require(model.isNotRunning) { "The model must not be running" }
            field = value
        }

    override fun replicationEnded() {
        myObserved.value = setting
    }
}
