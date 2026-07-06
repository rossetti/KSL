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

package ksl.service.capability.dbanalysis

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ksl.app.comparison.AnalysisType
import ksl.app.comparison.ComparisonDataSourceIfc
import ksl.app.comparison.ComparisonSelectionModel
import ksl.app.config.ReportFormat
import ksl.app.results.comparison.ComparisonReportRenderer
import ksl.app.comparison.ExperimentRow
import ksl.app.comparison.KSLDatabaseComparisonSource
import ksl.app.comparison.ResponseRow
import ksl.utilities.io.dbutil.DerbyDb
import ksl.utilities.io.dbutil.KSLDatabase
import ksl.utilities.io.dbutil.SQLiteDb
import ksl.utilities.io.report.extensions.toReport
import ksl.utilities.io.report.renderer.RenderContext
import ksl.utilities.io.report.writeHtml
import ksl.utilities.io.report.writeMarkdown
import ksl.utilities.io.report.writeText
import ksl.utilities.statistic.MCBIntervalData
import ksl.utilities.statistic.MCBScreeningIntervalData
import ksl.utilities.statistic.MultipleComparisonAnalyzer
import ksl.utilities.statistic.asMCBIntervalDataFrame
import ksl.utilities.statistic.asMCBResultDataFrame
import ksl.utilities.statistic.asMCBScreeningIntervalDataFrame
import ksl.controls.experiments.LinearModel
import ksl.utilities.random.rvariable.parameters.RVParameterSetter
import ksl.utilities.io.addColumnsFor
import ksl.utilities.io.toDataFrame
import ksl.utilities.statistic.OLSRegression
import ksl.utilities.statistic.RegressionData
import ksl.utilities.statistic.RegressionResultsIfc
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.columnNames
import org.jetbrains.kotlinx.dataframe.api.filter
import org.jetbrains.kotlinx.dataframe.api.remove
import org.jetbrains.kotlinx.dataframe.api.take
import org.jetbrains.kotlinx.dataframe.api.toDataFrame
import org.jetbrains.kotlinx.dataframe.io.toJson
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Wire projection of [ExperimentRow]; the domain type is not @Serializable. */
@Serializable
data class ExperimentInfoDto(
    val name: String,
    val modelIdentifier: String,
    val numReplications: Int,
    val responses: List<ResponseInfoDto>,
)

/** Wire projection of [ResponseRow]. */
@Serializable
data class ResponseInfoDto(
    val name: String,
    val category: String,
)

/** An opened, read-only database session managed by [DatabaseAnalysisService]. */
class DbHandle internal constructor(
    val id: String,
    internal val database: KSLDatabase,
    internal val source: KSLDatabaseComparisonSource,
    val label: String,
)

/**
 * The service-core surface for capability C — analyzing a saved results
 * database (strategic plan §5.7).
 *
 * Unlike the run and fit capabilities, this one is **not** a submit-and-stream
 * job: it is *open-then-query*, so it sits beside the generic job spine rather
 * than on it. It opens a `KSLDatabase` (SQLite file or embedded Derby
 * directory) **read-only**, exposes its experiments / responses / per-replication
 * observations through the existing [ComparisonDataSourceIfc] seam, and never
 * mutates the database.
 *
 * Connection lifecycle is intentionally minimal in this first cut: handles are
 * tracked and dropped on [close]; richer pooling/eviction can follow if a
 * long-running server needs it.
 */
class DatabaseAnalysisService : AutoCloseable {

    private val handles = ConcurrentHashMap<String, DbHandle>()

    /**
     * Opens an embedded database at [path] read-only: a SQLite `.db` file, or an
     * embedded Derby directory (detected by a `service.properties` entry).
     */
    fun open(path: Path): DbHandle {
        require(Files.exists(path)) { "No database found at $path" }
        val derby = Files.isDirectory(path) && Files.exists(path.resolve("service.properties"))
        val database =
            if (derby) KSLDatabase(DerbyDb.openDatabase(path)) else KSLDatabase(SQLiteDb.openDatabase(path))
        return attach(database, path.fileName.toString())
    }

    /**
     * Wraps an already-open [KSLDatabase] (e.g. one a caller produced in-process)
     * as a tracked handle. The eager [KSLDatabaseComparisonSource] snapshot reads
     * the experiment/observation metadata up front.
     */
    fun attach(database: KSLDatabase, label: String = "database"): DbHandle {
        val handle = DbHandle(
            id = UUID.randomUUID().toString(),
            database = database,
            source = KSLDatabaseComparisonSource(database),
            label = label,
        )
        handles[handle.id] = handle
        return handle
    }

    /** Every experiment recorded in the database. */
    fun listExperiments(handle: DbHandle): List<ExperimentInfoDto> =
        handle.source.availableExperiments().map { it.toDto() }

    /** The responses recorded by one experiment, or empty if unknown. */
    fun listResponses(handle: DbHandle, experimentName: String): List<ResponseInfoDto> =
        handle.source.availableExperiments()
            .firstOrNull { it.name == experimentName }
            ?.responses?.map { it.toDto() }
            ?: emptyList()

    /** Per-replication observations for one response, or null if absent. */
    fun observations(handle: DbHandle, experimentName: String, responseName: String): DoubleArray? =
        handle.source.observations(experimentName, responseName)

    /** The underlying comparison source, for callers that drive MCB / CI analyses directly. */
    fun comparisonSource(handle: DbHandle): ComparisonDataSourceIfc = handle.source

    // ----- JSON projections (DataFrame.toJson over the existing analysis layer) -----

    /**
     * Across-replication summary statistics for [experimentName] as JSON (one
     * object per response: average, std error, CI half-width, count, min/max, …).
     * Reuses `KSLDatabase.acrossRepStatDataFor` and the DataFrame JSON writer —
     * no bespoke serialization. An unknown experiment yields `[]`.
     */
    fun acrossReplicationSummaryJson(handle: DbHandle, experimentName: String, prettyPrint: Boolean = false): String =
        handle.database.acrossRepStatDataFor(experimentName).toDataFrame().dropDbBookkeeping().toJson(prettyPrint)

    /**
     * Multiple-comparison (MCB) analysis of [responseName] across [experimentNames]
     * (null = every experiment recording the response), as a JSON document
     * `{response, delta, level, results, intervals, screening}` where each array is
     * the `DataFrame.toJson()` of the analyzer's existing MCB data. Preconditions
     * are validated through `ComparisonSelectionModel`, so an unanalyzable request
     * returns [DbQueryResult.Invalid] with the explanation rather than throwing.
     */
    fun comparisonJson(
        handle: DbHandle,
        responseName: String,
        experimentNames: List<String>? = null,
        delta: Double = 0.0,
        level: Double = 0.95,
        prettyPrint: Boolean = false,
    ): DbQueryResult = comparisonJson(handle.source, responseName, experimentNames, delta, level, prettyPrint)

    /**
     * MCB analysis over any [ComparisonDataSourceIfc] — the database-independent core.
     * The [DbHandle] overload delegates here with `handle.source`; the in-memory path (a
     * headless batch rehydrated from its retained per-replication observations) calls it
     * directly, so both routes produce byte-identical JSON.
     */
    fun comparisonJson(
        source: ComparisonDataSourceIfc,
        responseName: String,
        experimentNames: List<String>? = null,
        delta: Double = 0.0,
        level: Double = 0.95,
        prettyPrint: Boolean = false,
    ): DbQueryResult {
        val selection = ComparisonSelectionModel(listOf(source))
        if (experimentNames == null) selection.selectAll()
        else experimentNames.forEach { selection.toggleExperiment(it, true) }

        val validation = selection.validateForResponse(responseName, AnalysisType.MULTIPLE_COMPARISON)
        if (!validation.ok) return DbQueryResult.Invalid(validation.reason ?: "Comparison request is not analyzable.")

        val observations = selection.gatherObservationsFor(responseName)
        val mca = MultipleComparisonAnalyzer(observations, responseName)
        val results = mca.mcbResultData(context = responseName)
            .asMCBResultDataFrame().toJson()
        val intervals = mca.mcbIntervalData(delta = delta, probCS = level, context = responseName)
            .map { it.finiteLimits() }.asMCBIntervalDataFrame().toJson()
        val screening = mca.mcbScreeningIntervalData(probCS = level, context = responseName)
            .map { it.finiteLimits() }.asMCBScreeningIntervalDataFrame().toJson()

        val payload = buildJsonObject {
            put("response", responseName)
            put("delta", delta)
            put("level", level)
            put("results", json.parseToJsonElement(results))
            put("intervals", json.parseToJsonElement(intervals))
            put("screening", json.parseToJsonElement(screening))
        }
        return DbQueryResult.Json(json.encodeToString(JsonObject.serializer(), payload))
    }

    /** The named statistical DataFrame views available for JSON projection. */
    fun viewNames(): List<String> = DB_VIEWS.keys.toList()

    /**
     * A named `KSLDatabase` statistical view as JSON, optionally filtered to one
     * experiment, row-capped with a no-silent-truncation envelope
     * `{view, total, returned, truncated, rows}`. Reuses `DataFrame.toJson()` — no
     * bespoke serialization. An unknown view name returns [DbQueryResult.Invalid]
     * listing the catalog.
     */
    fun viewJson(
        handle: DbHandle,
        viewName: String,
        experiment: String? = null,
        limit: Int = DEFAULT_VIEW_ROW_LIMIT,
    ): DbQueryResult {
        val accessor = DB_VIEWS[viewName]
            ?: return DbQueryResult.Invalid("unknown view '$viewName'; available: ${DB_VIEWS.keys.joinToString(", ")}")
        val full = accessor(handle.database).dropDbBookkeeping().filterExperiment(experiment)
        val total = full.rowsCount()
        val capped = if (total > limit) full.take(limit) else full
        val envelope = buildJsonObject {
            put("view", viewName)
            put("total", total)
            put("returned", capped.rowsCount())
            put("truncated", total > limit)
            put("rows", json.parseToJsonElement(capped.toJson()))
        }
        return DbQueryResult.Json(json.encodeToString(JsonObject.serializer(), envelope))
    }

    private fun DataFrame<*>.filterExperiment(name: String?): DataFrame<*> =
        if (name == null || "exp_name" !in columnNames()) this else filter { it["exp_name"] == name }

    // ----- file-producing operations (rendered reports, exports) — Phase C+ -----

    /**
     * Renders a Multiple-Comparison report for [responseName] (MCB intervals plus
     * embedded confidence-interval and box plots) into [reportsDir], reusing
     * `ComparisonReportRenderer`. Preconditions are validated through
     * `ComparisonSelectionModel`, so an unanalyzable request returns
     * [DbReportResult.Invalid]. Returns the written file names on success.
     */
    fun renderComparisonReport(
        handle: DbHandle,
        responseName: String,
        experimentNames: List<String>? = null,
        delta: Double = 0.0,
        level: Double = 0.95,
        formats: Set<ReportFormat> = setOf(ReportFormat.HTML),
        reportsDir: Path,
    ): DbReportResult {
        val selection = ComparisonSelectionModel(listOf(handle.source))
        if (experimentNames == null) selection.selectAll()
        else experimentNames.forEach { selection.toggleExperiment(it, true) }

        val validation = selection.validateForResponse(responseName, AnalysisType.MULTIPLE_COMPARISON)
        if (!validation.ok) return DbReportResult.Invalid(validation.reason ?: "Comparison request is not analyzable.")

        Files.createDirectories(reportsDir)
        val outcome = ComparisonReportRenderer.renderMca(
            sourceLabel = handle.label,
            responseName = responseName,
            observations = selection.gatherObservationsFor(responseName),
            outputDir = reportsDir,
            formats = formats,
            indifferenceZone = delta,
            altConfidenceLevel = level,
            diffConfidenceLevel = level,
            probCorrectSelection = level,
            showAltCIPlot = true,
            showBoxPlot = true,
        )
        return DbReportResult.Ok(outcome.written.map { it.fileName.toString() })
    }

    /**
     * Renders a single-experiment summary report (across-replication statistics
     * plus embedded histograms / frequency distributions, optional plots) for
     * [experimentName] into [reportsDir], reusing `KSLDatabase.toReport`. Returns
     * the written file names; an unknown experiment still produces a report noting
     * the absence (never an error).
     */
    fun renderExperimentSummaryReport(
        handle: DbHandle,
        experimentName: String,
        level: Double = 0.95,
        showPlots: Boolean = true,
        formats: Set<ReportFormat> = setOf(ReportFormat.HTML),
        reportsDir: Path,
    ): List<String> {
        Files.createDirectories(reportsDir)
        val document = handle.database.toReport(experimentName, confidenceLevel = level, showPlots = showPlots)
        val ctx = RenderContext(outputDir = reportsDir, plotDir = reportsDir.resolve("plots"))
        val stem = "summary-" + experimentName.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return formats.map { fmt ->
            val target = reportsDir.resolve("$stem.${fmt.extension()}")
            val file = when (fmt) {
                ReportFormat.HTML -> document.writeHtml(path = target, ctx = ctx)
                ReportFormat.MARKDOWN -> document.writeMarkdown(path = target, ctx = ctx)
                ReportFormat.TEXT -> document.writeText(path = target, ctx = ctx)
            }
            reportsDir.relativize(file.toPath()).toString().replace('\\', '/')
        }
    }

    private fun ReportFormat.extension(): String = when (this) {
        ReportFormat.HTML -> "html"
        ReportFormat.MARKDOWN -> "md"
        ReportFormat.TEXT -> "txt"
    }

    /**
     * Reconstructs a designed experiment's regression / factor-effects analysis from a
     * retained KSL database, feeding the identical KSLCore pipeline the desktop uses so
     * the coefficients/ANOVA match `DesignedExperimentIfc.regressionResults` on the same
     * design. Each design point was committed as a distinct experiment; this reads each
     * point's control values (the regressors) and its per-replication response averages,
     * reassembles the base (regressors + response) data frame the live object would have
     * produced, then applies the linear model.
     *
     * [effects] are control key names (the regressors) — each must be a control that varies
     * across the design points. [interactions] are product terms, each a list of effect
     * names (e.g. `["A","B"]` for `A*B`). When [coded] is true each regressor column is
     * ±1-coded from the executed factor levels (its observed low/high across design points,
     * which for a two-level design are exactly the factor's low/high — matching
     * `Factor.toCodedValue`); otherwise the raw control values are used.
     */
    fun experimentRegressionResults(
        handle: DbHandle,
        responseName: String,
        effects: List<String>,
        interactions: List<List<String>> = emptyList(),
        coded: Boolean = true,
        intercept: Boolean = true,
    ): RegressionResultsIfc =
        OLSRegression(buildExperimentRegressionData(handle.database, responseName, effects, interactions, coded, intercept))

    /**
     * Renders [experimentRegressionResults] as report files (default HTML) into
     * [reportsDir], reusing the same regression report the desktop produces. Returns the
     * written file names relative to [reportsDir].
     */
    fun renderExperimentRegressionReport(
        handle: DbHandle,
        responseName: String,
        effects: List<String>,
        interactions: List<List<String>> = emptyList(),
        coded: Boolean = true,
        intercept: Boolean = true,
        confidenceLevel: Double = 0.95,
        formats: Set<ReportFormat> = setOf(ReportFormat.HTML),
        reportsDir: Path,
    ): List<String> {
        Files.createDirectories(reportsDir)
        val results = experimentRegressionResults(handle, responseName, effects, interactions, coded, intercept)
        val document = results.toReport(
            title = "Experiment Regression — $responseName" + if (coded) " (coded factors)" else "",
            confidenceLevel = confidenceLevel,
        )
        val ctx = RenderContext(outputDir = reportsDir, plotDir = reportsDir.resolve("plots"))
        val stem = "regression-" + responseName.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return formats.map { fmt ->
            val target = reportsDir.resolve("$stem.${fmt.extension()}")
            val file = when (fmt) {
                ReportFormat.HTML -> document.writeHtml(path = target, ctx = ctx)
                ReportFormat.MARKDOWN -> document.writeMarkdown(path = target, ctx = ctx)
                ReportFormat.TEXT -> document.writeText(path = target, ctx = ctx)
            }
            reportsDir.relativize(file.toPath()).toString().replace('\\', '/')
        }
    }

    /**
     * The join at the heart of the DB-backed regression: enumerate the design-point
     * experiments, read each one's regressor (control) values and per-replication response
     * averages, assemble the base data frame, and expand it for the linear model. Design
     * points that produced no observation of [responseName] are skipped.
     */
    private fun buildExperimentRegressionData(
        db: KSLDatabase,
        responseName: String,
        effects: List<String>,
        interactions: List<List<String>>,
        coded: Boolean,
        intercept: Boolean,
    ): RegressionData {
        require(effects.isNotEmpty()) { "at least one regressor (effect) is required" }
        // Pass 1: per design point, gather the regressor values + the per-rep responses.
        data class DesignPoint(val rawValues: Map<String, Double>, val responses: List<Double>)
        val points = mutableListOf<DesignPoint>()
        for (expName in db.experimentNames) {
            val responses = db.withinRepStatDataFor(expName)
                .filter { it.stat_name == responseName }
                .mapNotNull { it.average }
            if (responses.isEmpty()) continue   // not a design point for this response
            // A factor may bind to either a control or a random-variable parameter, so
            // build one input-key -> value map from both tables. RV-parameter keys are
            // "<rvName><.><paramName>" — the same flat form Model.inputKeys() presents.
            val inputs = HashMap<String, Double>()
            db.controlDataFor(expName).forEach { c -> c.control_value?.let { inputs[c.key_name] = it } }
            db.rvParameterDataFor(expName).forEach { p ->
                inputs["${p.rv_name}${RVParameterSetter.rvParamConCatChar}${p.param_name}"] = p.param_value
            }
            val rawValues = effects.associateWith { effect ->
                inputs[effect] ?: throw IllegalArgumentException(
                    "'$effect' is not an input (control or RV parameter) on design point '$expName'; " +
                        "available inputs: ${inputs.keys.sorted()}")
            }
            points.add(DesignPoint(rawValues, responses))
        }
        require(points.isNotEmpty()) {
            "no design points in the database produced observations of response '$responseName'"
        }
        // Coding basis: each regressor's executed low/high (its observed extremes).
        val bounds = effects.associateWith { effect ->
            val values = points.map { it.rawValues.getValue(effect) }
            values.min() to values.max()
        }
        fun encode(effect: String, raw: Double): Double {
            if (!coded) return raw
            val (lo, hi) = bounds.getValue(effect)
            require(hi != lo) { "regressor '$effect' has a single level; it cannot be coded (or regressed on)" }
            return (raw - (lo + hi) / 2.0) / ((hi - lo) / 2.0)   // matches Factor.toCodedValue
        }
        // Pass 2: expand to one row per (design point × replication).
        val columns = LinkedHashMap<String, MutableList<Double>>().apply {
            effects.forEach { put(it, mutableListOf()) }
            put(responseName, mutableListOf())
        }
        for (point in points) {
            for (y in point.responses) {
                effects.forEach { columns.getValue(it).add(encode(it, point.rawValues.getValue(it))) }
                columns.getValue(responseName).add(y)
            }
        }
        val baseDf = columns.mapValues { it.value.toDoubleArray() }.toDataFrame()
        // Same downstream path as DesignedExperiment.regressionData: expand the model's
        // interaction columns, then create the regression data over its term names.
        val linearModel = LinearModel(effects.toSet()).also { lm ->
            lm.intercept = intercept
            interactions.forEach { lm.term(it) }
        }
        val df = baseDf.addColumnsFor(linearModel)
        return RegressionData.create(df, responseName, linearModel.termsAsMap.keys.toList(), linearModel.intercept)
    }

    /**
     * Exports the database's tables into [reportsDir] (a single workbook for
     * [DbExportFormat.EXCEL]; one CSV per table under a `csv/` subdir for
     * [DbExportFormat.CSV]), reusing the `DatabaseIOIfc` exporters. Returns the
     * written file names (relative to [reportsDir]).
     */
    fun exportDatabase(handle: DbHandle, format: DbExportFormat, reportsDir: Path): List<String> {
        Files.createDirectories(reportsDir)
        return when (format) {
            DbExportFormat.EXCEL -> {
                handle.database.exportToExcel(wbName = "database", wbDirectory = reportsDir)
                listOf("database.xlsx")
            }
            DbExportFormat.CSV -> {
                val csvDir = Files.createDirectories(reportsDir.resolve("csv"))
                handle.database.exportAllTablesAsCSV(pathToOutPutDirectory = csvDir)
                Files.walk(csvDir).use { stream ->
                    stream.filter { Files.isRegularFile(it) }
                        .map { reportsDir.relativize(it).toString().replace('\\', '/') }
                        .sorted()
                        .toList()
                }
            }
        }
    }

    /** Releases a handle. Best-effort closes the underlying connection if closeable. */
    fun close(handle: DbHandle) {
        handles.remove(handle.id)
        (handle.database as? AutoCloseable)?.let { runCatching { it.close() } }
    }

    override fun close() {
        handles.values.toList().forEach { close(it) }
    }

    private fun ExperimentRow.toDto() =
        ExperimentInfoDto(name, modelIdentifier, numReplications, responses.map { it.toDto() })

    private fun ResponseRow.toDto() = ResponseInfoDto(name, category.name)

    private val json = Json { ignoreUnknownKeys = true; allowSpecialFloatingPointValues = true }

    /** Drops the `DbTableData` bookkeeping columns that reflective `toDataFrame()`
     *  adds, leaving only the real data columns (mirrors `asMCB*DataFrame`). */
    private fun <T> DataFrame<T>.dropDbBookkeeping(): DataFrame<T> {
        val present = DB_BOOKKEEPING.filter { it in columnNames() }
        return if (present.isEmpty()) this else remove(*present.toTypedArray())
    }

    /** ±Infinity interval limits → null so the JSON is valid (JSON has no Infinity). */
    private fun MCBIntervalData.finiteLimits(): MCBIntervalData =
        copy(lowerLimit = lowerLimit?.takeIf { it.isFinite() }, upperLimit = upperLimit?.takeIf { it.isFinite() })

    private fun MCBScreeningIntervalData.finiteLimits(): MCBScreeningIntervalData =
        copy(lowerLimit = lowerLimit?.takeIf { it.isFinite() }, upperLimit = upperLimit?.takeIf { it.isFinite() })

    private companion object {
        val DB_BOOKKEEPING = listOf(
            "autoIncField", "keyFields", "numColumns", "numInsertFields",
            "numUpdateFields", "schemaName", "tableName",
        )

        /**
         * The exposed statistical views (friendly name -> DataFrame accessor).
         * `time-series` is the across-replication per-period summary view (bounded:
         * experiment x response x period). Pairwise-differences is intentionally
         * excluded; bulk extraction is the job of the database export.
         */
        val DB_VIEWS: Map<String, (KSLDatabase) -> DataFrame<*>> = linkedMapOf(
            "across-replication" to { it.acrossReplicationViewStatistics },
            "within-replication" to { it.withinRepViewStatistics },
            "within-replication-responses" to { it.withinRepResponseViewStatistics },
            "within-replication-counters" to { it.withinRepCounterViewStatistics },
            "time-series" to { it.timeSeriesResponseViewData },
            "histograms" to { it.histogramResults },
            "frequencies" to { it.frequencyResults },
            "batch-statistics" to { it.batchStatViewStatistics },
            "experiment-replications" to { it.expStatRepViewStatistics },
        )
    }
}
