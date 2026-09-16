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

import kotlin.math.abs

/**
 *  What moving the cuts revealed about the reported fit.
 *
 *  **Three readings rather than a yes or no, because a yes-or-no does not survive measurement.**
 *  The obvious design is a boolean: stable if the density moved less than some threshold. Calibrated
 *  against the designed experiment that rule reaches a Youden's J of 0.23, which is barely better
 *  than chance, and the reason is visible in the data — the density moves a little for almost
 *  every fit, so a line drawn through the middle of that distribution separates nothing. What does
 *  separate is the extremes, and they say different things with different confidence.
 */
enum class CutDependence {

    /**
     *  The cuts can be moved and essentially nothing changes group, and the density does not follow
     *  them. The partition is not doing arbitrary work: the components are divided by gaps the data
     *  actually has.
     *
     *  **The informative reading, and an uncommon one.** Over the designed experiment it arose on
     *  10.3% of measurable fits and the component count was right on 99.0% of them, against 57.4%
     *  over all measurable fits. It holds within adequacy bands rather than merely tracking them —
     *  96.8% within `MARGINAL` and 100% within `AMPLE`, on 218 and 511 attempts — so it is telling
     *  you something the sample size and separability do not.
     */
    CUTS_LIE_IN_A_GAP,

    /**
     *  Displacing the cuts reassigns a large share of the sample. The reported components are
     *  slices of a continuum rather than groups the data separates, and their shapes should not be
     *  read as physical.
     *
     *  Arose on 16.6% of measurable fits, with the count right on 33.7% of them. Worth reading even
     *  where the sample is otherwise ample: among `AMPLE` fits carrying this reading the count was
     *  right on 37.6% against 89.4% for the fits beside them in that band, which is a warning the
     *  adequacy verdict cannot give on its own.
     */
    DENSITY_FOLLOWS_THE_CUTS,

    /**
     *  Neither signature is present, or nothing could be measured.
     *
     *  **The usual answer**, on 73.2% of measurable fits, where the count was right on 56.9% — that
     *  is, indistinguishable from not having asked. This value is not a mild version of the other
     *  two; it means the diagnostic had nothing to say, and reporting that plainly is the point of
     *  having it.
     */
    INCONCLUSIVE
}

/**
 *  How far the fitted density moves, and how much of the sample changes hands, when the cuts
 *  between the components are nudged.
 *
 *  The method partitions the sorted sample and estimates each component from its own group, so
 *  every reported component depends on where two cuts fell. That dependence is invisible in the
 *  result: a mixture assembled from arbitrary cuts looks exactly like one assembled from
 *  meaningful cuts. This measures it, by moving each cut a little way along the axis the data
 *  lives on and refitting the components from the groups that result.
 *
 *  **The cut moves in data units, not in observations.** That is the whole point, and the reason
 *  the obvious alternative does not work. Displacing a cut by a fixed number of observations, or
 *  widening each component's estimation window by a fraction of its group, always moves data
 *  across the boundary — so a genuinely well-separated fit, whose components sit far apart, is
 *  disturbed the most, because the observations that cross are the most alien ones available.
 *  That inverts the reading. Moving the cut a fixed distance along the axis instead reassigns
 *  nothing when the cut sits in an empty gap, which is exactly when the partition is not doing
 *  arbitrary work, and reassigns a great deal when the sample is one blob.
 *
 *  **The count of reassigned observations turned out to matter more than the distance.** Calibrated
 *  against the designed experiment, the share of the sample that changes group separates answerable
 *  fits from unanswerable ones better than the density movement does — a Youden's J of 0.33 against
 *  0.23 — and it is free, being a property of the displaced partition rather than of anything
 *  fitted to it. So the reading below is governed mainly by the share, with the movement as a
 *  guard against the case where a handful of reassigned observations happen to move the density a
 *  long way.
 *
 *  **The alternative fits are not here, deliberately.** They are computed, measured, and dropped.
 *  Returning them would offer the analyst a menu of densities differing by a tuning constant they
 *  have no basis to choose among, and the question this answers is not "which fit" but "does the
 *  fit depend on the cuts".
 *
 *  @param baselineNumComponents the number of components in the reported fit
 *  @param numObservations the size of the sample, so that a count of reassigned observations can
 *  be read as a share
 *  @param displacements the signed cut displacements that were tried, each as a fraction of the
 *  span of the two groups the cut separates
 *  @param hellingerFromBaseline how far the refitted density sat from the reported one at each
 *  displacement. **Null where the refit could not be measured** — the displaced partition was
 *  inadmissible, no mixture could be assembled from it, or the quadrature could not represent
 *  both densities — because an unmeasured displacement is not a movement of zero.
 *  @param numObservationsReassigned how many observations changed groups at each displacement,
 *  or null where the displacement could not be applied at all
 *  @param gapShare the share of the sample that may change group and still count as nothing
 *  @param gapMovement the movement permitted alongside that share before the gap reading is refused
 *  @param followsShare the share above which the density is taken to follow the cuts
 */
class PartitionStability(
    val baselineNumComponents: Int,
    val numObservations: Int,
    val displacements: List<Double>,
    val hellingerFromBaseline: List<Double?>,
    val numObservationsReassigned: List<Int?>,
    val gapShare: Double = defaultGapShare,
    val gapMovement: Double = defaultGapMovement,
    val followsShare: Double = defaultFollowsShare
) {

    init {
        require(baselineNumComponents >= 2) {
            "A fit with fewer than two components has no cut to move. It had " +
                    "$baselineNumComponents"
        }
        require(numObservations > 0) { "The number of observations must be > 0" }
        require(displacements.isNotEmpty()) { "At least one cut displacement must be measured" }
        require(hellingerFromBaseline.size == displacements.size) {
            "There must be one movement per displacement"
        }
        require(numObservationsReassigned.size == displacements.size) {
            "There must be one reassignment count per displacement"
        }
        require(gapShare >= 0.0) { "The gap share must be >= 0. It was $gapShare" }
        require(gapMovement > 0.0) { "The gap movement must be > 0. It was $gapMovement" }
        require(followsShare > gapShare) {
            "The share at which the density follows the cuts ($followsShare) must exceed the " +
                    "share that counts as nothing ($gapShare)"
        }
    }

    /**
     *  The largest movement over the displacements that could be measured, or null when none could.
     */
    val maximumMovement: Double?
        get() = hellingerFromBaseline.filterNotNull().maxOrNull()

    /**
     *  How many displacements produced no usable measurement.
     *
     *  Reported separately rather than folded into the movement, for the same reason the designed
     *  experiment counts unmeasured attempts apart from failed ones: a distance that could not be
     *  computed is not a distance of zero, and averaging it in as one would understate movement
     *  exactly where the numerics or the admissibility rules were worst.
     */
    val numUnmeasured: Int
        get() = hellingerFromBaseline.count { it == null }

    /**
     *  The largest share of the sample that changed groups under any displacement.
     *
     *  **The statistic the reading mostly rests on**, and the cheap one: it depends on the
     *  displaced partition alone, so it is known before anything is refitted.
     */
    val largestShareReassigned: Double
        get() = (numObservationsReassigned.filterNotNull().maxOrNull() ?: 0).toDouble() /
                numObservations

    /**
     *  What the displacements revealed.
     *
     *  Requires every displacement to have been measured before it will report either of the
     *  informative readings: an unmeasured displacement is an unanswered question, and a signature
     *  established on the displacements that happened to work is not established.
     */
    val cutDependence: CutDependence
        get() {
            if (numUnmeasured > 0) return CutDependence.INCONCLUSIVE
            val movement = maximumMovement ?: return CutDependence.INCONCLUSIVE
            val share = largestShareReassigned
            if (share <= gapShare && movement <= gapMovement) return CutDependence.CUTS_LIE_IN_A_GAP
            if (share > followsShare) return CutDependence.DENSITY_FOLLOWS_THE_CUTS
            return CutDependence.INCONCLUSIVE
        }

    /**
     *  What a reader must be told alongside the reading.
     *
     *  **Says that the reading is calibrated, and not what the calibration found.** This sentence
     *  is printed to an analyst looking at their own fit, where the recovery rates behind the
     *  thresholds are a result about the method over synthetic truths rather than evidence about
     *  their data; those belong in the project report. Suppressing the provenance entirely would
     *  be the opposite error — a threshold fitted to one experiment would read as a theorem — so
     *  the status is stated and the numbers are not.
     */
    fun caveat(): String =
        "The thresholds behind this reading were calibrated against a designed experiment rather " +
                "than derived, so it is a rule of thumb rather than a theorem."

    override fun toString(): String = buildString {
        appendLine("Partition stability")
        appendLine("-".repeat(72))
        appendLine("  reported fit: $baselineNumComponents components over $numObservations observations")
        for (i in displacements.indices) {
            val movement = hellingerFromBaseline[i]
            val reassigned = numObservationsReassigned[i]
            val sign = if (displacements[i] < 0.0) "-" else "+"
            append("  cuts moved $sign${"%.2f".format(abs(displacements[i]))} of the local span: ")
            if (reassigned == null) {
                appendLine("could not be applied to this sample")
            } else if (movement == null) {
                appendLine("$reassigned observations changed groups, movement not measurable")
            } else {
                appendLine(
                    "$reassigned observations changed groups, " +
                            "density moved ${"%.4f".format(movement)}"
                )
            }
        }
        maximumMovement?.let { appendLine("  largest movement: ${"%.4f".format(it)}") }
        appendLine(
            "  largest share reassigned: ${"%.1f".format(100.0 * largestShareReassigned)}%"
        )
        if (numUnmeasured > 0) {
            appendLine("  $numUnmeasured of ${displacements.size} displacements could not be measured")
        }
        appendLine(
            when (cutDependence) {
                CutDependence.CUTS_LIE_IN_A_GAP ->
                    "  The cuts lie in a gap: they can be moved and essentially no observation " +
                            "changes group. The components are divided by gaps the data has."
                CutDependence.DENSITY_FOLLOWS_THE_CUTS ->
                    "  The density follows the cuts: displacing them reassigns much of the " +
                            "sample. Do not read the component shapes as physical."
                CutDependence.INCONCLUSIVE ->
                    if (numUnmeasured > 0) {
                        "  Not every displacement could be measured, so nothing is concluded."
                    } else {
                        "  Neither signature is present; this diagnostic has nothing to add."
                    }
            }
        )
        append("  ${caveat()}")
    }

    companion object {

        /**
         *  The cut displacements measured when none are given, as fractions of the span of the two
         *  groups each cut separates. Each is applied in both directions.
         *
         *  A twentieth and a tenth. Small enough that a cut lying in a real gap stays inside it,
         *  large enough that a cut placed arbitrarily through a single blob reassigns an
         *  appreciable share of the sample.
         */
        val defaultDisplacements: List<Double> = listOf(0.05, 0.10)

        /**
         *  The share of the sample that may change group and still count as none of it.
         *
         *  Two percent. **Calibrated jointly with the movement bound, because the two interact.**
         *  A first calibration set this at half a percent on the reasoning that the share was the
         *  binding constraint; sweeping both together over 7,128 measurable fits showed the
         *  opposite — with the movement bound at 0.05 the share does almost nothing, and raising it
         *  alone from 0.005 to 0.10 gains 46 fits out of seven thousand. Moving both to 0.02 and
         *  0.10 takes the reading from 3.9% of fits at 99.3% recovery to 10.3% at 99.0%: two and a
         *  half times the reach for three tenths of a point.
         */
        var defaultGapShare: Double = 0.02
            set(value) {
                require(value >= 0.0) { "The gap share must be >= 0" }
                field = value
            }

        /**
         *  The movement permitted alongside that share before the gap reading is refused.
         *
         *  A tenth. It was introduced as a guard against the case where a handful of reassigned
         *  observations sit in a tail and move the fitted density a long way, and it is still that —
         *  but the joint sweep showed it is also **the binding constraint of the pair**, not the
         *  share. Past 0.12 the reading degrades quickly: at 0.15 with a share of 0.10 precision
         *  falls to 76.7%, which is barely above the 57.4% baseline and not worth reporting as a
         *  signal.
         */
        var defaultGapMovement: Double = 0.10
            set(value) {
                require(value > 0.0) { "The gap movement must be > 0" }
                field = value
            }

        /**
         *  The share above which the density is taken to follow the cuts.
         *
         *  Fifteen percent. Chosen against the designed experiment, where fits above it recovered
         *  the component count on 33.7% of attempts against 57.4% overall, and on 37.6% of the fits
         *  the adequacy verdict had called `AMPLE`, where the fits beside them in that band reached
         *  89.4% — a warning that verdict cannot give by itself.
         */
        var defaultFollowsShare: Double = 0.15
            set(value) {
                require(value > 0.0) { "The follows share must be > 0" }
                field = value
            }

        /**
         *  Whether the thresholds have been calibrated against the designed experiment.
         *
         *  True since Phase G. What that calibration achieved, and on what, is in `caveat`, which
         *  any report showing a reading must carry with it.
         */
        const val thresholdsAreCalibrated: Boolean = true
    }
}
