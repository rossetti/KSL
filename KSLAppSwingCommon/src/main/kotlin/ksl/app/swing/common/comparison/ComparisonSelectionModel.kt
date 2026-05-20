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

package ksl.app.swing.common.comparison

/**
 *  Analysis types the Comparison Analyzer can produce.  Each one
 *  has its own validation rule against the current selection — see
 *  [ComparisonSelectionModel.validateForResponse].
 */
enum class AnalysisType { BOX_PLOT, MULTIPLE_COMPARISON, CONFIDENCE_INTERVALS }

/**
 *  Result of pre-flight validation for a given (selection, analysis)
 *  pair.  When [ok] is `false`, [reason] carries a short user-facing
 *  explanation; the analysis dialog's *Generate* button is disabled
 *  with this text shown as a tooltip / status line.
 */
data class ValidationResult(
    val ok: Boolean,
    val reason: String? = null
) {
    companion object {
        val OK: ValidationResult = ValidationResult(true)
        fun fail(reason: String) = ValidationResult(false, reason)
    }
}

/**
 *  Shared mutable state for the Comparison Analyzer's
 *  experiments-first workflow.
 *
 *  The frame owns one of these and uses it for the *Experiments*
 *  column only.  Response and analysis selection have moved into
 *  the per-analysis dialogs ([BoxPlotAnalysisDialog],
 *  `MultipleComparisonAnalysisDialog`, `ConfidenceIntervalsAnalysisDialog`)
 *  — each dialog reads experiments from this model when it opens and
 *  asks the user for a response inside its own configuration UI.
 *
 *  Multi-source ready: takes a list of [ComparisonDataSourceIfc].
 *  v1 hosts construct with a single-element list.  When the analyzer
 *  grows to genuinely multi-source workflows, the model flattens
 *  experiments across sources and the helpers below adapt without
 *  changing the public surface.
 */
class ComparisonSelectionModel(
    val sources: List<ComparisonDataSourceIfc>
) {
    /** All experiments across all sources, in source-order then
     *  natural order within each source. */
    val allExperiments: List<ExperimentRow> =
        sources.flatMap { it.availableExperiments() }

    private val mySelectedExperiments = linkedSetOf<String>()

    /** Names of experiments the analyst has checked. */
    val selectedExperimentNames: Set<String> get() = mySelectedExperiments.toSet()

    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    private fun notifyChange() {
        for (l in listeners) l()
    }

    // ── Mutators ─────────────────────────────────────────────────────────

    /** Check or uncheck the experiment with the given [name]. */
    fun toggleExperiment(name: String, checked: Boolean) {
        if (checked) mySelectedExperiments.add(name)
        else mySelectedExperiments.remove(name)
        notifyChange()
    }

    /** Check every experiment.  Used by *Select All* / clearing
     *  with [selectNone]. */
    fun selectAll() {
        mySelectedExperiments.clear()
        for (e in allExperiments) mySelectedExperiments.add(e.name)
        notifyChange()
    }

    /** Uncheck every experiment. */
    fun selectNone() {
        mySelectedExperiments.clear()
        notifyChange()
    }

    // ── Derived state ────────────────────────────────────────────────────

    /** Currently-checked experiments as full rows, in source-order
     *  then natural order within each source.  Iteration order is
     *  the canonical left→right ordering for box plots and CI plots.
     */
    fun selectedExperiments(): List<ExperimentRow> =
        allExperiments.filter { it.name in mySelectedExperiments }

    /** Responses across the currently-checked experiments — the
     *  candidate list shown in each analysis dialog's response
     *  sub-picker. */
    fun availableResponses(): List<ResponseRow> =
        selectedExperiments().unionOfResponses()

    /** Names of experiments that record [responseName] within the
     *  current selection.  Drives the "Recorded by N of M" column. */
    fun experimentsRecording(responseName: String): List<ExperimentRow> =
        selectedExperiments().recordingResponse(responseName)

    /** Collect per-experiment observation arrays for [responseName],
     *  restricted to the experiments that record it.  Iteration
     *  order matches [selectedExperiments] — i.e. source-order then
     *  natural order within each source — which is the canonical
     *  left→right ordering for cross-experiment box plots and CI
     *  plots.  Returns an empty map when no experiment records the
     *  response.
     *
     *  This is the canonical input to
     *  [ComparisonReportRenderer.renderBoxPlot] / `renderMca` /
     *  `renderCiPlot`. */
    fun gatherObservationsFor(responseName: String): Map<String, DoubleArray> {
        val participants = experimentsRecording(responseName).map { it.name }.toSet()
        if (participants.isEmpty()) return emptyMap()
        // Build a name → source lookup so we can pull observations
        // through the right adapter.  In a multi-source future, two
        // sources might expose experiments with the same name — for
        // v1 they don't collide because each source has a single
        // KSLDatabase exp-name-uniqueness invariant behind it.
        val sourceByName = mutableMapOf<String, ComparisonDataSourceIfc>()
        for (src in sources) {
            for (e in src.availableExperiments()) sourceByName[e.name] = src
        }
        val out = linkedMapOf<String, DoubleArray>()
        for (exp in selectedExperiments()) {
            if (exp.name !in participants) continue
            val src = sourceByName[exp.name] ?: continue
            val values = src.observations(exp.name, responseName) ?: continue
            out[exp.name] = values
        }
        return out
    }

    /** Validate that the current selection supports running [type]
     *  against [responseName].  Returns [ValidationResult.OK] when
     *  the analysis dialog's *Generate* button can fire; otherwise
     *  [ValidationResult.fail] with a user-facing explanation.
     *
     *  This is the only validation entry point — the frame no
     *  longer holds a "current" response or analysis, so callers
     *  must supply both.
     */
    fun validateForResponse(
        responseName: String?,
        type: AnalysisType
    ): ValidationResult {
        val response = responseName
            ?: return ValidationResult.fail("Pick a response.")
        val participants = experimentsRecording(response)
        if (participants.isEmpty()) {
            return ValidationResult.fail(
                "No checked experiment records '$response'.  Pick a different response " +
                    "or check experiments that record it."
            )
        }
        return when (type) {
            AnalysisType.BOX_PLOT -> ValidationResult.OK
            AnalysisType.CONFIDENCE_INTERVALS -> ValidationResult.OK
            AnalysisType.MULTIPLE_COMPARISON -> {
                if (participants.size < 2) {
                    return ValidationResult.fail(
                        "Multiple Comparison needs at least 2 experiments recording '$response'.  " +
                            "Currently: ${participants.size}."
                    )
                }
                val repCounts = participants.map { it.numReplications }.distinct()
                if (repCounts.size != 1) {
                    return ValidationResult.fail(
                        "Multiple Comparison needs equal replication counts.  Currently: " +
                            repCounts.sorted().joinToString(", ") + "."
                    )
                }
                if (repCounts.single() < 2) {
                    return ValidationResult.fail(
                        "Multiple Comparison needs at least 2 replications per experiment.  " +
                            "Currently: ${repCounts.single()}."
                    )
                }
                ValidationResult.OK
            }
        }
    }
}
