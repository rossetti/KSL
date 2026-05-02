package ksl.examples.general.running

import ksl.controls.experiments.ConcurrentScenarioRunner
import ksl.controls.experiments.Scenario
import ksl.controls.experiments.ScenarioRunner
import ksl.controls.experiments.assignIndependentStreamAdvances
import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import kotlinx.coroutines.runBlocking

/**
 * Demonstrates how ScenarioRunner and ConcurrentScenarioRunner use random-number streams.
 *
 * By default, fresh instances of the same model use the same stream numbers and start
 * from the same substream block. This gives common random numbers across scenarios.
 *
 * Use independent stream advances when scenarios should start from different substream
 * blocks instead. The examples below show the runner-level convenience method, the
 * public List<Scenario> helper, and the single-scenario helpers.
 *
 * During experiment setup, KSL applies model configuration, controls, and random-variable
 * parameter changes first. It then resets streams if resetStartStreamOption is true,
 * applies numberOfStreamAdvancesPriorToRunning, and finally applies the setting that
 * controls end-of-replication substream advancement. This ordering lets reset-to-start
 * and explicit stream advances be used together safely.
 */
fun main() {
    defaultCommonRandomNumbers()
    runnerLevelIndependentStreams()
    resetBeforeIndependentStreamAdvances()
    fineGrainedIndependentStreams()
    concurrentIndependentStreams()
}

/**
 * Default behavior: no stream advances are assigned.
 *
 * Each scenario uses a fresh model instance with the same random-variable stream
 * numbers inside GIGcQueue. Since all scenarios have zero pre-run stream advances,
 * they begin from the same substream block. This is the usual common-random-number
 * setup for scenario comparisons.
 */
fun defaultCommonRandomNumbers() {
    val scenarios = buildServerCountScenarios()
    val runner = ScenarioRunner("ServerCount_Default_CRN", scenarios)

    printStreamAdvances("Default common-random-number setup", scenarios)

    runner.simulate()
    runner.print()
}

/**
 * Runner-level independent streams.
 *
 * This is the simplest user-facing option when every scenario in the runner should
 * receive a non-overlapping substream assignment. With the default spacing, the first
 * scenario starts at advance 0, and each later scenario starts after the prior
 * scenario's number of replications.
 *
 * With 30 replications per scenario, the assigned advances are 0, 30, and 60.
 */
fun runnerLevelIndependentStreams() {
    val scenarios = buildServerCountScenarios()
    val runner = ScenarioRunner("ServerCount_Independent", scenarios)

    runner.useIndependentRandomStreams()

    printStreamAdvances("Runner-level independent stream setup", scenarios)

    runner.simulate()
    runner.print()
}

/**
 * Reset-to-start and independent stream advances can be combined.
 *
 * The resetStartStreamOption is useful when repeated executions should begin from
 * a known stream state. KSL performs that reset before applying each scenario's
 * numberOfStreamAdvancesPriorToRunning value, so the explicit stream-advance
 * assignment is not erased by the reset.
 *
 * This example assigns the same independent stream advances as the previous example,
 * but also asks each scenario to reset streams at the start of the experiment.
 */
fun resetBeforeIndependentStreamAdvances() {
    val scenarios = buildServerCountScenarios()
    val runner = ScenarioRunner("ServerCount_Reset_Independent", scenarios)

    for (scenario in scenarios) {
        scenario.scenarioRunParameters.resetStartStreamOption = true
    }

    runner.useIndependentRandomStreams()

    printStreamAdvances("Reset-to-start followed by independent stream setup", scenarios)

    runner.simulate()
    runner.print()
}

/**
 * Fine-grained stream control.
 *
 * Library users may configure individual scenarios directly or use the public
 * List<Scenario>.assignIndependentStreamAdvances helper. This is useful when only
 * a subset of scenarios should be independent, or when the caller wants stable
 * manually chosen stream-advance offsets.
 */
fun fineGrainedIndependentStreams() {
    val scenarios = buildServerCountScenarios()

    // Direct scenario-level control.
    // This explicitly moves OneServer to substream block 25.
    scenarios[0].useStreamAdvance(25)

    // This explicitly restores TwoServers to the default CRN setting.
    scenarios[1].useCommonRandomNumbers()

    // List-level control for selected scenarios.
    // Here only ThreeServers is assigned by the list helper.
    scenarios.assignIndependentStreamAdvances(
        scenarios = 2..2,
        startingStreamAdvance = 100,
        streamAdvanceSpacing = 50
    )

    printStreamAdvances("Fine-grained stream setup", scenarios)
}

/**
 * Concurrent runner usage.
 *
 * ConcurrentScenarioRunner requires each Scenario to build a fresh Model instance.
 * The QueueModelBuilder below does that by creating a new Model every time build()
 * is called.
 *
 * The stream-assignment API is the same as ScenarioRunner: call
 * useIndependentRandomStreams() before simulate().
 */
fun concurrentIndependentStreams() = runBlocking {
    val scenarios = buildServerCountScenarios()
    val runner = ConcurrentScenarioRunner("Concurrent_ServerCount_Independent", scenarios)

    runner.useIndependentRandomStreams()

    printStreamAdvances("Concurrent independent stream setup", scenarios)

    runner.simulate()
    runner.print()
}

/**
 * Builds three scenarios that share the same model structure and stream-number choices.
 *
 * The scenarios differ only by the controlled number of servers. Because the builder
 * creates GIGcQueue with its default arrival stream 1 and service stream 2, these
 * scenarios are suitable for demonstrating common random numbers and independent
 * stream advances.
 */
private fun buildServerCountScenarios(): List<Scenario> {
    fun scenario(name: String, servers: Double): Scenario {
        return Scenario(
            modelBuilder = QueueModelBuilder("${name}_Model"),
            name = name,
            inputs = mapOf("MM1Q.numServers" to servers),
            numberReplications = 30,
            lengthOfReplication = 1_000.0,
            lengthOfReplicationWarmUp = 100.0
        )
    }

    return listOf(
        scenario("OneServer", 1.0),
        scenario("TwoServers", 2.0),
        scenario("ThreeServers", 3.0)
    )
}

/**
 * A small model builder for ScenarioRunner and ConcurrentScenarioRunner examples.
 *
 * Returning a new Model from every build() call keeps each scenario execution isolated.
 * That isolation is required for ConcurrentScenarioRunner and is also a good pattern
 * for scenario examples that are intended to compare fresh model instances.
 */
private class QueueModelBuilder(
    private val modelName: String
) : ModelBuilderIfc {

    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val model = Model(modelName, autoCSVReports = false)

        // GIGcQueue defaults to arrival stream 1 and service stream 2.
        // Using the same defaults in each fresh model is what makes CRN possible.
        GIGcQueue(model, numServers = 1, name = "MM1Q")

        return model
    }
}

/**
 * Prints the stream-advance assignment that will be applied when each scenario runs.
 *
 * These values are stored in ExperimentRunParameters.numberOfStreamAdvancesPriorToRunning.
 * SimulationRunner applies resetStartStreamOption first, then this stream-advance value,
 * before executing the first replication.
 */
private fun printStreamAdvances(title: String, scenarios: List<Scenario>) {
    println()
    println(title)
    for (scenario in scenarios) {
        val params = scenario.scenarioRunParameters
        println(
            "${scenario.name.padEnd(14)} " +
                "reset start = ${params.resetStartStreamOption.toString().padEnd(5)} " +
                "stream advances before running = ${params.numberOfStreamAdvancesPriorToRunning}"
        )
    }
}
