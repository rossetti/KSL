package ksl.simulation

import ksl.controls.experiments.ExperimentRunParameters
import ksl.controls.experiments.SimulationRunner
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RandomStreamSetupOrderingTest {

    @Test
    fun streamAdvancesAreAppliedAfterResetStartStream() {
        val reference = runOneDraw(numberOfReplications = 4)
        val advanced = runOneDraw(
            numberOfReplications = 1,
            resetStartStreamOption = true,
            numberOfStreamAdvancesPriorToRunning = 3
        )

        assertEquals(
            reference[3],
            advanced[0],
            0.0,
            "Reset-to-start must occur before the explicit pre-run substream advance"
        )
    }

    @Test
    fun chunkedReplicationsMatchFullRunReplicationSlices() {
        val fullRun = runOneDraw(numberOfReplications = 10)
        val fixture = buildOneDrawFixture("Chunked")
        val runner = SimulationRunner(fixture.model)
        val chunks = SimulationRunner.chunkReplications(fixture.model, numReplications = 10, size = 4)

        val chunkedRun = chunks
            .flatMap { params -> simulate(fixture, runner, params).asIterable() }
            .toDoubleArray()

        assertArrayEquals(
            fullRun,
            chunkedRun,
            0.0,
            "Chunked replications must reproduce the corresponding full-run replication observations"
        )
    }

    @Test
    fun streamAdvancesSurviveRvParameterChanges() {
        val reference = runOneDraw(
            numberOfReplications = 4,
            mean = CHANGED_MEAN
        )
        val advanced = runOneDrawWithRvParameterChange(
            changedMean = CHANGED_MEAN,
            numberOfReplications = 1,
            resetStartStreamOption = true,
            numberOfStreamAdvancesPriorToRunning = 3
        )

        assertEquals(
            reference[3],
            advanced[0],
            0.0,
            "RV parameter replacement must occur before reset and explicit pre-run substream advance"
        )
    }

    @Test
    fun preRunStreamAdvancesIgnorePriorAdvanceNextSubStreamOption() {
        val reference = runOneDraw(numberOfReplications = 4)
        val fixture = buildOneDrawFixture("PriorAdvanceOption")
        val runner = SimulationRunner(fixture.model)

        val disableEndOfReplicationAdvances = fixture.model.extractRunParameters().copy(
            numberOfReplications = 1,
            lengthOfReplication = REPLICATION_LENGTH,
            lengthOfReplicationWarmUp = 0.0,
            resetStartStreamOption = true,
            advanceNextSubStreamOption = false,
            numberOfStreamAdvancesPriorToRunning = 0
        )
        simulate(fixture, runner, disableEndOfReplicationAdvances)

        val explicitAdvance = fixture.model.extractRunParameters().copy(
            numberOfReplications = 1,
            lengthOfReplication = REPLICATION_LENGTH,
            lengthOfReplicationWarmUp = 0.0,
            resetStartStreamOption = true,
            advanceNextSubStreamOption = false,
            numberOfStreamAdvancesPriorToRunning = 3
        )
        val advanced = simulate(fixture, runner, explicitAdvance)

        assertEquals(
            reference[3],
            advanced[0],
            0.0,
            "Explicit pre-run advances must not depend on a prior experiment's end-of-replication advance option"
        )
    }

    private fun runOneDraw(
        numberOfReplications: Int,
        mean: Double = DEFAULT_MEAN,
        resetStartStreamOption: Boolean = false,
        advanceNextSubStreamOption: Boolean = true,
        numberOfStreamAdvancesPriorToRunning: Int = 0
    ): DoubleArray {
        val fixture = buildOneDrawFixture("Run", mean)
        val runner = SimulationRunner(fixture.model)
        val params = fixture.model.extractRunParameters().copy(
            numberOfReplications = numberOfReplications,
            lengthOfReplication = REPLICATION_LENGTH,
            lengthOfReplicationWarmUp = 0.0,
            resetStartStreamOption = resetStartStreamOption,
            advanceNextSubStreamOption = advanceNextSubStreamOption,
            numberOfStreamAdvancesPriorToRunning = numberOfStreamAdvancesPriorToRunning
        )
        return simulate(fixture, runner, params)
    }

    private fun runOneDrawWithRvParameterChange(
        changedMean: Double,
        numberOfReplications: Int,
        resetStartStreamOption: Boolean,
        numberOfStreamAdvancesPriorToRunning: Int
    ): DoubleArray {
        val fixture = buildOneDrawFixture("ParameterChange")
        val setter = fixture.model.rvParameterSetter

        assertTrue(
            setter.changeParameter(RV_NAME, "mean", changedMean),
            "Test fixture must expose the random variable mean through RVParameterSetter"
        )

        val runner = SimulationRunner(fixture.model)
        val params = fixture.model.extractRunParameters().copy(
            numberOfReplications = numberOfReplications,
            lengthOfReplication = REPLICATION_LENGTH,
            lengthOfReplicationWarmUp = 0.0,
            resetStartStreamOption = resetStartStreamOption,
            numberOfStreamAdvancesPriorToRunning = numberOfStreamAdvancesPriorToRunning
        )
        return simulate(fixture, runner, params)
    }

    private fun simulate(
        fixture: OneDrawFixture,
        runner: SimulationRunner,
        runParameters: ExperimentRunParameters
    ): DoubleArray {
        val simulationRun = runner.simulate(experimentRunParameters = runParameters)
        return simulationRun.replicationObservations(fixture.element.responseName)
            ?: error("Missing response observations for ${fixture.element.responseName}")
    }

    private fun buildOneDrawFixture(
        label: String,
        mean: Double = DEFAULT_MEAN
    ): OneDrawFixture {
        val model = Model("StreamSetupOrdering_${label}_${System.nanoTime()}", autoCSVReports = false)
        model.lengthOfReplication = REPLICATION_LENGTH
        model.lengthOfReplicationWarmUp = 0.0
        val element = OneDrawElement(model, mean)
        return OneDrawFixture(model, element)
    }

    private data class OneDrawFixture(
        val model: Model,
        val element: OneDrawElement
    )

    private class OneDrawElement(
        parent: Model,
        mean: Double
    ) : ModelElement(parent, "OneDraw") {

        private val rv = RandomVariable(
            parent = this,
            rSource = ExponentialRV(mean, streamNum = 1),
            name = RV_NAME
        )
        private val response = Response(this, RESPONSE_NAME)

        val responseName: String
            get() = response.name

        override fun initialize() {
            response.value = rv.value
        }
    }

    companion object {
        private const val DEFAULT_MEAN = 1.0
        private const val CHANGED_MEAN = 2.0
        private const val REPLICATION_LENGTH = 1.0
        private const val RV_NAME = "SetupOrderingRV"
        private const val RESPONSE_NAME = "Sample"
    }
}
