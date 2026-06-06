/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.app.swing.results

import ksl.utilities.io.dbutil.KSLDatabase
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path

/** Output format for a database export. */
enum class ExportFormat { EXCEL, CSV, MARKDOWN, TEXT, SQL }

/** What subset of the database to export. */
enum class ExportScope { ALL_TABLES, ALL_VIEWS, SELECTED_TABLES, SELECTED_VIEWS }

/**
 *  Thin forwarder that surfaces the export capabilities a [KSLDatabase]
 *  already provides through `ksl.utilities.io.dbutil.DatabaseIOIfc` —
 *  Excel (tables), CSV (tables + views), Markdown, plain text, and SQL
 *  INSERT dumps.  No new analysis or serialization logic lives here; it
 *  only routes a (format, scope, selection, directory) request to the
 *  matching `DatabaseIOIfc` call and reports the outcome.
 *
 *  The read-only `importWorkbookToSchema` counterpart is deliberately
 *  **not** exposed — this app never writes to the database.
 *
 *  Both the `File ▸ Export` quick menu items and the Database tab's
 *  Export dialog call into this controller.
 */
class ResultsExportController(private val appController: ResultsAppController) {

    /** Result of an export attempt — surfaced through the host's notification sink. */
    data class Outcome(val ok: Boolean, val message: String)

    /** The fixed KSL table names, for the dialog's table checklist. */
    val tableNames: List<String> get() = KSLDatabase.TableNames

    /** The fixed KSL view names, for the dialog's view checklist. */
    val viewNames: List<String> get() = KSLDatabase.ViewNames

    /** True when a database is open and export is possible. */
    val canExport: Boolean get() = appController.database != null

    /**
     *  Run an export of [scope] in [format] to [dir].  [selected] is used
     *  only for the SELECTED_* scopes (table/view names).  Returns an
     *  [Outcome]; never throws.
     */
    fun export(
        format: ExportFormat,
        scope: ExportScope,
        selected: List<String>,
        dir: Path
    ): Outcome {
        val db = appController.database ?: return Outcome(false, "No database is open.")
        if (scope == ExportScope.SELECTED_TABLES || scope == ExportScope.SELECTED_VIEWS) {
            if (selected.isEmpty()) return Outcome(false, "Select at least one ${if (scope == ExportScope.SELECTED_VIEWS) "view" else "table"}.")
        }
        return try {
            Files.createDirectories(dir)
            when (format) {
                ExportFormat.EXCEL -> exportExcel(db, scope, selected, dir)
                ExportFormat.CSV -> exportCsv(db, scope, selected, dir)
                ExportFormat.MARKDOWN -> exportMarkdown(db, scope, selected, dir)
                ExportFormat.TEXT -> exportText(db, scope, selected, dir)
                ExportFormat.SQL -> exportSql(db, scope, selected, dir)
            }
        } catch (t: Throwable) {
            Outcome(false, "Export failed: ${t.message ?: t::class.simpleName ?: "unknown error"}")
        }
    }

    // ── Quick whole-database exports (File ▸ Export) ──────────────────────

    fun excelAllTables(dir: Path): Outcome = export(ExportFormat.EXCEL, ExportScope.ALL_TABLES, emptyList(), dir)
    fun csvAllTables(dir: Path): Outcome = export(ExportFormat.CSV, ExportScope.ALL_TABLES, emptyList(), dir)
    fun csvAllViews(dir: Path): Outcome = export(ExportFormat.CSV, ExportScope.ALL_VIEWS, emptyList(), dir)
    fun sqlDumpAllTables(dir: Path): Outcome = export(ExportFormat.SQL, ExportScope.ALL_TABLES, emptyList(), dir)

    // ── Per-format routing ────────────────────────────────────────────────

    private fun exportExcel(db: KSLDatabase, scope: ExportScope, selected: List<String>, dir: Path): Outcome {
        val base = exportBaseName(db)
        return when (scope) {
            ExportScope.ALL_TABLES -> {
                db.exportToExcel(wbName = base, wbDirectory = dir)
                Outcome(true, "Wrote Excel workbook $base.xlsx (all tables) to $dir")
            }
            ExportScope.SELECTED_TABLES -> {
                db.exportToExcel(tableNames = selected, wbName = base, wbDirectory = dir)
                Outcome(true, "Wrote Excel workbook $base.xlsx (${selected.size} table(s)) to $dir")
            }
            else -> Outcome(false, "Excel export covers tables only (the views export to CSV or Markdown).")
        }
    }

    private fun exportCsv(db: KSLDatabase, scope: ExportScope, selected: List<String>, dir: Path): Outcome =
        when (scope) {
            ExportScope.ALL_TABLES -> {
                db.exportAllTablesAsCSV(pathToOutPutDirectory = dir)
                Outcome(true, "Wrote ${tableNames.size} table CSV file(s) to $dir")
            }
            ExportScope.ALL_VIEWS -> {
                db.exportAllViewsAsCSV(pathToOutPutDirectory = dir)
                Outcome(true, "Wrote ${viewNames.size} view CSV file(s) to $dir")
            }
            ExportScope.SELECTED_TABLES, ExportScope.SELECTED_VIEWS -> {
                for (name in selected) {
                    writeVia(dir.resolve("$name.csv")) { db.exportTableAsCSV(tableName = name, out = it) }
                }
                Outcome(true, "Wrote ${selected.size} CSV file(s) to $dir")
            }
        }

    private fun exportMarkdown(db: KSLDatabase, scope: ExportScope, selected: List<String>, dir: Path): Outcome {
        val base = exportBaseName(db)
        return when (scope) {
            ExportScope.ALL_TABLES -> {
                writeVia(dir.resolve("${base}_Tables.md")) { db.writeAllTablesAsMarkdown(out = it) }
                Outcome(true, "Wrote ${base}_Tables.md to $dir")
            }
            ExportScope.ALL_VIEWS -> {
                writeVia(dir.resolve("${base}_Views.md")) { db.writeAllViewsAsMarkdown(out = it) }
                Outcome(true, "Wrote ${base}_Views.md to $dir")
            }
            ExportScope.SELECTED_TABLES, ExportScope.SELECTED_VIEWS -> {
                for (name in selected) {
                    writeVia(dir.resolve("$name.md")) { db.writeTableAsMarkdown(tableName = name, out = it) }
                }
                Outcome(true, "Wrote ${selected.size} Markdown file(s) to $dir")
            }
        }
    }

    private fun exportText(db: KSLDatabase, scope: ExportScope, selected: List<String>, dir: Path): Outcome {
        val base = exportBaseName(db)
        return when (scope) {
            ExportScope.ALL_TABLES -> {
                writeVia(dir.resolve("${base}_Tables.txt")) { db.writeAllTablesAsText(out = it) }
                Outcome(true, "Wrote ${base}_Tables.txt to $dir")
            }
            ExportScope.SELECTED_TABLES, ExportScope.SELECTED_VIEWS -> {
                for (name in selected) {
                    writeVia(dir.resolve("$name.txt")) { db.writeTableAsText(tableName = name, out = it) }
                }
                Outcome(true, "Wrote ${selected.size} text file(s) to $dir")
            }
            ExportScope.ALL_VIEWS -> Outcome(false, "Text export of all views is not available; pick CSV or Markdown for views.")
        }
    }

    private fun exportSql(db: KSLDatabase, scope: ExportScope, selected: List<String>, dir: Path): Outcome {
        val base = exportBaseName(db)
        return when (scope) {
            ExportScope.ALL_TABLES -> {
                writeVia(dir.resolve("${base}_TableInserts.sql")) { db.exportAllTablesAsInsertQueries(out = it) }
                Outcome(true, "Wrote ${base}_TableInserts.sql to $dir")
            }
            ExportScope.SELECTED_TABLES -> {
                for (name in selected) {
                    writeVia(dir.resolve("${name}_Inserts.sql")) { db.exportInsertQueries(tableName = name, out = it) }
                }
                Outcome(true, "Wrote ${selected.size} SQL file(s) to $dir")
            }
            else -> Outcome(false, "SQL INSERT export covers tables only.")
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun writeVia(file: Path, block: (PrintWriter) -> Unit) {
        PrintWriter(file.toFile()).use { block(it); it.flush() }
    }

    /** The database label without any trailing extension, used as the
     *  workbook / file base name. */
    private fun exportBaseName(db: KSLDatabase): String = db.label.substringBeforeLast(".")
}
