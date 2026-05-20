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
 *  [ComparisonSelectionModel.validate].
 */
enum class AnalysisType { BOX_PLOT, MULTIPLE_COMPARISON, CONFIDENCE_INTERVALS }

/**
 *  Result of pre-flight validation for a given (selection, analysis)
 *  pair.  When [ok] is `false`, [reason] carries a short user-facing
 *  explanation; the analyzer's *Generate* button is disabled with
 *  this text shown as a tooltip / status line.
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
 *  Shared mutable state for the Comparison Analyzer's three
 *  selection components (experiments / response / analysis type).
 *
 *  Each component reads from this model on render, calls a mutator
 *  on user input, and registers a listener to refresh when other
 *  components change the state.  Listeners are invoked
 *  synchronously on the EDT (the same thread Swing input handlers
 *  run on).
 *
 *  Multi-source ready: takes a list of [ComparisonDataSourceIfc].
 *  v1 hosts construct with a single-element list.  When the
 *  analyzer grows to genuinely multi-source workflows, the model
 *  flattens experiments across sources and the response/analysis
 *  selection logic adapts without changing the public surface.
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

    private var mySelectedResponse: String? = null

    /** Currently picked response name, or `null` when nothing is
     *  picked / the previously-picked response is no longer
     *  available in the union of selected experiments. */
    val selectedResponse: String? get() = mySelectedResponse

    private var myAnalysis: AnalysisType = AnalysisType.BOX_PLOT

    /** Currently chosen analysis type.  Defaults to box plot. */
    val analysis: AnalysisType get() = myAnalysis

    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    private fun notifyChange() {
        for (l in listeners) l()
    }

    // ── Mutators ─────────────────────────────────────────────────────────

    /** Check or uncheck the experiment with the given [name].  When
     *  unchecking would leave the currently-selected response with
     *  no recording experiments at all, the response is cleared too
     *  so the analyzer doesn't sit in an obviously-invalid state. */
    fun toggleExperiment(name: String, checked: Boolean) {
        if (checked) mySelectedExperiments.add(name)
        else mySelectedExperiments.remove(name)
        clearResponseIfStale()
        notifyChange()
    }

    /** Check every experiment.  Used by *Select All* / clearing
     *  with [selectNone]. */
    fun selectAll() {
        mySelectedExperiments.clear()
        for (e in allExperiments) mySelectedExperiments.add(e.name)
        notifyChange()
    }

    /** Uncheck every experiment.  Clears the response too. */
    fun selectNone() {
        mySelectedExperiments.clear()
        mySelectedResponse = null
        notifyChange()
    }

    fun setResponse(name: String?) {
        if (mySelectedResponse == name) return
        mySelectedResponse = name
        notifyChange()
    }

    fun setAnalysis(type: AnalysisType) {
        if (myAnalysis == type) return
        myAnalysis = type
        notifyChange()
    }

    // ── Derived state ────────────────────────────────────────────────────

    /** Currently-checked experiments as full rows. */
    fun selectedExperiments(): List<ExperimentRow> =
        allExperiments.filter { it.name in mySelectedExperiments }

    /** Responses across the currently-checked experiments — the
     *  candidate list shown in the response table. */
    fun availableResponses(): List<ResponseRow> =
        selectedExperiments().unionOfResponses()

    /** Names of experiments that record [responseName] within the
     *  current selection.  Drives the "Recorded by N of M" column. */
    fun experimentsRecording(responseName: String): List<ExperimentRow> =
        selectedExperiments().recordingResponse(responseName)

    /** Validate that the current selection supports the given
     *  analysis type.  Returns [ValidationResult.OK] when the
     *  *Generate* button can fire; otherwise [ValidationResult.fail]
     *  with a user-facing explanation. */
    fun validate(type: AnalysisType = analysis): ValidationResult {
        val response = mySelectedResponse
            ?: return ValidationResult.fail("Pick a response from the middle column.")
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

    private fun clearResponseIfStale() {
        val r = mySelectedResponse ?: return
        if (experimentsRecording(r).isEmpty()) mySelectedResponse = null
    }
}
