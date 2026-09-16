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
 *  What is known about one candidate number of components.
 *
 *  @param numComponents the candidate count
 *  @param logLikelihood the observed-data log-likelihood of the best mixture at this count
 *  @param marginalGain the improvement in log-likelihood over the next count down. **May be
 *  negative**, and that is not a fault — see `ComponentCountEvidence`
 *  @param penaltyIncrement what the ranking criterion charges for the extra component
 *  @param criterionValues each reported criterion's value at this count
 *  @param isFeasible whether the count could be fitted at all
 */
data class ComponentCountRow(
    val numComponents: Int,
    val logLikelihood: Double?,
    val marginalGain: Double?,
    val penaltyIncrement: Double?,
    val criterionValues: Map<String, Double>,
    val isFeasible: Boolean
) {

    /**
     *  How far the gain exceeded the penalty at this count.
     *
     *  **This is the criterion's firmness, not its reliability.** See `ComponentCountEvidence`
     *  for what that distinction rests on and why it must be stated wherever this is shown.
     */
    val margin: Double?
        get() {
            val g = marginalGain ?: return null
            val p = penaltyIncrement ?: return null
            return g - p
        }
}

/**
 *  Why the criterion chose the number of components it chose, and how much that is worth.
 *
 *  **The central caution, which every display of this must carry.** It is tempting to read the
 *  size of the criterion's margin as a measure of how likely its choice is to be right. Measured
 *  across the designed experiment, it is not: when the criterion picks the wrong component count,
 *  the median gap to the truth is about **19** units, and only about **4.6%** of wrong calls fall
 *  inside a conventional weak-evidence margin of two. A large margin therefore means the criterion
 *  is not indifferent. It does **not** mean the criterion is right, and presenting it as though it
 *  did would give an analyst a confident reason to defer at exactly the moment deferring is wrong.
 *
 *  A *small* margin is the half that survives measurement: it genuinely does mean the criterion is
 *  nearly indifferent between two counts, which is worth knowing.
 *
 *  **What is worth more is agreement.** The criteria fail in opposite directions for reasons that
 *  are theorems — AIC over-selects, BIC under-selects — so their concurrence carries information
 *  their individual confidence does not. On the attempts where AIC and BIC agreed, the agreed
 *  count was correct on about 66% against a base rate of 37%, and the interval between their two
 *  choices covered the truth about 78% of the time. That interval is wide, averaging around three
 *  and a half of the eight candidate counts, and at the worst separations it is close to
 *  uninformative — but a wide interval is an answer too.
 *
 *  **On a negative marginal gain.** In textbook maximum-likelihood mixture fitting the
 *  log-likelihood cannot fall as components are added, because the models nest. Here they do not:
 *  the partition is regenerated for each count rather than split from the previous one, so the
 *  search can find a worse partition at the larger count. A negative gain is a real finding about
 *  the search rather than a numerical fault, and every display of it says so.
 *
 *  @param rows one row per candidate count, in increasing order
 *  @param hypothesisedCount the count the analyst supplied, when they supplied one
 *  @param criterionChoices what each reported criterion would choose
 */
class ComponentCountEvidence(
    val rows: List<ComponentCountRow>,
    val hypothesisedCount: Int?,
    val criterionChoices: Map<String, Int>
) {

    /**
     *  Whether every reported criterion chose the same number of components.
     *
     *  The headline of this object, in preference to any single criterion's margin, for the
     *  reason given in the class documentation.
     */
    val criteriaAgree: Boolean
        get() = criterionChoices.values.distinct().size == 1

    /**
     *  The range of counts the reported criteria span, or null when none of them chose.
     *
     *  Reported rather than a single number because the criteria bracket the truth from opposite
     *  sides: the lightest penalty over-selects and the heaviest under-selects, so the interval
     *  between them is the honest summary of what the criteria collectively support.
     */
    val criterionInterval: IntRange?
        get() {
            val choices = criterionChoices.values
            if (choices.isEmpty()) return null
            return choices.min()..choices.max()
        }

    /**
     *  How many candidate counts the criteria span, which is how much the interval is worth.
     *
     *  A width of one is agreement. A width approaching the number of counts fitted is close to
     *  no information at all, and is reported as such rather than presented as a range.
     */
    val criterionIntervalWidth: Int?
        get() = criterionInterval?.let { it.last - it.first + 1 }

    /**
     *  The row for a count, or null when it was not among those fitted.
     *
     *  @param numComponents the count wanted
     */
    fun rowAt(numComponents: Int): ComponentCountRow? =
        rows.firstOrNull { it.numComponents == numComponents }

    /**
     *  How firmly the criterion held its view at a count: the marginal gain less the penalty.
     *
     *  **Not a measure of how likely that view is to be correct.** Named for what it measures.
     *
     *  @param numComponents the count wanted
     */
    fun firmnessAt(numComponents: Int): Double? = rowAt(numComponents)?.margin

    /**
     *  Whether the criterion was nearly indifferent at a count.
     *
     *  This is the half of the margin that measurement supports. A margin within a couple of units
     *  means the criterion barely preferred one count to its neighbour, which is a real and useful
     *  thing for an analyst to know before deferring to it.
     *
     *  @param numComponents the count wanted
     *  @param within how small a margin counts as indifference
     */
    fun criterionWasNearlyIndifferentAt(numComponents: Int, within: Double = 2.0): Boolean {
        val m = firmnessAt(numComponents) ?: return false
        return abs(m) <= within
    }

    /**
     *  Prose an analyst can read, leading with agreement rather than with any margin.
     */
    fun explain(): String = buildString {
        val interval = criterionInterval
        if (interval == null) {
            appendLine("No criterion selected a number of components.")
            return@buildString
        }
        if (criteriaAgree) {
            appendLine(
                "All ${criterionChoices.size} reported criteria chose ${interval.first} " +
                        "components. Agreement is the most useful signal available here: on the " +
                        "designed experiment, counts that AIC and BIC agreed on were correct " +
                        "about 66% of the time against a base rate near 37%."
            )
        } else {
            appendLine(
                "The reported criteria disagree, spanning ${interval.first} to ${interval.last} " +
                        "components. They fail in opposite directions by construction — the " +
                        "lightest penalty over-selects and the heaviest under-selects — so this " +
                        "interval is what the criteria collectively support. It covered the truth " +
                        "about 78% of the time on the designed experiment."
            )
            if ((criterionIntervalWidth ?: 0) >= 4) {
                appendLine(
                    "  This interval is wide enough to be close to uninformative, which is " +
                            "itself the finding: the data does not pin the count down."
                )
            }
        }
        if (hypothesisedCount != null) {
            val chosen = criterionChoices.values.toSet()
            if (hypothesisedCount in chosen) {
                appendLine(
                    "  Your count of $hypothesisedCount is among those the criteria chose."
                )
            } else {
                appendLine(
                    "  Your count of $hypothesisedCount is not what the criteria chose. That is " +
                            "not evidence against it: across the designed experiment these " +
                            "criteria recovered the true count on well under half of attempts, " +
                            "and were most confident exactly when they were wrong."
                )
            }
            if (criterionWasNearlyIndifferentAt(hypothesisedCount)) {
                appendLine(
                    "  At $hypothesisedCount the criterion was nearly indifferent, so it barely " +
                            "preferred its own answer to yours."
                )
            }
        }
        if (rows.any { (it.marginalGain ?: 0.0) < 0.0 }) {
            appendLine(
                "  A negative gain appears below. The partition is regenerated at each count " +
                        "rather than split from the one before, so the models do not nest and a " +
                        "larger count can fit worse. That is a finding about the search, not a " +
                        "fault."
            )
        }
        append(
            "  The size of a criterion's lead is how firmly it holds its view, not how likely " +
                    "that view is to be right: when these criteria choose wrongly, the median " +
                    "gap to the truth is about 19."
        )
    }
}
