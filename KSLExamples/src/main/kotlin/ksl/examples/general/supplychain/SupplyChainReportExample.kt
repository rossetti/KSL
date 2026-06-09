package ksl.examples.general.supplychain

import ksl.modeling.supplychain.report.resultsReport
import ksl.modeling.supplychain.spec.NetworkSpec
import ksl.modeling.supplychain.spec.SupplyChainBuilder
import ksl.modeling.supplychain.spec.fromToml
import ksl.simulation.Model
import ksl.utilities.io.report.toMarkdown
import ksl.utilities.io.report.writeHtml

/**
 * Demonstrates structured results reporting for a multi-echelon network
 * (DSL plan follow-up).  A supply-chain run emits hundreds of flat
 * responses; `network.resultsReport()` organizes the same numbers into a
 * few focused tables — a topology overview, a compact tier × line cost
 * matrix per formulation (plus a comparison when several are attached),
 * and a per-IHP inventory-performance table.
 *
 * The report is a `ReportNode.Document`, so it renders to Markdown, HTML,
 * or text with the standard KSL helpers.  Here we print the Markdown and
 * also write a standalone HTML file.
 *
 * Run via
 * `gradle :KSLExamples:run -PmainClass=ksl.examples.general.supplychain.SupplyChainReportExampleKt`
 * or by invoking `main` from an IDE.
 */
fun main() {
    // Reuse the authored comparative-cost network (two Default formulations
    // plus a per-node one) so the report shows a comparative study.
    val toml = object {}.javaClass
        .getResourceAsStream("/ksl/examples/general/supplychain/comparative-cost-network.toml")
        ?.bufferedReader()?.use { it.readText() }
        ?: error("could not find comparative-cost-network.toml on the classpath")

    val spec = NetworkSpec.fromToml(toml)
    val m = Model("SupplyChain-Report")
    val result = SupplyChainBuilder.build(m, spec)

    m.numberOfReplications = 30
    m.lengthOfReplication = 5400.0
    m.lengthOfReplicationWarmUp = 1800.0
    m.simulate()

    val doc = result.network.resultsReport()

    // Markdown to the console (compact, readable)...
    println(doc.toMarkdown())

    // ...and a standalone HTML file under the model's output directory.
    val html = doc.writeHtml()
    println("\nWrote HTML report: ${html.absolutePath}")

    // The exhaustive half-width report is still available if you want it:
    // m.simulationReporter.printHalfWidthSummaryReport()
}
