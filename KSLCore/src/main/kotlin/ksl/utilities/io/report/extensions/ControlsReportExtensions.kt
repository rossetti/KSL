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

package ksl.utilities.io.report.extensions

import ksl.controls.ControlData
import ksl.controls.Controls
import ksl.controls.JsonControlData
import ksl.controls.ModelControlsExport
import ksl.controls.StringControlData
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.ReportBuilder
import ksl.utilities.io.report.dsl.report

/**
 * DSL extension functions on [ReportBuilder] for documenting and inspecting
 * KSL model controls across all three families.
 *
 * The primary entry points are the two [controlsReport] overloads, exposed
 * through convenience extensions on the domain types:
 *
 * - **`Controls.toReport()`** — the most common call; takes the live
 *   [Controls] object returned by `model.controls()` and snapshots it via
 *   [Controls.exportAll] before building the report.
 * - **`ModelControlsExport.toReport()`** — lower-level entry point for
 *   offline reporting from a previously saved JSON snapshot.
 *
 * Two views are available via the `groupByElement` flag:
 *
 * - **Flat family view** (`groupByElement = false`, default) — one section per
 *   control family (numeric, string, JSON), each containing a single table of
 *   all controls in that family.  Good for a quick inventory.
 * - **Element-grouped view** (`groupByElement = true`) — one section per model
 *   element, each containing sub-tables for whichever families that element
 *   contributes to.  Good when many elements share the same class and you want
 *   to inspect one entity at a time.
 *
 * Granular DSL methods are also available for embedding controls tables inside
 * a larger report alongside simulation output or Welch analyses:
 *
 * ```kotlin
 * report("Full Model Report") {
 *     simulationSummary(reporter)
 *     section("Model Parameters") {
 *         paragraph("Controls captured before the simulation run.")
 *         numericControlsTable(export.numericControls)
 *         stringControlsTable(export.stringControls)
 *         jsonControlsTable(export.jsonControls)
 *     }
 * }
 * ```
 *
 * Zero-code usage:
 * ```kotlin
 * model.controls().toReport().showInBrowser()
 * model.controls().toReport(groupByElement = true).writeMarkdown()
 * ```
 *
 * Offline snapshot usage:
 * ```kotlin
 * val export = Controls.exportJson.decodeFromString<ModelControlsExport>(savedJson)
 * export.toReport().showInBrowser()
 * ```
 */

// ── Private formatting helpers ────────────────────────────────────────────────

/** Formats a finite [Double] to 4 decimal places; returns `"—"` for NaN or infinite. */
private fun fmtNum(v: Double): String = when {
    v.isNaN() || v.isInfinite() -> "\u2014"
    else -> "%.4f".format(v)
}

/**
 * Formats a bound [Double]:
 * - [Double.NEGATIVE_INFINITY] → `"−∞"`
 * - [Double.POSITIVE_INFINITY] → `"+∞"`
 * - NaN → `"—"`
 * - finite → 4 decimal places
 */
private fun fmtBound(v: Double): String = when {
    v == Double.NEGATIVE_INFINITY -> "\u2212\u221E"
    v == Double.POSITIVE_INFINITY -> "+\u221E"
    v.isNaN()                     -> "\u2014"
    else                          -> "%.4f".format(v)
}

/**
 * Truncates [v] to [maxWidth] characters and appends `"…"` if truncated;
 * returns [v] verbatim when it fits within [maxWidth].
 */
private fun fmtJson(v: String, maxWidth: Int): String =
    if (v.length <= maxWidth) v else v.take(maxWidth) + "\u2026"

/**
 * Renders [values] as a comma-joined string, or `"(any)"` when the list is
 * empty (indicating an unconstrained string control).
 */
private fun fmtAllowed(values: List<String>): String =
    if (values.isEmpty()) "(any)" else values.joinToString(", ")

// ── Granular DSL methods ──────────────────────────────────────────────────────

/**
 * Appends a table of numeric controls from [controls].
 *
 * Columns: **Key | Type | Value | Lower Bound | Upper Bound | Comment**
 * (Comment column omitted when [includeComment] is `false`).
 *
 * Bound values of `±∞` are rendered as `"−∞"` / `"+∞"` rather than the raw
 * `Infinity` strings that appear when no explicit bounds were declared on a
 * `@KSLControl` annotation.  Finite values are formatted to 4 decimal places.
 *
 * If [controls] is empty a short paragraph noting the absence is emitted
 * instead of an empty table.
 *
 * @param controls       list of [ControlData] DTOs to tabulate
 * @param caption        optional table caption; defaults to `"Numeric Controls"`
 * @param includeComment `false` omits the Comment column
 */
fun ReportBuilder.numericControlsTable(
    controls:       List<ControlData>,
    caption:        String?  = "Numeric Controls",
    includeComment: Boolean  = true,
) {
    if (controls.isEmpty()) {
        paragraph("No numeric controls.")
        return
    }
    val headers = buildList {
        add("Key"); add("Type"); add("Value"); add("Lower Bound"); add("Upper Bound")
        if (includeComment) add("Comment")
    }
    val rows = controls.map { cd ->
        buildList {
            add(cd.keyName)
            add(cd.controlType.name)
            add(fmtNum(cd.value))
            add(fmtBound(cd.lowerBound))
            add(fmtBound(cd.upperBound))
            if (includeComment) add(cd.comment)
        }
    }
    dataTable(headers, rows, caption)
}

/**
 * Appends a table of string controls from [controls].
 *
 * Columns: **Key | Value | Allowed Values | Comment**
 * (Allowed Values column omitted when [includeAllowedValues] is `false`;
 * Comment column omitted when [includeComment] is `false`).
 *
 * The Allowed Values column renders the permitted set as a comma-joined string,
 * or `"(any)"` for unconstrained controls whose `allowedValues` list is empty.
 *
 * If [controls] is empty a short paragraph noting the absence is emitted
 * instead of an empty table.
 *
 * @param controls             list of [StringControlData] DTOs to tabulate
 * @param caption              optional table caption; defaults to `"String Controls"`
 * @param includeAllowedValues `false` omits the Allowed Values column
 * @param includeComment       `false` omits the Comment column
 */
fun ReportBuilder.stringControlsTable(
    controls:             List<StringControlData>,
    caption:              String?  = "String Controls",
    includeAllowedValues: Boolean  = true,
    includeComment:       Boolean  = true,
) {
    if (controls.isEmpty()) {
        paragraph("No string controls.")
        return
    }
    val headers = buildList {
        add("Key"); add("Value")
        if (includeAllowedValues) add("Allowed Values")
        if (includeComment) add("Comment")
    }
    val rows = controls.map { sd ->
        buildList {
            add(sd.keyName); add(sd.value)
            if (includeAllowedValues) add(fmtAllowed(sd.allowedValues))
            if (includeComment) add(sd.comment)
        }
    }
    dataTable(headers, rows, caption)
}

/**
 * Appends a table of JSON controls from [controls].
 *
 * Columns: **Key | Type Hint | Value | Comment**
 * (Comment column omitted when [includeComment] is `false`).
 *
 * The Type Hint column appears before the Value column because it conveys the
 * expected Kotlin type and is more useful for at-a-glance inspection than the
 * raw JSON string.  The Value column is truncated to [jsonValueWidth] characters
 * and suffixed with `"…"` when the JSON string exceeds that width.
 *
 * If [controls] is empty a short paragraph noting the absence is emitted
 * instead of an empty table.
 *
 * @param controls       list of [JsonControlData] DTOs to tabulate
 * @param caption        optional table caption; defaults to `"JSON Controls"`
 * @param jsonValueWidth maximum characters shown in the Value column before truncation
 * @param includeComment `false` omits the Comment column
 */
fun ReportBuilder.jsonControlsTable(
    controls:       List<JsonControlData>,
    caption:        String?  = "JSON Controls",
    jsonValueWidth: Int      = 60,
    includeComment: Boolean  = true,
) {
    if (controls.isEmpty()) {
        paragraph("No JSON controls.")
        return
    }
    val headers = buildList {
        add("Key"); add("Type Hint"); add("Value")
        if (includeComment) add("Comment")
    }
    val rows = controls.map { jd ->
        buildList {
            add(jd.keyName); add(jd.typeHint); add(fmtJson(jd.jsonValue, jsonValueWidth))
            if (includeComment) add(jd.comment)
        }
    }
    dataTable(headers, rows, caption)
}

// ── Composite DSL method ──────────────────────────────────────────────────────

/**
 * Appends a full controls report section for [export].
 *
 * Always begins with a summary paragraph stating the model name, total control
 * count, element count, and per-family breakdown.  The body then follows one
 * of two layouts controlled by [groupByElement]:
 *
 * **Flat family view** (`groupByElement = false`, default):
 * Three sub-sections in sequence — Numeric, String, JSON — each omitted
 * entirely when the corresponding family list is empty.
 *
 * **Element-grouped view** (`groupByElement = true`):
 * One sub-section per model element (sorted alphabetically), each containing
 * only the families that element contributes to.  Table captions carry the
 * element name (e.g. `"Van_0 — Numeric"`) so content remains self-identifying
 * when sections span multiple pages.
 *
 * @param export               the controls snapshot to report
 * @param groupByElement       `true` for element-grouped layout; `false` for flat family layout
 * @param includeAllowedValues passed through to [stringControlsTable]
 * @param jsonValueWidth       passed through to [jsonControlsTable]
 * @param includeComment       passed through to all three table methods
 */
fun ReportBuilder.controlsReport(
    export:               ModelControlsExport,
    groupByElement:       Boolean = false,
    includeAllowedValues: Boolean = true,
    jsonValueWidth:       Int     = 60,
    includeComment:       Boolean = true,
) {
    val elementCount = (
        export.numericControls.map { it.elementName } +
        export.stringControls.map  { it.elementName } +
        export.jsonControls.map    { it.elementName }
    ).toSet().size

    val elementWord = if (elementCount == 1) "element" else "elements"
    paragraph(
        "${export.modelName}: ${export.totalControls} controls across " +
        "$elementCount model $elementWord — " +
        "${export.numericControls.size} numeric, " +
        "${export.stringControls.size} string, " +
        "${export.jsonControls.size} JSON."
    )

    if (groupByElement) {
        val elementNames = (
            export.numericControls.map { it.elementName } +
            export.stringControls.map  { it.elementName } +
            export.jsonControls.map    { it.elementName }
        ).toSortedSet()

        for (elementName in elementNames) {
            section(elementName) {
                val numeric = export.numericControls.filter { it.elementName == elementName }
                val string  = export.stringControls.filter  { it.elementName == elementName }
                val json    = export.jsonControls.filter    { it.elementName == elementName }

                if (numeric.isNotEmpty()) {
                    numericControlsTable(
                        numeric,
                        caption        = "$elementName \u2014 Numeric",
                        includeComment = includeComment,
                    )
                }
                if (string.isNotEmpty()) {
                    stringControlsTable(
                        string,
                        caption              = "$elementName \u2014 String",
                        includeAllowedValues = includeAllowedValues,
                        includeComment       = includeComment,
                    )
                }
                if (json.isNotEmpty()) {
                    jsonControlsTable(
                        json,
                        caption        = "$elementName \u2014 JSON",
                        jsonValueWidth = jsonValueWidth,
                        includeComment = includeComment,
                    )
                }
            }
        }
    } else {
        if (export.numericControls.isNotEmpty()) {
            section("Numeric Controls (${export.numericControls.size})") {
                numericControlsTable(
                    export.numericControls,
                    caption        = "Numeric Controls",
                    includeComment = includeComment,
                )
            }
        }
        if (export.stringControls.isNotEmpty()) {
            section("String Controls (${export.stringControls.size})") {
                stringControlsTable(
                    export.stringControls,
                    caption              = "String Controls",
                    includeAllowedValues = includeAllowedValues,
                    includeComment       = includeComment,
                )
            }
        }
        if (export.jsonControls.isNotEmpty()) {
            section("JSON Controls (${export.jsonControls.size})") {
                jsonControlsTable(
                    export.jsonControls,
                    caption        = "JSON Controls",
                    jsonValueWidth = jsonValueWidth,
                    includeComment = includeComment,
                )
            }
        }
    }
}

// ── Convenience extensions on domain types ────────────────────────────────────

/**
 * Builds a [ReportNode.Document] for this [Controls] instance.
 *
 * [Controls.exportAll] is called once to snapshot the current state; the
 * resulting [ModelControlsExport] is passed to [controlsReport].
 *
 * The default [block] calls [controlsReport] with the supplied parameters.
 * A custom [block] replaces the default content entirely.
 *
 * Zero-code usage:
 * ```kotlin
 * model.controls().toReport().showInBrowser()
 * model.controls().toReport(groupByElement = true).writeMarkdown()
 * ```
 *
 * Custom block:
 * ```kotlin
 * model.controls().toReport("Pharmacy Model — Parameters") {
 *     paragraph("Controls captured before replication 1.")
 *     val export = this@toReport.exportAll()
 *     numericControlsTable(export.numericControls)
 *     stringControlsTable(export.stringControls, includeAllowedValues = false)
 * }
 * ```
 *
 * @param title                document title; defaults to `"${modelName} — Controls"`
 * @param groupByElement       `true` for element-grouped layout
 * @param includeAllowedValues passed through to [stringControlsTable]
 * @param jsonValueWidth       passed through to [jsonControlsTable]
 * @param includeComment       passed through to all three table methods
 * @param block                optional DSL block; replaces the default content when provided
 */
fun Controls.toReport(
    title:                String?  = null,
    groupByElement:       Boolean  = false,
    includeAllowedValues: Boolean  = true,
    jsonValueWidth:       Int      = 60,
    includeComment:       Boolean  = true,
    block: ReportBuilder.() -> Unit = run {
        val export = this.exportAll()
        val lambda: ReportBuilder.() -> Unit = {
            controlsReport(
                export               = export,
                groupByElement       = groupByElement,
                includeAllowedValues = includeAllowedValues,
                jsonValueWidth       = jsonValueWidth,
                includeComment       = includeComment,
            )
        }
        lambda
    },
): ReportNode.Document {
    val export       = this.exportAll()
    val reportTitle  = title ?: "${export.modelName} \u2014 Controls"
    return report(reportTitle, block)
}

/**
 * Builds a [ReportNode.Document] for this [ModelControlsExport].
 *
 * Enables offline reporting from a saved JSON snapshot without a live model:
 * ```kotlin
 * val export = Controls.exportJson.decodeFromString<ModelControlsExport>(savedJson)
 * export.toReport().showInBrowser()
 * ```
 *
 * The default [block] calls [controlsReport] with the supplied parameters.
 * A custom [block] replaces the default content entirely.
 *
 * @param title                document title; defaults to `"${modelName} — Controls"`
 * @param groupByElement       `true` for element-grouped layout
 * @param includeAllowedValues passed through to [stringControlsTable]
 * @param jsonValueWidth       passed through to [jsonControlsTable]
 * @param includeComment       passed through to all three table methods
 * @param block                optional DSL block; replaces the default content when provided
 */
fun ModelControlsExport.toReport(
    title:                String?  = null,
    groupByElement:       Boolean  = false,
    includeAllowedValues: Boolean  = true,
    jsonValueWidth:       Int      = 60,
    includeComment:       Boolean  = true,
    block: ReportBuilder.() -> Unit = {
        controlsReport(
            export               = this@toReport,
            groupByElement       = groupByElement,
            includeAllowedValues = includeAllowedValues,
            jsonValueWidth       = jsonValueWidth,
            includeComment       = includeComment,
        )
    },
): ReportNode.Document {
    val reportTitle = title ?: "${this.modelName} \u2014 Controls"
    return report(reportTitle, block)
}
