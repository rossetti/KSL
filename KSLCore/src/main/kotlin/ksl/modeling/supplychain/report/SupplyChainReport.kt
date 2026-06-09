package ksl.modeling.supplychain.report

import ksl.modeling.supplychain.cost.CostFormulation
import ksl.modeling.supplychain.cost.CostLine
import ksl.modeling.supplychain.cost.NodeTier
import ksl.modeling.supplychain.inventory.InventoryHoldingPoint
import ksl.modeling.supplychain.network.MultiEchelonNetwork
import ksl.modeling.supplychain.network.TransportStrategy
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.ModelElement
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.ReportBuilder
import ksl.utilities.io.report.dsl.report

/**
 * Structured results reporting for a [MultiEchelonNetwork] (DSL plan
 * follow-up).  A multi-echelon run produces hundreds of flat responses
 * — the default half-width summary is exhaustive but hard to read.
 * These helpers organize the same numbers into a small set of focused,
 * digestible tables on top of the KSL report framework
 * (`ksl.utilities.io.report`), so the output renders to Markdown, HTML,
 * or text and collapses to only the rows/columns the topology actually
 * produces.
 *
 * Top-level entry point:
 *
 * ```kotlin
 * val doc = network.resultsReport()        // after model.simulate()
 * println(doc.toMarkdown())                // or: doc.writeHtml(); doc.showInBrowser()
 * ```
 *
 * Or compose the individual sections into a larger report:
 *
 * ```kotlin
 * val doc = report("Study") {
 *     supplyChainOverview(network)
 *     supplyChainCostSummary(network)
 *     supplyChainInventoryPerformance(network)
 * }
 * ```
 *
 * The numbers are across-replication averages (with half-widths where a
 * confidence interval is most useful — the cost totals); read them after
 * `simulate()`.  Cells that the model never produced (NaN before a run,
 * or a structurally-absent tier/line) are omitted or shown as `-`.
 */

// -- formatting helpers ------------------------------------------------

private fun num(v: Double): String = if (v.isFinite()) "%,.2f".format(v) else "-"
private fun pct(v: Double): String = if (v.isFinite()) "%.1f%%".format(100.0 * v) else "-"

private fun ResponseCIfc.avgOrZero(): Double = acrossReplicationStatistic.average
private fun ResponseCIfc.halfWidthAt(level: Double): Double =
    acrossReplicationStatistic.halfWidth(level)

private fun CostFormulation.displayName(): String =
    (this as? ModelElement)?.name ?: "Cost Formulation"

private fun MultiEchelonNetwork.strategyName(): String = when (transportStrategy) {
    is TransportStrategy.SharedCarrier -> "SharedCarrier"
    is TransportStrategy.PerIHPTimeBased -> "PerIHPTimeBased"
    is TransportStrategy.NetworkTimeBased -> "NetworkTimeBased"
}

// -- sections ----------------------------------------------------------

/**
 * A topology overview section: a one-line summary plus a node table
 * (name, kind, level, supplier) and an item table (unit cost, weight,
 * cube).
 */
fun ReportBuilder.supplyChainOverview(network: MultiEchelonNetwork) {
    section("Network Overview") {
        val ihps = network.getInventoryHoldingPoints()
        val cds = network.getInventoryCrossDocks()
        paragraph(
            "Network '${network.name}' - transport strategy " +
                "${network.strategyName()}; ${ihps.size} inventory holding " +
                "point(s), ${cds.size} cross-dock(s), " +
                "${network.itemTypes.size} item type(s).",
        )
        val nodeRows = network.getNodes().sortedBy { it.level }.map { node ->
            listOf(
                node.name,
                if (node is InventoryHoldingPoint) "IHP" else "CD",
                node.level.toString(),
                node.demandFiller?.name ?: "—",
            )
        }
        dataTable(listOf("Node", "Type", "Level", "Supplied by"), nodeRows, "Topology")

        val itemRows = network.itemTypes.map { item ->
            listOf(item.name, num(item.unitCost), num(item.weight), num(item.cube))
        }
        if (itemRows.isNotEmpty()) {
            dataTable(listOf("Item", "Unit cost", "Weight", "Cube"), itemRows, "Items")
        }
    }
}

/**
 * A cost-summary section: for each attached [CostFormulation], a compact
 * **tier × line** matrix of average costs (only the tiers and lines the
 * topology actually produces), a TOTAL row, and the grand total with a
 * confidence half-width.  When more than one formulation is attached, a
 * grand-total comparison table is appended for the comparative study.
 *
 * @param confidenceLevel level for the reported cost half-widths
 */
fun ReportBuilder.supplyChainCostSummary(
    network: MultiEchelonNetwork,
    confidenceLevel: Double = 0.95,
) {
    val formulations = network.costFormulations
    if (formulations.isEmpty()) return

    section("Cost Summary") {
        for (f in formulations) {
            heading(f.displayName(), level = 3)

            fun active(v: Double) = v.isFinite() && v != 0.0
            val tiers = NodeTier.all.filter { active(f.byTierResponse(it)?.avgOrZero() ?: 0.0) }
            val lines = CostLine.all.filter { active(f.byLineResponse(it)?.avgOrZero() ?: 0.0) }
            if (lines.isEmpty()) {
                paragraph("No non-zero cost lines (was the model simulated?).")
                continue
            }

            val headers = listOf("Cost line") + tiers.map { it.displayName } + "Total"
            val rows = lines.map { line ->
                listOf(line.displayName) +
                    tiers.map { tier -> num(f.byTierAndLineResponse(tier, line)?.avgOrZero() ?: 0.0) } +
                    num(f.byLineResponse(line)?.avgOrZero() ?: 0.0)
            }
            val totalRow = listOf("TOTAL") +
                tiers.map { num(f.byTierResponse(it)?.avgOrZero() ?: 0.0) } +
                num(f.totalCostResponse.avgOrZero())
            dataTable(headers, rows + listOf(totalRow), "Average cost by tier x line")

            val gt = f.totalCostResponse
            paragraph(
                "Grand total: ${num(gt.avgOrZero())} +/- ${num(gt.halfWidthAt(confidenceLevel))} " +
                    "(${(confidenceLevel * 100).toInt()}% half-width).",
            )
        }

        if (formulations.size > 1) {
            heading("Comparison", level = 3)
            val rows = formulations.map { f ->
                listOf(
                    f.displayName(),
                    num(f.totalCostResponse.avgOrZero()),
                    num(f.totalCostResponse.halfWidthAt(confidenceLevel)),
                )
            }
            dataTable(
                listOf("Formulation", "Grand total", "+/- Half-width"),
                rows, "Grand-total comparison",
            )
        }
    }
}

/**
 * An inventory-performance section: one row per inventory holding point
 * (sorted by level) with the aggregate replication-average on-hand,
 * on-order, backordered, first-fill-rate, and customer wait time.
 */
fun ReportBuilder.supplyChainInventoryPerformance(
    network: MultiEchelonNetwork,
    @Suppress("UNUSED_PARAMETER") confidenceLevel: Double = 0.95,
) {
    val ihps = network.getInventoryHoldingPoints()
    if (ihps.isEmpty()) return

    section("Inventory Performance") {
        val headers = listOf(
            "IHP", "Level", "On hand", "On order", "Backordered", "Fill rate", "Avg wait",
        )
        val rows = ihps.sortedBy { it.level }.map { ihp ->
            listOf(
                ihp.name,
                ihp.level.toString(),
                num(ihp.aggregateOnHandInventory.avgOrZero()),
                num(ihp.aggregateAmountOnOrder.avgOrZero()),
                num(ihp.aggregateAmountBackOrdered.avgOrZero()),
                pct(ihp.aggregateAvgFirstFillRate.avgOrZero()),
                num(ihp.aggregateAvgCustomerWaitTime.avgOrZero()),
            )
        }
        dataTable(headers, rows, "Per-IHP aggregate performance (replication averages)")
    }
}

// -- top-level assembly ------------------------------------------------

/**
 * Build a complete results report for this network: a network overview,
 * a cost summary (per formulation, tier × line), and an inventory
 * performance table.  Call after `model.simulate()`, then render with
 * the standard KSL helpers (`toMarkdown()`, `writeHtml()`,
 * `showInBrowser()`, …).
 *
 * @param title report title
 * @param confidenceLevel level for reported cost half-widths
 * @return the report document (render with the KSL report extensions)
 */
fun MultiEchelonNetwork.resultsReport(
    title: String = "$name - Results",
    confidenceLevel: Double = 0.95,
): ReportNode.Document {
    val net = this
    return report(title) {
        supplyChainOverview(net)
        supplyChainCostSummary(net, confidenceLevel)
        supplyChainInventoryPerformance(net, confidenceLevel)
    }
}
