/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.utilities.io.report.dsl

import ksl.utilities.io.report.ast.ReportNode

/**
 * Top-level DSL entry point for the KSL reporting framework.
 *
 * Constructs a [ReportNode.Document] (the AST root) by applying [block] to a
 * [ReportBuilder], then wrapping the accumulated children in a [ReportNode.Document].
 *
 * Usage:
 * ```kotlin
 * val doc = report("Drive-Through Pharmacy") {
 *     section("Across-Replication Statistics") {
 *         statTable(model.simulationReporter.acrossReplicationStatisticsList())
 *     }
 *     section("Histograms") {
 *         histogram(waitTimeHistogram)
 *     }
 * }
 * doc.showInBrowser()
 * doc.writeMarkdown()
 * ```
 *
 * The returned [ReportNode.Document] is a pure, immutable data structure. It can be
 * rendered any number of times by different [ksl.utilities.io.report.visitor.ReportVisitor]
 * implementations without rebuilding the tree.
 *
 * @param title the document title, used as the top-level heading by all renderers
 * @param block DSL block executed in the context of the root [ReportBuilder]
 * @return the assembled [ReportNode.Document]
 */
fun report(title: String, block: ReportBuilder.() -> Unit): ReportNode.Document {
    val myBuilder = ReportBuilder(title).apply(block)
    return ReportNode.Document(title, myBuilder.buildChildren())
}
