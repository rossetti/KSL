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

package ksl.utilities.io.report.visitor

import ksl.utilities.io.report.ast.ReportNode

/**
 * Visitor interface for the KSL report AST. Each method corresponds to exactly one
 * [ReportNode] subtype, providing compile-time assurance that every renderer handles
 * every node type.
 *
 * Structural nodes ([ReportNode.Document], [ReportNode.Section]) have paired
 * `enter`/`exit` methods so renderers can emit opening and closing markup around
 * their children. Leaf nodes have a single `visit` method.
 *
 * Implement this interface directly for renderers that must handle all node types
 * explicitly. Extend [AbstractReportVisitor] for renderers that only need to handle
 * a subset — all methods default to no-ops in the abstract base.
 */
interface ReportVisitor {
    fun enterDocument(node: ReportNode.Document)
    fun exitDocument(node: ReportNode.Document)
    fun enterSection(node: ReportNode.Section)
    fun exitSection(node: ReportNode.Section)
    fun visit(node: ReportNode.Heading)
    fun visit(node: ReportNode.Paragraph)
    fun visit(node: ReportNode.StatTable)
    fun visit(node: ReportNode.WeightedStatTable)
    fun visit(node: ReportNode.DataTable)
    fun visit(node: ReportNode.PlotNode)
    fun visit(node: ReportNode.RawText)
    fun visit(node: ReportNode.PageBreak)
}

/**
 * Convenience base class with no-op implementations of all [ReportVisitor] methods.
 * Extend this when only a subset of node types needs custom handling.
 */
abstract class AbstractReportVisitor : ReportVisitor {
    override fun enterDocument(node: ReportNode.Document)      {}
    override fun exitDocument(node: ReportNode.Document)       {}
    override fun enterSection(node: ReportNode.Section)        {}
    override fun exitSection(node: ReportNode.Section)         {}
    override fun visit(node: ReportNode.Heading)               {}
    override fun visit(node: ReportNode.Paragraph)             {}
    override fun visit(node: ReportNode.StatTable)             {}
    override fun visit(node: ReportNode.WeightedStatTable)     {}
    override fun visit(node: ReportNode.DataTable)             {}
    override fun visit(node: ReportNode.PlotNode)              {}
    override fun visit(node: ReportNode.RawText)               {}
    override fun visit(node: ReportNode.PageBreak)             {}
}
