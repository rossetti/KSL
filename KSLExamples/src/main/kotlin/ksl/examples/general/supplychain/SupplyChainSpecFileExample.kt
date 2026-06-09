package ksl.examples.general.supplychain

import ksl.modeling.supplychain.spec.NetworkSpec
import ksl.modeling.supplychain.spec.SupplyChainBuilder
import ksl.modeling.supplychain.spec.fromToml
import ksl.simulation.Model

/**
 * Loads a supply-chain network from a hand-authored `.toml` file and
 * runs it (DSL plan Phase D3).  The TOML resource
 * `me-ss-network.toml` describes the same warehouse-serves-five-retailers
 * (s, S) topology as `MultiEchelonNetworkSSPolicyExample`, but as data
 * rather than code; `NetworkSpec.fromToml` parses it and one
 * [SupplyChainBuilder.build] call instantiates the running model.
 *
 * The companion `me-ss-network.json` holds the same network in JSON;
 * swap `fromToml` for `fromJson` to load it instead.
 *
 * Run via
 * `gradle :KSLExamples:run -PmainClass=ksl.examples.general.supplychain.SupplyChainSpecFileExampleKt`
 * or by invoking `main` from an IDE.
 */
fun main() {
    val tomlText = object {}.javaClass
        .getResourceAsStream("/ksl/examples/general/supplychain/me-ss-network.toml")
        ?.bufferedReader()?.use { it.readText() }
        ?: error("could not find me-ss-network.toml on the classpath")

    val spec = NetworkSpec.fromToml(tomlText)
    println("Loaded network '${spec.name}': ${spec.nodes.size} nodes, ${spec.items.size} items.")

    val m = Model("SupplyChain-Spec-From-File")
    SupplyChainBuilder.build(m, spec)

    m.numberOfReplications = 30
    m.lengthOfReplication = 5400.0
    m.lengthOfReplicationWarmUp = 1800.0
    m.simulate()
    m.simulationReporter.printHalfWidthSummaryReport()
}
