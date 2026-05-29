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

import ksl.app.session.RunResult
import ksl.utilities.io.dbutil.SimulationSnapshot
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 *  Scenario-host adapter that exposes a completed scenario sweep —
 *  the [RunResult.BatchCompleted] surfaced through whichever
 *  controller owns `lastResult` — as a [ComparisonDataSourceIfc] for
 *  the comparison analyzer UI.
 *
 *  Each scenario in [result] becomes one [ExperimentRow].  The
 *  experiment's response set is the union of names recorded across
 *  its `ReplicationCompleted` snapshots:
 *
 *  - `WithinRepStatTableData` rows → [ResponseCategory.OBSERVATION].
 *    The bridge does not separately tag observation vs time-weighted
 *    responses in within-rep records; the discrimination would
 *    require joining through the model-element table, which is only
 *    available to the (future) database adapter.
 *  - `WithinRepCounterStatTableData` rows → [ResponseCategory.COUNTER].
 *
 *  [observations] returns per-replication values in `rep_id` order:
 *  for response statistics, the `average` field; for counters, the
 *  `last_value` field (the running count at end of replication).
 *
 *  Lookup is by `experiment.exp_name` — the authoritative scenario
 *  identifier the substrate populates from
 *  `Model.experimentName`, which the orchestrator sets from
 *  `ScenarioSpec.name`.
 */
class BatchCompletedComparisonSource(
    private val result: RunResult.BatchCompleted,
    override val sourceLabel: String = defaultLabel(result)
) : ComparisonDataSourceIfc {

    private val rows: List<ExperimentRow> = result.snapshots.map { snap ->
        buildRow(snap, result.replicationsByItem[snap.experiment.exp_name].orEmpty())
    }

    override fun availableExperiments(): List<ExperimentRow> = rows

    override fun observations(experimentName: String, responseName: String): DoubleArray? {
        val reps = result.replicationsByItem[experimentName] ?: return null
        val values = ArrayList<Double>(reps.size)
        for (rep in reps.sortedBy { it.repId }) {
            val obs = rep.withinRepStats.firstOrNull { it.stat_name == responseName }?.average
            if (obs != null) {
                values.add(obs)
                continue
            }
            val counter = rep.withinRepCounterStats.firstOrNull { it.stat_name == responseName }?.last_value
            if (counter != null) {
                values.add(counter)
            }
        }
        // Null when the response wasn't recorded at all on any rep.
        // Empty would mean "recorded but no finite values" — vanishingly
        // rare; we collapse to null for the same UX outcome.
        return if (values.isEmpty()) null else values.toDoubleArray()
    }

    private fun buildRow(
        snap: SimulationSnapshot.ExperimentCompleted,
        reps: List<SimulationSnapshot.ReplicationCompleted>
    ): ExperimentRow {
        val responses = linkedMapOf<String, ResponseRow>()
        for (rep in reps) {
            for (s in rep.withinRepStats) {
                responses.putIfAbsent(s.stat_name, ResponseRow(s.stat_name, ResponseCategory.OBSERVATION))
            }
            for (c in rep.withinRepCounterStats) {
                responses.putIfAbsent(c.stat_name, ResponseRow(c.stat_name, ResponseCategory.COUNTER))
            }
        }
        return ExperimentRow(
            name = snap.experiment.exp_name,
            modelIdentifier = snap.experiment.model_name,
            // Actual completed reps (count of ReplicationCompleted snapshots)
            // rather than the requested count — the analyzer's MCA path
            // requires equal *actual* counts, not equal requested counts.
            numReplications = reps.size,
            responses = responses.values.toList()
        )
    }

    companion object {
        /** Default `sourceLabel` derived from the batch summary.
         *
         *  Renders the count of completed scenarios and the run's
         *  begin time in the system default zone — e.g.
         *  `"Scenario run · 4 scenarios · started 2026-05-20 14:32:15"`.
         *  Earlier versions surfaced the first 8 characters of the
         *  orchestrator's UUID; that was visually indistinguishable
         *  from a default-`toString` hash and meant nothing to a
         *  human staring at the analyzer header.  The full UUID is
         *  still correlatable through logs and the KSL database for
         *  anyone who needs it. */
        fun defaultLabel(result: RunResult.BatchCompleted): String {
            val n = result.snapshots.size
            val started = formatLocal(result.summary.beginTime)
            return "Scenario run · $n scenario${if (n == 1) "" else "s"} · started $started"
        }

        private val labelTimeFormatter: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

        private fun formatLocal(instant: Instant): String =
            labelTimeFormatter.format(instant.toJavaInstant())
    }
}
