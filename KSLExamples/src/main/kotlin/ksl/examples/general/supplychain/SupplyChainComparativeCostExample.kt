package ksl.examples.general.supplychain

import ksl.modeling.supplychain.spec.NetworkSpec
import ksl.modeling.supplychain.spec.SupplyChainBuilder
import ksl.modeling.supplychain.spec.fromToml
import ksl.simulation.Model

/**
 * A comparative cost study authored as data (DSL plan Phase D5 /
 * cost-redesign Phase 6).  The TOML resource
 * `comparative-cost-network.toml` attaches **three** cost formulations
 * to one network — `standard`, `highCarrying`, and a per-node
 * `warehouseHeavy` — so a single simulation run reports all three side
 * by side (their responses are prefixed by the formulation name).
 *
 * This is the multi-formulation comparative-study case: every
 * formulation observes the same sample path, so the totals are directly
 * comparable without re-running the model.
 *
 * Run via
 * `gradle :KSLExamples:run -PmainClass=ksl.examples.general.supplychain.SupplyChainComparativeCostExampleKt`
 * or by invoking `main` from an IDE.
 */
fun main() {
    val toml = object {}.javaClass
        .getResourceAsStream("/ksl/examples/general/supplychain/comparative-cost-network.toml")
        ?.bufferedReader()?.use { it.readText() }
        ?: error("could not find comparative-cost-network.toml on the classpath")

    val spec = NetworkSpec.fromToml(toml)
    println("Loaded '${spec.name}' with ${spec.costFormulations.size} cost formulations.")

    val m = Model("SupplyChain-Comparative-Cost")
    SupplyChainBuilder.build(m, spec)

    m.numberOfReplications = 30
    m.lengthOfReplication = 5400.0
    m.lengthOfReplicationWarmUp = 1800.0
    m.simulate()
    m.simulationReporter.printHalfWidthSummaryReport()
}
