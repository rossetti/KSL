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
package ksl.utilities.distributions.fitting.diagnostics

/**
 *  What is known about the shape of the data before anything is fitted to it.
 *
 *  @param apparentModes the mode count that persists over the widest span of bandwidths
 *  @param unimodalityTest Silverman's test of the hypothesis of a single mode
 *  @param modePersistence the span of bandwidths over which the apparent count holds
 *  @param binSensitivity the apparent mode count at each of several bin counts
 *  @param modeTrace the full sweep, for plotting
 */
class ModalityAssessment(
    val apparentModes: Int,
    val unimodalityTest: ModalityTestResult,
    val modePersistence: ClosedRange<Double>?,
    val binSensitivity: Map<Int, Int>,
    val modeTrace: List<ModeTraceEntry>
) {

    /**
     *  Whether the data shows more than one mode by the test rather than by eye.
     *
     *  @param level the significance level
     */
    fun isMultimodal(level: Double = 0.05): Boolean = unimodalityTest.rejects(level)

    /**
     *  Whether the apparent mode count is an artefact of the bin count.
     *
     *  A reading that changes as the bins change is a statement about the histogram rather than
     *  about the data, and an analyst who counted humps off a default plot deserves to know which
     *  they are looking at.
     */
    val apparentModesDependOnBinning: Boolean
        get() = binSensitivity.values.distinct().size > 1

    /**
     *  The warning of the design review's entry gate, or **null when there is nothing to warn
     *  about** so that a caller cannot print an empty warning.
     *
     *  The gate warns and proceeds; it never refuses. Refusing would be both paternalistic and
     *  sometimes wrong — a mixture fitted to unimodal data with a known mechanism is exactly the
     *  case where the method earns its keep, and the analyst knows about the mechanism and the
     *  tool does not.
     *
     *  @param hasMechanism whether the analyst has a priori reason to believe in a mixing
     *  mechanism, which the library takes as an argument rather than asking
     *  @param level the significance level for the modality test
     */
    fun warningOrNull(hasMechanism: Boolean, level: Double = 0.05): String? {
        if (hasMechanism) return null
        if (isMultimodal(level)) return null
        return buildString {
            append("Neither warrant for a mixture is present. ")
            append(
                "The data does not show more than one mode (Silverman's test of a single mode " +
                        "gives p = ${"%.3f".format(unimodalityTest.pValue)}), and no mixing " +
                        "mechanism has been claimed. "
            )
            append(
                "A mixture will still fit, and will fit better than a single distribution because " +
                        "it has more parameters, but its components will not correspond to " +
                        "anything and the number of them will not be recoverable. "
            )
            append(
                "If the goal is a density that behaves like the data, a single distribution is " +
                        "the more defensible model. If there is a mechanism, say so and proceed."
            )
        }
    }

    override fun toString(): String = buildString {
        appendLine("Modality")
        appendLine("-".repeat(72))
        appendLine("  apparent modes: $apparentModes")
        modePersistence?.let {
            appendLine(
                "  that count holds for bandwidths ${"%.4f".format(it.start)} to " +
                        "${"%.4f".format(it.endInclusive)}"
            )
        }
        appendLine("  $unimodalityTest")
        appendLine(
            "  apparent modes by bin count: " +
                    binSensitivity.entries.joinToString(", ") { "${it.key}:${it.value}" }
        )
        if (apparentModesDependOnBinning) {
            appendLine("  the apparent count depends on the bin count, so read it with care")
        }
        append(
            "  Modes bound the number of components from below only: a two-component mixture can " +
                    "be unimodal, so one hump does not mean one component."
        )
    }
}
