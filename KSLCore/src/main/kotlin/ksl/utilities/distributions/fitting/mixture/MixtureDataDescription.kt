/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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
package ksl.utilities.distributions.fitting.mixture

import ksl.utilities.distributions.fitting.diagnostics.ModalityAssessment
import ksl.utilities.statistic.StatisticIfc

/**
 *  What the workflow advises after looking at the data but before fitting anything.
 *
 *  **None of these is a refusal, and that is structural rather than a matter of taste.** Refusing
 *  would be paternalistic and sometimes wrong: a mixture fitted to unimodal data with a known
 *  mechanism is exactly the case where the method earns its keep, and the analyst knows about the
 *  mechanism while the tool does not. There is no `REFUSE`, so no caller can produce one.
 */
enum class EntryGuidance {

    /** A warrant for a mixture is present: visible structure, a claimed mechanism, or both. */
    PROCEED,

    /**
     *  Worth proceeding, but the components will be hard to identify — the data supports fewer
     *  components than an analyst is likely to be hypothesising, or the apparent structure moves
     *  with the bin count.
     */
    PROCEED_WITH_WEAK_IDENTIFICATION,

    /**
     *  Neither warrant is present. A mixture will still fit, and will fit better than a single
     *  distribution because it has more parameters, but its components will not correspond to
     *  anything.
     */
    PROCEED_WITH_WARNING
}

/**
 *  Everything known about a sample before a mixture is fitted to it.
 *
 *  **The order matters.** An analyst is best served by learning that their sample cannot support
 *  six components *before* being shown a fit at six components, and by learning that the humps they
 *  counted move with the bin width before being asked to defend the count. Both facts are cheap;
 *  neither is available from the fit.
 *
 *  **Adequacy is deliberately absent.** The plan expected the data-adequacy verdict to join this
 *  object, and it cannot: adequacy is governed by how far the *fitted* mixture sits from the
 *  nearest mixture with one component fewer, so it requires a fit that by definition does not exist
 *  yet. It lives on the results instead. What belongs here is the structural ceiling — the largest
 *  number of components this sample can support at all — which is available before fitting and
 *  answers the same question in the cases where the answer is emphatic.
 *
 *  @param numObservations how many observations there are
 *  @param numDistinctValues how many of them are distinct, which is what actually limits the
 *  partition
 *  @param statistics the sample statistics
 *  @param maximumFeasibleComponents the largest number of components the sample can structurally
 *  support, from the admissibility certificate
 *  @param modality what the mode structure looks like and whether it survives smoothing
 */
class MixtureDataDescription(
    val numObservations: Int,
    val numDistinctValues: Int,
    val statistics: StatisticIfc,
    val maximumFeasibleComponents: Int,
    val modality: ModalityAssessment
) {

    /**
     *  What to do next, given whether the analyst has a mechanism in mind.
     *
     *  `hasMechanism` is an argument rather than a question the library asks. A library should not
     *  prompt; the application layer above it can, and the honest default is that no mechanism has
     *  been claimed.
     *
     *  @param hasMechanism whether the analyst has a priori reason to believe in a mixing mechanism
     *  @param level the significance level for the modality test
     */
    fun entryGuidance(hasMechanism: Boolean, level: Double = 0.05): EntryGuidance = when {
        modality.warningOrNull(hasMechanism, level) != null -> EntryGuidance.PROCEED_WITH_WARNING
        maximumFeasibleComponents < 3 || modality.apparentModesDependOnBinning ->
            EntryGuidance.PROCEED_WITH_WEAK_IDENTIFICATION
        else -> EntryGuidance.PROCEED
    }

    /**
     *  The warning of the entry gate, or null when there is nothing to warn about.
     *
     *  @param hasMechanism whether the analyst has a priori reason to believe in a mixing mechanism
     *  @param level the significance level for the modality test
     */
    fun warningOrNull(hasMechanism: Boolean, level: Double = 0.05): String? =
        modality.warningOrNull(hasMechanism, level)

    override fun toString(): String = buildString {
        appendLine("=".repeat(72))
        appendLine("Before fitting anything")
        appendLine("=".repeat(72))
        appendLine()
        appendLine("The sample")
        appendLine("-".repeat(72))
        appendLine("  $numObservations observations, $numDistinctValues of them distinct")
        appendLine(
            "  average ${"%.4f".format(statistics.average)}, " +
                    "standard deviation ${"%.4f".format(statistics.standardDeviation)}"
        )
        appendLine(
            "  at most $maximumFeasibleComponents components can be fitted to a sample this size"
        )
        appendLine()
        append(modality.toString())
    }
}
