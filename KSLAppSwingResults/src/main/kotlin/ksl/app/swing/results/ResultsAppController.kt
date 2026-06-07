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

import ksl.app.comparison.ExperimentRow
import ksl.app.comparison.KSLDatabaseComparisonSource
import ksl.app.session.AppWorkspacePaths
import ksl.app.settings.UserSettingsStore
import ksl.utilities.io.dbutil.DerbyDb
import ksl.utilities.io.dbutil.KSLDatabase
import ksl.utilities.io.dbutil.PostgresDb
import ksl.utilities.io.dbutil.SQLiteDb
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 *  Connection parameters for a server-based Postgres KSL database.  The
 *  [password] is used only for the duration of the connect call; the
 *  controller does not retain it.
 */
data class PostgresConnectionSpec(
    val server: String,
    val port: Int,
    val databaseName: String,
    val user: String,
    val password: String
)

/**
 *  Headless application state for the Results app.
 *
 *  Owns the currently-open [KSLDatabase], the derived
 *  [KSLDatabaseComparisonSource] (the seam every analysis tab reads
 *  experiment/response metadata through), and the output directory
 *  where generated reports are written.  No Swing types appear here;
 *  the frame and panels observe state changes via [addListener].
 *
 *  A database is opened by [openDatabase], which detects SQLite (a
 *  `.db` file) versus an embedded Derby database (a directory holding
 *  `service.properties`), opens it through the appropriate KSL opener,
 *  and wraps it in a [KSLDatabase].  Opening always uses the
 *  non-clearing [KSLDatabase] constructor — the database is treated as
 *  read-only result data and is never mutated by this app.
 */
class ResultsAppController(val appName: String) {

    var database: KSLDatabase? = null
        private set

    var databaseFile: File? = null
        private set

    var comparisonSource: KSLDatabaseComparisonSource? = null
        private set

    /** Kind of the open database — "SQLite", "Derby", or "Postgres".
     *  Tracked explicitly rather than inferred from [databaseFile],
     *  which is `null` for a server connection. */
    var databaseKind: String = ""
        private set

    /** Human-readable name of the open database — the file name for an
     *  embedded database, or `"<db>@<server>"` for a Postgres
     *  connection.  Used for headers, the window title, and the report
     *  output folder. */
    var databaseDisplayName: String = ""
        private set

    /** User-wide settings (working directory, recent lists), backed by
     *  `~/.ksl/settings.toml`.  Remembers the chosen working directory
     *  across sessions, defaulting to `~/Documents/KSLWork`.  Shared by
     *  the File-menu *Set Working Directory…* action and the workspace
     *  status bar. */
    val settingsStore: UserSettingsStore = UserSettingsStore()

    /** This app's home folder under the active workspace —
     *  `<KSLWork>/<appName>/`.  Recomputed from the (remembered)
     *  working directory each access so it always reflects the current
     *  workspace. */
    val appWorkspace: Path
        get() = AppWorkspacePaths.appWorkspaceDir(settingsStore.activeWorkspace(), appName)

    /** Creates this app's workspace folder (and its `KSLWork` parent) if
     *  missing, returning it.  Called at startup so file choosers open
     *  inside the workspace rather than falling back to the home
     *  directory when `KSLWork` has not been created yet. */
    fun ensureAppWorkspace(): Path {
        val dir = appWorkspace
        runCatching { Files.createDirectories(dir) }
        return dir
    }

    /** Directory where generated reports are written for the open
     *  database — `<KSLWork>/<appName>/output/<dbName>/reports/`.
     *  Computed on demand (never cached) so it tracks both the open
     *  database and the remembered working directory.  Created lazily by
     *  whatever writes into it. */
    val outputDir: Path
        get() = AppWorkspacePaths.reportsDir(appWorkspace, databaseDisplayName.ifBlank { "untitled" })

    val isDatabaseOpen: Boolean get() = database != null

    private val listeners = mutableListOf<() -> Unit>()

    /** Register a callback fired whenever the open database changes. */
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    private fun notifyChanged() {
        for (l in listeners) l()
    }

    /**
     *  Open the supplied SQLite `.db` file or Derby directory as a
     *  [KSLDatabase], rebuild the comparison source, point the output
     *  directory beside it, and notify listeners.
     *
     *  Throws on failure (not a KSL database, unreadable file, etc.) —
     *  the previous open database, if any, is left intact and the
     *  caller surfaces the error.
     */
    fun openDatabase(file: File) {
        val derby = file.isDirectory && File(file, "service.properties").exists()
        val opened = openEmbeddedKslDatabase(file, derby)
        database = opened
        databaseFile = file
        databaseKind = if (derby) "Derby" else "SQLite"
        databaseDisplayName = file.name
        comparisonSource = KSLDatabaseComparisonSource(opened)
        notifyChanged()
    }

    /**
     *  Connect to a server-based Postgres KSL database described by
     *  [spec], rebuild the comparison source, and notify listeners.
     *
     *  Delegates to [KSLDatabase.connectKSLDatabase], which verifies the
     *  `ksl_db` schema is present (throwing
     *  `ksl.utilities.io.dbutil.KSLDatabaseNotConfigured` otherwise) and
     *  points the connection at it.  Connects read-only — the
     *  `clearDataOption` is `false`, so data is never mutated.
     *
     *  Throws on failure (unreachable host, bad credentials, not a KSL
     *  database); the previous open database, if any, is left intact and
     *  the caller surfaces the error.
     */
    fun connectPostgres(spec: PostgresConnectionSpec) {
        val props = PostgresDb.createProperties(
            dbServerName = spec.server,
            dbName = spec.databaseName,
            user = spec.user,
            pWord = spec.password,
            portNumber = spec.port
        )
        val opened = KSLDatabase.connectKSLDatabase(clearDataOption = false, dBProperties = props)
        database = opened
        databaseFile = null
        databaseKind = "Postgres"
        databaseDisplayName = "${spec.databaseName}@${spec.server}"
        comparisonSource = KSLDatabaseComparisonSource(opened)
        notifyChanged()
    }

    /** Experiments available for analysis, in database order.  Empty
     *  when no database is open. */
    fun experiments(): List<ExperimentRow> =
        comparisonSource?.availableExperiments() ?: emptyList()

    /** Names of responses recorded as time series for [expName] —
     *  used by the navigator's time-series flag and by the Time Series
     *  tab's response picker. */
    fun timeSeriesResponseNames(expName: String): Set<String> =
        database?.timeSeriesResponseDataFor(expName)?.map { it.stat_name }?.toSet() ?: emptySet()

    /** Distinct in-simulation `HistogramResponse` names (the `HISTOGRAM`
     *  table's `response_name`) recorded for [expName], sorted.  Drives the
     *  navigator's HISTOGRAM rows and the Histograms & Frequencies tab. */
    fun histogramResponseNames(expName: String): List<String> =
        database?.histogramDataFor(expName)?.map { it.response_name }?.distinct()?.sorted() ?: emptyList()

    /** Distinct in-simulation `IntegerFrequencyResponse` names (the
     *  `FREQUENCY` table's `name`) recorded for [expName], sorted. */
    fun frequencyResponseNames(expName: String): List<String> =
        database?.frequencyDataFor(expName)?.map { it.name }?.distinct()?.sorted() ?: emptyList()

    /** A short one-line description of the open database for headers. */
    fun databaseSummary(): String {
        if (database == null) return "No database open"
        val exps = experiments()
        val responseCount = exps.sumOf { it.responses.size }
        return "$databaseDisplayName · $databaseKind · ${exps.size} experiment${if (exps.size == 1) "" else "s"}" +
            " · $responseCount response${if (responseCount == 1) "" else "s"}"
    }

    private fun openEmbeddedKslDatabase(file: File, derby: Boolean): KSLDatabase {
        val database = if (derby) {
            DerbyDb.openDatabase(file.toPath()).apply {
                // KSL stores its tables in the KSL_DB schema. DerbyDb.openDatabase
                // defaults the schema to "APP", which would make the KSLDatabase
                // table-existence check look in the wrong schema and wrongly report
                // the database as "not a KSLDatabase".  This mirrors what
                // KSLDatabase.createEmbeddedDerbyKSLDatabase does on creation.
                defaultSchemaName = DERBY_KSL_SCHEMA
            }
        } else {
            SQLiteDb.openDatabase(file.toPath())
        }
        // Non-clearing constructor: result data is never mutated here.
        return KSLDatabase(database)
    }

    private companion object {
        /** Schema that KSL uses for its tables in embedded Derby.  Derby
         *  folds unquoted identifiers to upper-case; must match
         *  `KSLDatabase`'s internal `SCHEMA_NAME`. */
        const val DERBY_KSL_SCHEMA = "KSL_DB"
    }
}
