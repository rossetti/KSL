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

package ksl.app.comparison

/**
 *  Read-only access to a collection of "experiments" whose
 *  per-replication response observations can be pulled for
 *  cross-experiment comparison analyses (box plots, multiple-
 *  comparison analysis, confidence-interval plots).
 *
 *  An *experiment* here is the unit of comparison the analyst
 *  picks among: one scenario from a scenario sweep, one design
 *  point from a designed experiment, or one experiment loaded from
 *  a saved `KSLDatabase`.  The interface deliberately uses the
 *  generic word "experiment" so the same component can drive any
 *  of those workflows.
 *
 *  Implementations are *adapters* — they translate a host-specific
 *  data shape (e.g. `RunResult.BatchCompleted`,
 *  `DesignedExperimentIfc`, `KSLDatabase`) into the small surface
 *  this interface exposes.  The comparison-analyzer UI knows about
 *  this interface only; it does not know which adapter is feeding
 *  it.
 *
 *  Designed for multi-source extension: hosts accept a
 *  `List<ComparisonDataSourceIfc>` so a future workflow can compare
 *  experiments across two prior runs by loading two adapters.  v1
 *  implementations and the v1 hosts work with a single source.
 */
interface ComparisonDataSourceIfc {

    /** Human-readable label for the source — shown in the
     *  comparison-analyzer header.  Free-form; typical values:
     *  `"Scenario run · 2025-05-15 · 4 scenarios"`,
     *  `"Designed experiment · 12 points"`,
     *  `"KSL database · queue-study.db"`. */
    val sourceLabel: String

    /** All experiments available for comparison from this source,
     *  in source-natural order (commit order for scenarios,
     *  execution order for design points, table order for DB
     *  loads).  Empty when the source has no experiments. */
    fun availableExperiments(): List<ExperimentRow>

    /** Per-replication observations for the given [experimentName]
     *  / [responseName] pair.  Returns `null` when the experiment
     *  did not record that response, when the experiment is
     *  unknown, or when no per-replication data is available
     *  (counters that record only an end-of-replication count are
     *  surfaced as `last_value` and still return a one-value-per-rep
     *  array).  Empty array is reserved for "experiment recorded
     *  the response but produced no values" — vanishingly rare in
     *  practice but worth distinguishing from "no recording at
     *  all". */
    fun observations(experimentName: String, responseName: String): DoubleArray?
}

/**
 *  One experiment exposed by a [ComparisonDataSourceIfc].
 *
 *  @property name              the experiment / scenario / design-point
 *                              identifier.  Equals
 *                              `ExperimentTableData.exp_name` for
 *                              scenario-app and DB-backed sources.
 *  @property modelIdentifier   the model the experiment ran against.
 *                              Used by the UI to group experiments by
 *                              model so the analyst can see at a
 *                              glance which experiments share a
 *                              schema of response names.
 *  @property numReplications   replication count actually completed
 *                              (not the requested count — that
 *                              distinction matters for analyses like
 *                              MCA that require equal rep counts).
 *  @property responses         every response / counter the
 *                              experiment recorded, in source-natural
 *                              order.  Empty when the experiment
 *                              completed without recording anything.
 */
data class ExperimentRow(
    val name: String,
    val modelIdentifier: String,
    val numReplications: Int,
    val responses: List<ResponseRow>
)

/**
 *  One response / counter exposed by an [ExperimentRow].
 *
 *  @property name      the response/counter name.  Equals the
 *                      `stat_name` field on the underlying
 *                      `WithinRepStatTableData` /
 *                      `WithinRepCounterStatTableData`.
 *  @property category  what kind of model element produced the
 *                      values.  Drives display chrome in the UI
 *                      and informs which analyses make sense
 *                      (parametric MCA is most defensible for
 *                      [ResponseCategory.OBSERVATION] and
 *                      [ResponseCategory.TIME_WEIGHTED]; counters
 *                      are still usable but the normality
 *                      assumptions are weaker).
 */
data class ResponseRow(
    val name: String,
    val category: ResponseCategory
)

/**
 *  Distinguishes the three kinds of measurable model elements
 *  whose per-replication values are eligible for cross-experiment
 *  comparison.
 *
 *  - [OBSERVATION]    — `ksl.modeling.variable.Response` —
 *                       observation-based statistics, per-replication
 *                       average from independent samples.
 *  - [TIME_WEIGHTED]  — `ksl.modeling.variable.TWResponse` —
 *                       time-weighted statistics for continuous
 *                       variables; per-replication time-average.
 *  - [COUNTER]        — `ksl.modeling.variable.Counter` — running
 *                       counts; per-replication final value.
 */
enum class ResponseCategory { OBSERVATION, TIME_WEIGHTED, COUNTER }

// ── Pure-function helpers used by the UI ─────────────────────────────────

/**
 *  Union of responses across [this] list of experiments, sorted by
 *  name with no duplicates.  When two experiments record a response
 *  under the same name but different categories (rare; usually
 *  indicates a model schema collision), the first occurrence wins —
 *  the UI can surface the discrepancy separately if needed.
 *
 *  This is what the comparison-analyzer's response table renders:
 *  "every response any of the currently-checked experiments records".
 */
fun List<ExperimentRow>.unionOfResponses(): List<ResponseRow> {
    val seen = linkedMapOf<String, ResponseRow>()
    for (exp in this) {
        for (r in exp.responses) seen.putIfAbsent(r.name, r)
    }
    return seen.values.sortedBy { it.name }
}

/**
 *  Experiments in [this] list that record a response named
 *  [responseName].  Used to drive the "Recorded by N of M" column
 *  in the response table and to define the participant set for
 *  each render.
 */
fun List<ExperimentRow>.recordingResponse(responseName: String): List<ExperimentRow> =
    this.filter { exp -> exp.responses.any { it.name == responseName } }
