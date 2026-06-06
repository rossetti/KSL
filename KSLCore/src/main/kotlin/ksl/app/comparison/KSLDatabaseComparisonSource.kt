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

import ksl.utilities.io.dbutil.KSLDatabase

/**
 *  Database-backed adapter exposing the experiments stored in a
 *  [KSLDatabase] as a [ComparisonDataSourceIfc] — the third member of
 *  the comparison-source family alongside [InMemoryComparisonSource]
 *  (test fixture / programmatic use) and
 *  [BatchCompletedComparisonSource] (live scenario-sweep result).
 *
 *  Each experiment found in `tblExperiment` becomes one
 *  [ExperimentRow]. The experiment's response set is the union of
 *  every response stat present in `tblWithinRepStat` and every counter
 *  stat present in `tblWithinRepCounterStat` for that experiment, in
 *  first-encountered order. Response-vs-time-weighted discrimination
 *  uses the `class_name` field on the joined `tblModelElement` row;
 *  this is information the batch and in-memory adapters cannot supply
 *  but the database always carries.
 *
 *  [observations] returns per-replication values in rep-id order,
 *  drawn from [KSLDatabase.replicationDataArraysByExperimentAndResponse]:
 *  for response statistics the `average` field, for counters the
 *  `last_value` field.  Missing-rep `NaN` entries (filled in by the
 *  underlying cross-experiment view) are dropped before the array is
 *  returned, so consumers see a clean, length-matched
 *  observation vector.
 *
 *  The adapter takes an **eager snapshot** of the database in its
 *  constructor — the per-experiment row metadata and the
 *  cross-experiment observation map are both materialised once and
 *  reused thereafter. The database is treated as frozen for the
 *  lifetime of the adapter, matching the behaviour of the in-memory
 *  and batch sources. Construct a new adapter if the underlying DB
 *  has been written to and you need to see the new state.
 */
class KSLDatabaseComparisonSource(
    private val db: KSLDatabase,
    override val sourceLabel: String = defaultLabel(db)
) : ComparisonDataSourceIfc {

    /**
     *  Eager cross-experiment observation snapshot. The underlying
     *  accessor builds a `Map<expName, Map<statName, DoubleArray>>`
     *  walking the within-rep view once; we keep it for the lifetime
     *  of the adapter so subsequent `observations()` calls are O(1)
     *  lookups instead of full re-scans.
     */
    private val myObservationsByExpAndResponse: Map<String, Map<String, DoubleArray>> =
        db.replicationDataArraysByExperimentAndResponse()

    private val myRows: List<ExperimentRow> = buildRows()

    override fun availableExperiments(): List<ExperimentRow> = myRows

    override fun observations(experimentName: String, responseName: String): DoubleArray? {
        val byResponse = myObservationsByExpAndResponse[experimentName] ?: return null
        val raw = byResponse[responseName] ?: return null
        // The cross-experiment view fills missing replications with NaN;
        // drop those so downstream consumers (Statistic,
        // MultipleComparisonAnalyzer) see only real observations.
        // toDoubleArray() already allocates a fresh array, so no
        // additional defensive copy is needed.
        val cleaned = raw.filter { !it.isNaN() }.toDoubleArray()
        return if (cleaned.isEmpty()) null else cleaned
    }

    private fun buildRows(): List<ExperimentRow> {
        val expNames = db.experimentNames
        val result = ArrayList<ExperimentRow>(expNames.size)
        for (expName in expNames) {
            val expRecord = db.fetchExperimentData(expName) ?: continue

            // Build element_id -> class_name lookup for this experiment.
            // Used solely to distinguish Response vs TWResponse below;
            // a missing or unrecognised class_name falls through to the
            // OBSERVATION default rather than failing the row.
            val classByElementId = db.modelElementDataFor(expName)
                .associate { it.element_id to it.class_name }

            // LinkedHashMap to preserve first-encountered order, matching
            // the convention used by BatchCompletedComparisonSource.
            val responses = linkedMapOf<String, ResponseRow>()
            val repIds = mutableSetOf<Int>()

            // Response / time-weighted stats live in within_rep_stat.
            for (row in db.withinRepStatDataFor(expName)) {
                repIds.add(row.rep_id)
                if (row.stat_name in responses) continue
                val category = when (classByElementId[row.element_id_fk]) {
                    TW_RESPONSE_CLASS_NAME -> ResponseCategory.TIME_WEIGHTED
                    else -> ResponseCategory.OBSERVATION
                }
                responses[row.stat_name] = ResponseRow(row.stat_name, category)
            }

            // Counter stats live in within_rep_counter_stat.  Any
            // collision with an already-seen response name is left
            // alone — the existing OBSERVATION/TIME_WEIGHTED tag wins,
            // matching the "first occurrence wins" rule documented in
            // unionOfResponses().
            for (row in db.withinRepCounterStatDataFor(expName)) {
                repIds.add(row.rep_id)
                if (row.stat_name in responses) continue
                responses[row.stat_name] = ResponseRow(row.stat_name, ResponseCategory.COUNTER)
            }

            result.add(
                ExperimentRow(
                    name = expName,
                    modelIdentifier = expRecord.model_name,
                    // Actually-completed reps (count of distinct rep_ids
                    // seen across the within-rep tables) rather than the
                    // requested count on the simulation_run record — the
                    // analyzer's MCA validation requires equal *actual*
                    // rep counts.
                    numReplications = repIds.size,
                    responses = responses.values.toList()
                )
            )
        }
        return result
    }

    companion object {
        /**
         *  Simple name of the `TWResponse` model element class, as
         *  written into `tblModelElement.class_name` by
         *  `KSLDatabase.createDbModelElement`.  Held as a constant so
         *  the discrimination logic in [buildRows] reads cleanly and
         *  the value is easy to update if the model element ever
         *  renames itself.
         */
        private const val TW_RESPONSE_CLASS_NAME = "TWResponse"

        /**
         *  Default [ComparisonDataSourceIfc.sourceLabel] for a
         *  database adapter.  Renders as
         *  `"KSL database · <db label>"`, where the label is whatever
         *  the underlying [ksl.utilities.io.dbutil.Database] reports
         *  (typically the SQLite file stem or the Derby directory
         *  name).
         */
        @JvmStatic
        fun defaultLabel(db: KSLDatabase): String =
            "KSL database · ${db.label}"
    }
}
