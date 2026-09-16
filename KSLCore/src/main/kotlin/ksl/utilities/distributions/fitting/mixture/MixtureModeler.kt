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

import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.distributions.fitting.ContinuousCDFGoodnessOfFit
import ksl.utilities.statistic.Statistic
import kotlin.math.sqrt
import ksl.utilities.distributions.fitting.mixture.partition.JenksPartitionGenerator
import ksl.utilities.distributions.fitting.mixture.partition.PartitionGeneratorIfc
import ksl.utilities.distributions.fitting.mixture.refine.BreakShiftRefiner
import ksl.utilities.distributions.fitting.mixture.refine.PartitionRefinerIfc
import ksl.utilities.distributions.fitting.mixture.refine.RefinementResult
import ksl.utilities.distributions.fitting.mixture.scoring.MixtureAICCriterion
import ksl.utilities.distributions.fitting.mixture.scoring.MixtureBICCriterion
import ksl.utilities.distributions.fitting.mixture.scoring.MixtureEBICCriterion
import ksl.utilities.distributions.fitting.mixture.scoring.MixtureHannanQuinnCriterion
import ksl.utilities.distributions.fitting.mixture.scoring.MixtureICLBICCriterion
import ksl.utilities.distributions.fitting.mixture.scoring.MixtureCriterionIfc
import ksl.utilities.distributions.fitting.mixture.scoring.MixtureCriterionValue
import ksl.utilities.distributions.fitting.mixture.search.FamilySelectorIfc
import ksl.utilities.distributions.fitting.mixture.search.SeparableCMLSelector
import ksl.utilities.distributions.fitting.diagnostics.ModalityAnalyzer
import ksl.utilities.distributions.fitting.diagnostics.ModalityAssessment
import ksl.utilities.io.plotting.FitDistPlot
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.statistic.Histogram
import ksl.utilities.statistic.HistogramIfc
import ksl.utilities.statistic.StatisticIfc
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.dataFrameOf

/**
 *  One evaluated candidate: the assembled mixture and its criterion value.
 *
 *  @param candidate the assembled mixture
 *  @param criterionValue the value of the criterion used to rank it
 */
data class RankedMixture(
    val candidate: MixtureCandidate,
    val criterionValue: MixtureCriterionValue
) {

    /**
     *  The fit as an ordinary continuous distribution.
     *
     *  This is the object the whole procedure exists to produce: it has a density and a
     *  distribution function, it reports a mean and a variance, and it will supply a random
     *  variable that a simulation can draw from. Everything the library can already do with a
     *  fitted distribution, it can do with this — which is why the diagnostics below need no new
     *  statistics written for them.
     *
     *  A one-component candidate is returned as that component rather than as a mixture of one;
     *  see `MixtureCandidate.toMixtureDistribution` for why.
     */
    val distribution: ContinuousDistributionIfc by lazy { candidate.toMixtureDistribution() }

    /**
     *  The number of free parameters charged by the information criteria, which the
     *  goodness-of-fit tests need in order to adjust their degrees of freedom.
     */
    val numberOfParameters: Int
        get() = candidate.numFreeParameters

    /**
     *  A short description of the fitted mixture, component by component.
     */
    val name: String
        get() = (0 until candidate.numComponents).joinToString("; ") { j ->
            "${"%.3f".format(candidate.weights[j])} x ${candidate.components[j].name}"
        }

    /**
     *  The four diagnostic plots the library draws for any fitted distribution: density against
     *  the data, a QQ plot, an ECDF comparison, and a PP plot.
     *
     *  @param data the observations the mixture was fitted to
     */
    fun distributionFitPlot(data: DoubleArray): FitDistPlot =
        FitDistPlot(data, distribution, distribution)

    /**
     *  The usual goodness-of-fit tests — Anderson–Darling, Cramér–von Mises and
     *  Kolmogorov–Smirnov, with their p-values — applied to the fitted mixture.
     *
     *  A mixture is a continuous distribution like any other, so the library's existing test
     *  machinery applies unchanged. The estimated-parameter count is taken from the candidate so
     *  that the tests charge for every parameter the fit actually spent, including each
     *  component's own and the mixing weights.
     *
     *  @param data the observations the mixture was fitted to
     */
    fun goodnessOfFit(data: DoubleArray): ContinuousCDFGoodnessOfFit =
        ContinuousCDFGoodnessOfFit(data, distribution, numEstimatedParameters = numberOfParameters)
}

/** The catalog's families whose support is a bounded interval. */
internal val boundedFamilies: Set<String> = setOf("Uniform", "Triangular", "GeneralizedBeta")

/**
 *  How much of a fresh sample a fitted mixture accounts for.
 *
 *  @param numEvaluated the held-out observations scored
 *  @param numZeroDensity how many the fit gave no density to at all
 *  @param averageLogLikelihood the mean held-out log-likelihood, or null when any observation had
 *  zero density and the total is therefore infinite
 */
data class MixtureCoverage(
    val numEvaluated: Int,
    val numZeroDensity: Int,
    val averageLogLikelihood: Double?
) {

    /** The share of held-out observations the fit cannot account for. */
    val uncoveredShare: Double
        get() = if (numEvaluated == 0) 0.0 else numZeroDensity.toDouble() / numEvaluated

    override fun toString(): String = buildString {
        appendLine("Coverage of the held-out sample")
        appendLine("  observations scored     : $numEvaluated")
        appendLine("  given no density at all : $numZeroDensity (${"%.2f".format(100.0 * uncoveredShare)}%)")
        appendLine(
            "  average log-likelihood  : " +
                    (averageLogLikelihood?.let { "%.4f".format(it) }
                        ?: "undefined, because some held-out data fell outside the fit's support")
        )
    }
}

/**
 *  Everything produced by one modeling run.
 *
 *  @param results the evaluated candidates, best first
 *  @param certificate the structural facts of the sample
 *  @param groupFitsByGroupCount the fit results per group, keyed by number of components
 *  @param rejectedGroupCounts the requested numbers of components that were not attempted,
 *  with the reason
 *  @param cacheHitRate the fraction of group fits answered from the cache
 *  @param truncatedGroupCounts the numbers of components whose family search was cut short, with
 *  what was cut. These were attempted and produced an answer; that answer may simply not be the
 *  best available, which is a different fact from the count being unusable and is reported
 *  separately for that reason.
 *  @param refinementsByGroupCount what the refiner did at each number of components
 *  @param sortedData the observations the mixtures were fitted to, in order. Carried so that the
 *  diagnostics can be asked for without handing the data back in, which is how the library's
 *  single-distribution results behave.
 *  @param criterion the criterion that ranked the candidates. Needed to orient a comparison:
 *  whether a smaller value is an improvement is a property of the criterion, not of the number.
 *  @param specifiedComponentCounts the numbers of components the caller asked for, when they
 *  asked rather than letting the criterion choose. Null after an ordinary search. It changes what
 *  the results mean: the recommendation comes from the caller's counts, and the criterion values
 *  are evidence about that choice rather than a selection over a searched range.
 */
class MixtureModelingResults(
    val results: List<RankedMixture>,
    val certificate: AdmissibilityCertificate,
    val groupFitsByGroupCount: Map<Int, List<GroupFitResult>>,
    val rejectedGroupCounts: Map<Int, String>,
    val cacheHitRate: Double,
    val truncatedGroupCounts: Map<Int, String> = emptyMap(),
    val refinementsByGroupCount: Map<Int, RefinementResult> = emptyMap(),
    private val sortedData: DoubleArray = DoubleArray(0),
    val criterion: MixtureCriterionIfc = MixtureBICCriterion(),
    val specifiedComponentCounts: Set<Int>? = null
) {

    /**
     *  The observations the mixtures were fitted to, in order.
     *
     *  A copy, so that a caller cannot reach in and change what the results were computed from.
     *  Exposed because the diagnostics a report needs — the sample's own statistics, and the fit
     *  plots for the single-distribution baseline — are functions of the data as well as the fit.
     */
    val observations: DoubleArray
        get() = sortedData.copyOf()

    /**
     *  Whether the number of components was supplied by the caller rather than selected.
     *
     *  Worth checking before reading anything as a selection. An analyst who reads a component
     *  count off a histogram is making a judgement the criteria are measurably poor at, so the
     *  distinction is not a formality.
     */
    val numComponentsWasSpecified: Boolean
        get() = specifiedComponentCounts != null

    /**
     *  The single count the caller asked for, or null when they asked for several or none.
     *
     *  A convenience for the common case; `specifiedComponentCounts` is the general form.
     */
    val specifiedNumComponents: Int?
        get() = specifiedComponentCounts?.singleOrNull()

    /**
     *  The recommended candidate, or null when nothing could be fitted.
     *
     *  After an ordinary search this is the best by the ranking criterion. When counts were
     *  specified it is the best *among those counts* — so a caller weighing two readings of a
     *  histogram gets the criterion's opinion between them, while the one-component baseline
     *  fitted for comparison cannot displace either.
     */
    val best: RankedMixture?
        get() = specifiedComponentCounts
            ?.let { counts -> results.firstOrNull { it.candidate.numComponents in counts } }
            ?: results.firstOrNull()

    /**
     *  Whether this sample is large enough for the recommended component count to be answerable
     *  at all, or null when the question does not arise.
     *
     *  **Read this before the count, not after it.** The component count is the least reliable
     *  thing this class reports, and how reliable it can possibly be is governed by the sample
     *  size and by how far the fitted mixture sits from the nearest mixture with one component
     *  fewer. Where that product is small, no procedure could settle the question and the number
     *  below is close to a guess.
     *
     *  Null when the recommendation has a single component, since there is then no count to
     *  settle, and null when the separability could not be computed — reported as absent rather
     *  than as an adequate or inadequate verdict.
     *
     *  Computed on demand rather than eagerly: it costs one minimisation over the fitted mixture,
     *  which is far cheaper than a bootstrap but not free.
     */
    fun countAdequacy(): CountAdequacy? {
        val b = best ?: return null
        return CountAdequacy.of(
            b.candidate.weights,
            b.candidate.distributions,
            b.candidate.components.map { it.rvType },
            sortedData.size
        )
    }

    /**
     *  The recommended number of components, or null when nothing could be fitted.
     *
     *  Treat this as a suggestion rather than a determination. Measured across the designed
     *  experiment, the criterion behind it recovers the true number of components on well under
     *  half of attempts, so the profile in `bestByNumComponents` is a more honest thing to look
     *  at than this single number.
     */
    val recommendedNumComponents: Int?
        get() = best?.candidate?.numComponents

    /**
     *  The recommendation as an ordinary continuous distribution, ready to be sampled from,
     *  plotted, or handed to a simulation model. Null when nothing could be fitted.
     *
     *  This is a mixture when more than one component was recommended and a plain distribution
     *  when one was, which is what the recommendation actually is in each case.
     */
    val recommendedDistribution: ContinuousDistributionIfc?
        get() = best?.distribution

    /**
     *  The four diagnostic plots for the recommended mixture, or null when nothing was fitted.
     */
    fun distributionFitPlot(): FitDistPlot? = best?.distributionFitPlot(sortedData)

    /**
     *  Goodness-of-fit tests for the recommended mixture, or null when nothing was fitted.
     */
    fun goodnessOfFit(): ContinuousCDFGoodnessOfFit? = best?.goodnessOfFit(sortedData)

    /**
     *  The best single distribution, which is simply the best candidate at one component.
     *
     *  A one-component mixture *is* a single distribution, fitted from the same catalog and
     *  ranked by the same criterion, so no separate fit is required and the comparison against it
     *  is exact rather than approximate. Null when one component was not among those searched.
     */
    val singleDistribution: RankedMixture?
        get() = bestByNumComponents()[1]

    /**
     *  How much better the recommended mixture is than the best single distribution, in the
     *  criterion's own units, oriented so that a positive number always means the mixture is
     *  better. Null when either is unavailable.
     */
    val improvementOverSingle: Double?
        get() {
            val b = best?.criterionValue ?: return null
            val one = singleDistribution?.criterionValue ?: return null
            if (!b.isComparable || !one.isComparable) return null
            return if (criterion.smallerIsBetter) one.value - b.value else b.value - one.value
        }

    /**
     *  Whether the criterion prefers the recommended mixture to the best single distribution.
     *
     *  This is the first question a user has and it deserves a direct answer rather than a table
     *  they must interpret. False does not mean the data are unimodal; it means the criterion did
     *  not judge the extra components worth their parameters on this sample.
     *
     *  **False when no comparison was made** — when one component was not among those fitted,
     *  there is nothing to prefer the mixture over, and this answers no rather than yes. Use
     *  `improvementOverSingle` to tell the two apart: null means unavailable, a number means
     *  measured. For "does the answer have more than one component", which is a different
     *  question, use `hasMultipleComponents`.
     */
    val mixtureBeatsSingleDistribution: Boolean
        get() = (improvementOverSingle ?: 0.0) > 0.0

    /**
     *  Whether the recommendation has more than one component.
     *
     *  A structural fact about the answer, carrying no claim that a mixture was worth having.
     *  Separated from `mixtureBeatsSingleDistribution` because conflating the two lets a result
     *  claim a mixture was preferred when nothing was compared against it.
     */
    val hasMultipleComponents: Boolean
        get() = (recommendedNumComponents ?: 1) > 1

    /**
     *  A direct answer to "was a mixture worth it?", comparing the recommendation against the best
     *  single distribution on the criterion and on the usual goodness-of-fit tests.
     *
     *  The goodness-of-fit tests are reported for both because the criterion difference alone is
     *  hard to read: it is on an arbitrary scale and says nothing about whether *either* fit is
     *  defensible. A mixture that beats a single distribution which both tests reject is a
     *  different situation from one that beats a single distribution neither test rejects.
     */
    fun worthItSummary(): String {
        val b = best ?: return "No mixture could be fitted."
        val one = singleDistribution
            ?: return "One component was not among those searched, so no comparison is available."
        return buildString {
            appendLine("Was a mixture worth it?")
            appendLine("-".repeat(60))
            appendLine("  best single distribution : ${one.candidate.components[0].name}")
            appendLine("  recommended mixture      : k = ${b.candidate.numComponents}")
            appendLine()
            appendLine("  %-26s %14s %14s".format("", "single", "mixture"))
            appendLine(
                "  %-26s %14d %14d".format(
                    "free parameters", one.numberOfParameters, b.numberOfParameters
                )
            )
            appendLine(
                "  %-26s %14.3f %14.3f".format(
                    criterion.name, one.criterionValue.value, b.criterionValue.value
                )
            )
            if (sortedData.isNotEmpty()) {
                val singleGof = one.goodnessOfFit(sortedData)
                val mixtureGof = b.goodnessOfFit(sortedData)
                appendLine(
                    "  %-26s %14.4f %14.4f".format(
                        "Anderson-Darling", singleGof.andersonDarlingStatistic,
                        mixtureGof.andersonDarlingStatistic
                    )
                )
                appendLine(
                    "  %-26s %14.4f %14.4f".format(
                        "  p-value", singleGof.andersonDarlingPValue, mixtureGof.andersonDarlingPValue
                    )
                )
                appendLine(
                    "  %-26s %14.4f %14.4f".format(
                        "Kolmogorov-Smirnov", singleGof.ksStatistic, mixtureGof.ksStatistic
                    )
                )
                appendLine(
                    "  %-26s %14.4f %14.4f".format(
                        "  p-value", singleGof.ksPValue, mixtureGof.ksPValue
                    )
                )
            }
            appendLine()
            val improvement = improvementOverSingle
            if (improvement == null || improvement.isNaN()) {
                appendLine("  Verdict: unavailable. The two values cannot be compared.")
            } else if (improvement > 0.0) {
                appendLine(
                    "  Verdict: YES. The mixture improves ${criterion.name} by " +
                            "${"%.3f".format(improvement)}."
                )
            } else {
                appendLine(
                    "  Verdict: NO. The criterion preferred a single distribution" +
                            if (numComponentsWasSpecified) {
                                ", by ${"%.3f".format(-improvement)}, and k was specified rather " +
                                        "than selected."
                            } else "."
                )
            }
        }
    }

    /**
     *  How many families the fitter offered, inferred from what a group actually attempted:
     *  the candidates that succeeded plus the ones that were rejected. Needed by the extended
     *  criterion, which charges for the size of the model space being searched.
     */
    val catalogSize: Int
        get() = groupFitsByGroupCount.values.firstOrNull()?.firstOrNull()
            ?.let { it.candidates.size + it.rejections.size } ?: 0

    /**
     *  Every reported criterion's value at every number of components that was fitted.
     *
     *  One criterion ranks the candidates, but no single criterion is trustworthy here — they
     *  disagree, and they disagree in ways a user should see rather than have resolved for them.
     *
     *  **An approximation worth stating.** The candidate scored at each count is the one the
     *  ranking criterion selected, not the one each criterion would have selected for itself. At
     *  a fixed count and partition the criteria share their likelihood term and differ only in
     *  penalty, so they rank identically unless parameter counts differ; the reported value is
     *  always the criterion's own value on the candidate named.
     *
     *  @param criteria the criteria to report
     */
    fun criterionProfile(
        criteria: List<MixtureCriterionIfc> = MixtureModeler.defaultReportedCriteria(catalogSize)
    ): Map<String, Map<Int, MixtureCriterionValue>> {
        val byK = bestByNumComponents()
        return criteria.associate { c ->
            c.name to byK.mapValues { (_, ranked) -> c.evaluate(ranked.candidate, sortedData) }
        }
    }

    /**
     *  What each criterion would choose, and whether it actually chose.
     *
     *  A criterion whose smallest value sits at the largest count fitted has not selected a
     *  number of components — it has run out of candidates, and would keep going if the range
     *  were widened. That is a different thing from choosing, and the two are indistinguishable
     *  if only the winner is reported.
     *
     *  @param criteria the criteria to report
     */
    fun criterionSummary(
        criteria: List<MixtureCriterionIfc> = MixtureModeler.defaultReportedCriteria(catalogSize)
    ): String {
        val profile = criterionProfile(criteria)
        val counts = bestByNumComponents().keys.sorted()
        if (counts.isEmpty()) return "No candidate was fitted."
        return buildString {
            appendLine("Criterion value at each number of components (smaller is better)")
            appendLine("-".repeat(64))
            append("  %-10s".format("k ="))
            for (k in counts) append("%12d".format(k))
            appendLine()
            for (c in criteria) {
                val values = profile[c.name] ?: continue
                append("  %-10s".format(c.name))
                for (k in counts) {
                    val v = values[k]
                    append(if (v != null && v.isComparable) "%12.1f".format(v.value) else "%12s".format("—"))
                }
                appendLine()
            }
            appendLine()
            if (numComponentsWasSpecified) {
                val asked = specifiedComponentCounts!!.sorted()
                val phrase = if (asked.size == 1) "k = ${asked.single()} was" else
                    "k in {${asked.joinToString(", ")}} were"
                appendLine("  $phrase specified rather than selected. The values above are evidence")
                appendLine("  for or against that choice, not a search — so no criterion here has run out")
                appendLine("  of candidates.")
                appendLine()
            }
            for (c in criteria) {
                val values = profile[c.name] ?: continue
                val comparable = values.filterValues { it.isComparable }
                if (comparable.isEmpty()) {
                    appendLine("  %-10s no comparable value".format(c.name))
                    continue
                }
                val chosen = if (c.smallerIsBetter) {
                    comparable.minByOrNull { it.value.value }!!.key
                } else {
                    comparable.maxByOrNull { it.value.value }!!.key
                }
                if (numComponentsWasSpecified) {
                    // No boundary annotation: with a specified count every value sits at an end of
                    // the range by construction, so the warning would fire on every line of every
                    // report and mean nothing.
                    appendLine("  %-10s prefers k = %d".format(c.name, chosen))
                    continue
                }
                val note = when (chosen) {
                    counts.last() -> "  <- at the largest count fitted: it ran out of candidates " +
                            "rather than finding a best"
                    counts.first() -> "  <- at the smallest count fitted"
                    else -> ""
                }
                appendLine("  %-10s chooses k = %d%s".format(c.name, chosen, note))
            }
        }
    }

    /**
     *  How much of a fresh sample the recommended fit cannot account for at all.
     *
     *  A mixture assembled from contiguous groups has support equal to the union of its
     *  components' supports. When a bounded family is chosen the mixture assigns no density
     *  outside it, so fresh data can land in a gap and receive density zero — which makes the
     *  held-out log-likelihood infinite and therefore unavailable exactly when bounded families
     *  were selected, which is not at random.
     *
     *  The share of held-out data with no density is defined whatever happens, and for this
     *  method it is the more informative statistic. Treat a share above a percent or two as a
     *  warning that the mixture may be describing the sample rather than the process.
     *
     *  @param heldOut observations the fit has not seen
     */
    fun coverage(heldOut: DoubleArray): MixtureCoverage? {
        val b = best ?: return null
        require(heldOut.isNotEmpty()) { "There must be at least one held-out observation" }
        val evaluation = MixtureLogLikelihood.evaluate(
            heldOut, b.candidate.weights, b.candidate.distributions
        )
        return MixtureCoverage(
            numEvaluated = heldOut.size,
            numZeroDensity = evaluation.numZeroDensity,
            averageLogLikelihood = if (evaluation.isUsable) evaluation.value / heldOut.size else null
        )
    }

    /**
     *  Why the criterion chose what it chose, assembled from the per-count profile.
     *
     *  Everything here is already computed; this gathers it into the shape an analyst deciding
     *  whether to keep their own count actually needs. Read `ComponentCountEvidence` before
     *  displaying any of it — the size of a criterion's lead is how firmly it holds its view and
     *  not how likely that view is to be right, and the two are easy to conflate.
     *
     *  @param criteria the criteria to report
     */
    fun componentCountEvidence(
        criteria: List<MixtureCriterionIfc> = MixtureModeler.defaultReportedCriteria(catalogSize)
    ): ComponentCountEvidence {
        val byK = bestByNumComponents()
        val profile = criterionProfile(criteria)
        val counts = byK.keys.sorted()
        val rows = mutableListOf<ComponentCountRow>()
        for (k in counts) {
            val ranked = byK[k]
            val ll = ranked?.let {
                val e = MixtureLogLikelihood.evaluate(sortedData, it.candidate.weights, it.candidate.distributions)
                if (e.isUsable) e.value else null
            }
            val previous = rows.lastOrNull()
            // The gain is against the previous count actually fitted, which need not be k-1 when a
            // count was refused; comparing across a gap would attribute two components' worth of
            // improvement to one.
            val gain = if (previous?.numComponents == k - 1 && ll != null && previous.logLikelihood != null) {
                ll - previous.logLikelihood
            } else null
            val penalty = if (previous?.numComponents == k - 1 && ranked != null) {
                val here = criterion.evaluate(ranked.candidate, sortedData)
                val there = byK[k - 1]?.let { criterion.evaluate(it.candidate, sortedData) }
                if (here.isComparable && there != null && there.isComparable && ll != null &&
                    previous.logLikelihood != null
                ) {
                    // criterion = -2 logL + penalty, so the penalty increment is the change in the
                    // criterion plus twice the change in the log-likelihood
                    (here.value - there.value) + 2.0 * (ll - previous.logLikelihood)
                } else null
            } else null
            rows.add(
                ComponentCountRow(
                    numComponents = k,
                    logLikelihood = ll,
                    // reported on the same scale as the penalty, which charges -2 logL
                    marginalGain = gain?.let { 2.0 * it },
                    penaltyIncrement = penalty,
                    criterionValues = profile.mapNotNull { (name, byCount) ->
                        byCount[k]?.takeIf { it.isComparable }?.let { name to it.value }
                    }.toMap(),
                    isFeasible = ranked != null
                )
            )
        }
        for ((k, reason) in rejectedGroupCounts) {
            if (counts.contains(k)) continue
            rows.add(ComponentCountRow(k, null, null, null, emptyMap(), false))
        }
        return ComponentCountEvidence(
            rows.sortedBy { it.numComponents },
            specifiedNumComponents,
            componentCountAgreement(criteria)
        )
    }

    /**
     *  How far the reported criteria agree about the number of components.
     *
     *  Disagreement is the honest headline when it exists. A single recommendation reported
     *  without it invites a confidence the evidence does not support: measured across a designed
     *  experiment, the best of these criteria recovers the true count on well under half of
     *  attempts, and they frequently choose differently on the same data.
     *
     *  @param criteria the criteria to consult
     */
    fun componentCountAgreement(
        criteria: List<MixtureCriterionIfc> = MixtureModeler.defaultReportedCriteria(catalogSize)
    ): Map<String, Int> {
        val profile = criterionProfile(criteria)
        val chosen = mutableMapOf<String, Int>()
        for (c in criteria) {
            val values = profile[c.name]?.filterValues { it.isComparable } ?: continue
            if (values.isEmpty()) continue
            chosen[c.name] = if (c.smallerIsBetter) {
                values.minByOrNull { it.value.value }!!.key
            } else {
                values.maxByOrNull { it.value.value }!!.key
            }
        }
        return chosen
    }

    /**
     *  Whether the recommended mixture's components are ones the data can actually tell apart,
     *  or null when the comparison could not be made.
     *
     *  The integrated completed likelihood is the Bayesian criterion plus twice the classification
     *  entropy, so the two differ only in whether a component that improves the density but not
     *  the classification is worth its cost. Where the entropy-penalised criterion prefers **fewer**
     *  components than the plain one, the components the plain criterion wanted are ones the model
     *  can fit but cannot separate: the extra structure improves the density without improving the
     *  assignment of observations to components.
     *
     *  Both criteria are already computed, so this is a comparison rather than a computation. It
     *  matters for interpretation rather than for the fit: a mixture used as a flexible density
     *  does not need separable components, while a mixture read as a description of subpopulations
     *  does.
     *
     *  True when the two agree, so components are separable as far as this comparison can tell.
     *
     *  @param criteria the criteria to consult; must include both BIC and ICL-BIC to be usable
     */
    fun componentsAreSeparable(
        criteria: List<MixtureCriterionIfc> = MixtureModeler.defaultReportedCriteria(catalogSize)
    ): Boolean? {
        val chosen = componentCountAgreement(criteria)
        val plain = chosen["BIC"] ?: return null
        val entropyPenalised = chosen["ICL-BIC"] ?: return null
        return entropyPenalised >= plain
    }

    /**
     *  Whether a fitted mixture shows the signature of having been over-fitted.
     *
     *  A partition into many groups makes each component a local fit to a narrow slice, and the
     *  natural local fit to a narrow slice is often a bounded family. Several adjacent bounded
     *  components are a piecewise-constant density — a histogram — so a fit that is mostly
     *  bounded may be tracking the sample rather than describing the process.
     *
     *  Measured across the designed experiment, fits that over-selected the component count were
     *  far more bounded than those that did not: on a truth of pure normals, 91% of the
     *  components of over-selecting fits were bounded against 35% otherwise.
     *
     *  This is a warning, not a verdict. Data that genuinely is bounded should of course be
     *  fitted with bounded families, and then a high share means nothing is wrong. It is
     *  informative only when there is no reason for the data to be bounded.
     */
    val boundedComponentShare: Double?
        get() {
            val b = best ?: return null
            val bounded = b.candidate.components.count { it.rvType.toString() in boundedFamilies }
            return bounded.toDouble() / b.candidate.numComponents
        }

    /**
     *  The recommended mixture as a table, one row per component.
     *
     *  Reported because a mixture's parts are the interesting thing about it and a single line of
     *  output cannot show them. Note the caution attached to `recommendedNumComponents`: the
     *  density these components combine to is usually good, while the components themselves are
     *  recovered much less reliably.
     */
    fun componentTable(): String {
        val b = best ?: return "no mixture was fitted"
        val candidate = b.candidate
        return buildString {
            appendLine("%-4s %-12s %8s %10s %s".format("j", "family", "weight", "n", "fitted"))
            for (j in 0 until candidate.numComponents) {
                val component = candidate.components[j]
                appendLine(
                    "%-4d %-12s %8.4f %10d %s".format(
                        j + 1, component.rvType.toString(), candidate.weights[j],
                        candidate.partition.sizeOf(j), component.name
                    )
                )
            }
        }
    }

    /**
     *  The best candidate for each number of components attempted, which is the profile a
     *  reader needs in order to judge how decisively the recommendation was made.
     */
    fun bestByNumComponents(): Map<Int, RankedMixture> {
        return results.groupBy { it.candidate.numComponents }
            .mapValues { (_, v) -> v.first() }
            .toSortedMap()
    }

    /**
     *  Every family that failed to fit some group, with the estimator's explanation and how
     *  often it happened. Estimator failure rates are an experimental result, so they are
     *  surfaced rather than logged and forgotten.
     */
    fun rejectionSummary(): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for ((_, fits) in groupFitsByGroupCount) {
            for (fit in fits) {
                for (rejection in fit.rejections) {
                    val key = "${rejection.rvType ?: "unknown"}: ${rejection.message}"
                    counts[key] = (counts[key] ?: 0) + 1
                }
            }
        }
        return counts.toList().sortedByDescending { it.second }.toMap()
    }

    /**
     *  The recommended mixture's components, one row per component.
     *
     *  Offered as a data frame because the components are the interesting part of a mixture and a
     *  reader will want to sort, filter and join them rather than read a printed block.
     */
    fun componentsAsDataFrame(): AnyFrame {
        val b = best ?: return DataFrame.empty()
        val candidate = b.candidate
        val indices = mutableListOf<Double>()
        val weights = mutableListOf<Double>()
        val sizes = mutableListOf<Double>()
        val parameters = mutableListOf<Double>()
        val families = mutableListOf<String>()
        val descriptions = mutableListOf<String>()
        for (j in 0 until candidate.numComponents) {
            indices.add((j + 1).toDouble())
            weights.add(candidate.weights[j])
            sizes.add(candidate.partition.sizeOf(j).toDouble())
            parameters.add(candidate.components[j].numParameters.toDouble())
            families.add(candidate.components[j].rvType.toString())
            descriptions.add(candidate.components[j].name)
        }
        return dataFrameOf(
            "component" to indices,
            "family" to families,
            "weight" to weights,
            "observations" to sizes,
            "parameters" to parameters,
            "fitted" to descriptions
        )
    }

    /**
     *  Every reported criterion's value at every number of components, in long form.
     *
     *  Long rather than wide so that the frame can be grouped and plotted without reshaping, and
     *  so that a criterion which could not score a candidate is a row marked incomparable rather
     *  than a hole in a column.
     *
     *  @param criteria the criteria to report
     */
    fun criterionProfileAsDataFrame(
        criteria: List<MixtureCriterionIfc> = MixtureModeler.defaultReportedCriteria(catalogSize)
    ): AnyFrame {
        val profile = criterionProfile(criteria)
        if (profile.isEmpty()) return DataFrame.empty()
        val names = mutableListOf<String>()
        val counts = mutableListOf<Double>()
        val values = mutableListOf<Double>()
        val comparable = mutableListOf<String>()
        for ((name, byK) in profile) {
            for ((k, value) in byK.toSortedMap()) {
                names.add(name)
                counts.add(k.toDouble())
                values.add(if (value.isComparable) value.value else Double.NaN)
                comparable.add(value.isComparable.toString())
            }
        }
        return dataFrameOf(
            "criterion" to names,
            "numComponents" to counts,
            "value" to values,
            "isComparable" to comparable
        )
    }


    /**
     *  Everything a reader needs in order to judge the recommendation, in one place.
     *
     *  Assembled rather than left for the caller to compose, because a user who has to know which
     *  five methods to call in what order will call one of them and stop. The order is
     *  deliberate: what the data are, whether a mixture was worth having, what was recommended,
     *  how much the criteria agreed, and what should make you doubt it.
     *
     *  @param heldOut observations the fit has not seen. When supplied, the share the fit cannot
     *  account for is reported, which is the strongest check available without a known truth.
     */
    fun summary(heldOut: DoubleArray? = null): String = buildString {
        appendLine("=".repeat(72))
        appendLine("Mixture modeling summary")
        appendLine("=".repeat(72))
        appendLine()
        appendLine("The sample")
        appendLine("-".repeat(72))
        appendLine("  $certificate")
        appendLine("  candidates evaluated: ${results.size}, cache hit rate ${"%.3f".format(cacheHitRate)}")
        if (rejectedGroupCounts.isNotEmpty()) {
            for ((k, reason) in rejectedGroupCounts) appendLine("  k=$k not attempted: $reason")
        }
        if (truncatedGroupCounts.isNotEmpty()) {
            for ((k, note) in truncatedGroupCounts) appendLine("  k=$k truncated: $note")
        }
        appendLine()
        appendLine(worthItSummary())
        appendLine("The recommended mixture")
        appendLine("-".repeat(72))
        append(componentTable())
        appendLine()
        appendLine(criterionSummary())

        appendLine("What should make you doubt this")
        appendLine("-".repeat(72))
        val agreement = componentCountAgreement()
        val distinctChoices = agreement.values.distinct()
        if (numComponentsWasSpecified) {
            // Agreement among criteria that were only ever offered the specified count and a
            // one-component baseline is close to guaranteed and says almost nothing. Reporting it
            // as corroboration would manufacture confidence in a count nothing here tested.
            val asked = specifiedComponentCounts!!.sorted()
            appendLine(
                "  The number of components was specified, not selected, so nothing here is " +
                        "evidence"
            )
            appendLine(
                "  that ${asked.joinToString(" or ")} is the right count — only how the fitted " +
                        "counts compare against"
            )
            appendLine(
                "  one component. Across a designed experiment the criteria recover the true " +
                        "count on"
            )
            appendLine(
                "  well under half of attempts, so a considered choice is defensible; a search " +
                        "over a"
            )
            appendLine("  range would at least tell you which counts the criteria find comparable.")
        } else if (distinctChoices.size > 1) {
            appendLine(
                "  The criteria disagree about the number of components: " +
                        agreement.entries.joinToString(", ") { "${it.key} says ${it.value}" } + "."
            )
            appendLine("  Treat the recommended count as one defensible answer among several.")
        } else {
            appendLine("  All reported criteria chose the same number of components.")
        }
        boundedComponentShare?.let { share ->
            if (share >= 0.5) {
                appendLine(
                    "  ${"%.0f".format(100.0 * share)}% of the components are bounded families. " +
                            "If the data has no reason to be bounded, that is the signature of a"
                )
                appendLine("  fit that has carved the sample up rather than described the process.")
            }
        }
        if (heldOut != null) {
            val cov = coverage(heldOut)
            if (cov != null) {
                appendLine()
                append(cov)
                if (cov.uncoveredShare > 0.01) {
                    appendLine(
                        "  More than one percent of fresh data falls outside the fit's support."
                    )
                }
            }
        } else {
            appendLine("  No held-out data was supplied, so nothing here tests the fit against")
            appendLine("  observations it has not already seen. That is the check worth making.")
        }
        appendLine()
        appendLine("  Across a designed experiment this method recovers the density well and the")
        appendLine("  component structure poorly. Use the fit to generate values; be cautious")
        appendLine("  about interpreting its parts.")
    }

    /**
     *  Writes the summary and opens the diagnostic plots.
     *
     *  The single call a user should need. Mirrors the way the library's single-distribution
     *  modeler shows all of its results at once.
     *
     *  @param heldOut observations the fit has not seen, if any
     */
    fun showAllResultsInBrowser(heldOut: DoubleArray? = null) {
        println(summary(heldOut))
        distributionFitPlot()?.showInBrowser("Recommended mixture")
        singleDistribution?.distributionFitPlot(sortedData)
            ?.showInBrowser("Best single distribution")
    }

    override fun toString(): String {
        return buildString {
            appendLine("MixtureModelingResults")
            appendLine("  certificate: $certificate")
            appendLine("  candidates evaluated: ${results.size}")
            appendLine("  cache hit rate: ${"%.3f".format(cacheHitRate)}")
            if (rejectedGroupCounts.isNotEmpty()) {
                appendLine("  group counts not attempted:")
                for ((k, reason) in rejectedGroupCounts) appendLine("    k=$k: $reason")
            }
            if (truncatedGroupCounts.isNotEmpty()) {
                appendLine("  group counts whose family search was truncated:")
                for ((k, note) in truncatedGroupCounts) appendLine("    k=$k: $note")
            }
            val b = best
            if (b == null) {
                appendLine("  no candidate could be fitted")
            } else {
                appendLine("  recommended k = ${b.candidate.numComponents}")
                improvementOverSingle?.let {
                    appendLine(
                        "  better than the best single distribution by " +
                                "${"%.3f".format(it)} ${criterion.name}"
                    )
                }
                appendLine("  ${b.candidate}")
            }
        }
    }
}

/**
 *  Fits a mixture of continuous distributions to univariate data by partitioning the sorted
 *  sample, fitting a component to each part, and ranking the assembled mixtures.
 *
 *  By default the facade reproduces the configuration the designed experiment measured as best:
 *  a Jenks initial partition, refinement by local search over the cut positions, and separable
 *  family selection. Every one of those is a parameter of `fit`, so the unrefined baseline and
 *  the exhaustive family search remain available for comparison — but a user who asks for nothing
 *  in particular gets the configuration the evidence supports rather than the simplest one.
 *
 *  The number of components to consider is intersected with what the data can structurally
 *  support before anything is fitted, so an impossible request is reported rather than
 *  discovered as a failure part-way through.
 *
 *  **Which entry point to use.** `fitSpecified` is the documented path and `fit` over a range is
 *  the sensitivity tool, which is the opposite of what a distribution-fitting facade usually
 *  implies. The reason is measured rather than stylistic: across the designed experiment the
 *  criterion recovers the true number of components on about 36% of attempts, under-selects eight
 *  times more often than it over-selects, and is *confidently* wrong when it is wrong — the median
 *  criterion gap to the truth is around 19, so the size of the gap is not a signal that the choice
 *  is reliable. An analyst who has a mechanism in mind, or who has read a count off a plot, holds
 *  better information than the search does, and this class should not silently overrule them.
 *
 *  So: name the count when you have one, and use the range form to ask how sensitive the answer is
 *  to that choice. Neither is more supported than the other computationally; the difference is
 *  which question is being asked.
 *
 *  @param data the observations, which need not be sorted; a sorted copy is taken
 *  @param fitter the component fitter, wrapped in a cache by this class
 *  @param criterion the criterion used to rank assembled mixtures
 *  @param minimumGroupSize the least number of observations permitted in a group
 *  @param minimumDistinctValues the least number of distinct values permitted in a group
 */
class MixtureModeler(
    data: DoubleArray,
    fitter: ComponentFitterIfc = PDFComponentFitter(),
    val criterion: MixtureCriterionIfc = MixtureBICCriterion(),
    minimumGroupSize: Int = AdmissibilityCertificate.defaultMinimumGroupSize,
    minimumDistinctValues: Int = AdmissibilityCertificate.defaultMinimumDistinctValues
) {

    private val mySortedData: DoubleArray = data.sortedArray()
    private val myCache = ComponentFitCache(fitter)

    /**
     *  The structural facts of the sample, computed once.
     */
    val certificate: AdmissibilityCertificate =
        AdmissibilityCertificate(mySortedData, minimumGroupSize, minimumDistinctValues)

    /**
     *  The observations in sorted order.
     */
    val sortedData: DoubleArray
        get() = mySortedData.copyOf()

    private val myHistogram: Histogram by lazy {
        Histogram.create(mySortedData, name = "Mixture Modeler Default Histogram")
    }

    /**
     *  A histogram of the sample.
     *
     *  A property rather than a method, following `PDFModeler`, which exposes its characterisation
     *  the same way and for the same reason: these are facts about the data rather than
     *  computations the caller commissions.
     */
    val histogram: HistogramIfc
        get() = myHistogram

    /**
     *  The sample statistics.
     */
    val statistics: StatisticIfc
        get() = myHistogram

    /**
     *  The largest number of components this sample can structurally support.
     *
     *  **The cheapest useful thing here.** The certificate already computes it, so an analyst
     *  hypothesising six components on a sample that supports three gets a complete answer before
     *  any fitting happens, rather than a refusal part-way through a search.
     */
    val maximumFeasibleComponents: Int
        get() = certificate.maximumFeasibleGroups

    /**
     *  How many modes the data appears to have, and whether that appearance survives smoothing.
     *
     *  A method rather than a property because it bootstraps and the rest do not. `PDFModeler`
     *  draws the same line: what is cheap is a property, what costs resamples is called.
     *
     *  @param numBootstrapSamples how many resamples the test draws
     *  @param streamNumber the random number stream, so the result is reproducible
     *  @param streamProvider the provider of that stream
     */
    fun assessModality(
        numBootstrapSamples: Int = ModalityAnalyzer.defaultNumBootstrapSamples,
        streamNumber: Int = 0,
        streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider
    ): ModalityAssessment =
        ModalityAnalyzer(
            mySortedData, ModalityAnalyzer.defaultNumBins, streamNumber, streamProvider
        ).assess(numBootstrapSamples)

    /**
     *  Everything worth knowing about the sample before fitting anything to it.
     *
     *  Gathers the cheap structural facts with the modality assessment, so that an analyst meets
     *  the ceiling on the component count and the fragility of the humps they counted *before*
     *  they are shown a fit that appears to settle both.
     *
     *  @param numBootstrapSamples how many resamples the modality test draws
     *  @param streamNumber the random number stream, so the result is reproducible
     *  @param streamProvider the provider of that stream
     */
    fun describe(
        numBootstrapSamples: Int = ModalityAnalyzer.defaultNumBootstrapSamples,
        streamNumber: Int = 0,
        streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider
    ): MixtureDataDescription = MixtureDataDescription(
        numObservations = certificate.numObservations,
        numDistinctValues = certificate.numDistinctValues,
        statistics = statistics,
        maximumFeasibleComponents = maximumFeasibleComponents,
        modality = assessModality(numBootstrapSamples, streamNumber, streamProvider)
    )

    /**
     *  How far the reported density moves when the cuts between the components are nudged.
     *
     *  Every component is estimated from its own group, so every reported component depends on
     *  where two cuts fell. This moves each interior cut a little way along the axis the data lives
     *  on, refits the components from the groups that result, and reports how far the density
     *  travelled. A fit whose cuts sit in real gaps does not move at all, because nothing changes
     *  group; a fit whose cuts were placed arbitrarily through a single blob moves with them.
     *
     *  **The displacement is in data units and the refits are not refined.** Both matter. Moving a
     *  cut by a fixed number of observations, or widening each component's estimation window,
     *  always reassigns data across the boundary, and disturbs a genuinely well-separated fit the
     *  most — the observations that cross are then the most alien available. That inverts the
     *  reading, which is why this does not use `WindowedComponentFitter` as the plan's Phase E
     *  first proposed. And re-refining a displaced partition would walk the cuts straight back to
     *  where they came from, measuring nothing.
     *
     *  **On the modeler rather than on the results, and that is a deviation from the plan.** The
     *  plan put this on `MixtureModelingResults`, which carries the data and the criterion but not
     *  the fitter, the generator or the selector. An extension there would have to refit with
     *  defaults, so for anyone who fitted with anything else the measured movement would be the sum
     *  of two changes — the displaced cuts, and a different estimator — reported as if it were the
     *  first. Here the modeler's own fitter and cache do the refitting, so the cuts are the only
     *  thing that differs and the number means what it says.
     *
     *  **Nothing fitted here is returned.** Each alternative is measured against the baseline and
     *  dropped; the answer is a distance, not another density to choose from.
     *
     *  @param baseline the results whose density is being tested. Must have been fitted to this
     *  modeler's data.
     *  @param displacements how far to move each cut, as fractions of the span of the two groups it
     *  separates. Each is applied in both directions.
     *  @param selector the family selector to use for the refits, which should be the one that
     *  produced the baseline
     *  @param gapShare the share of the sample that may change group and still count as none
     *  @param gapMovement the movement permitted alongside that share
     *  @param followsShare the share above which the density is taken to follow the cuts
     *  @return the measurement, or null when the baseline fitted nothing, or fitted a single
     *  component and so has no cut to move
     *  @throws IllegalArgumentException when the baseline was fitted to different data, or when a
     *  displacement is not a positive finite fraction
     */
    fun partitionStability(
        baseline: MixtureModelingResults,
        displacements: List<Double> = PartitionStability.defaultDisplacements,
        selector: FamilySelectorIfc = defaultSelector(),
        gapShare: Double = PartitionStability.defaultGapShare,
        gapMovement: Double = PartitionStability.defaultGapMovement,
        followsShare: Double = PartitionStability.defaultFollowsShare
    ): PartitionStability? {
        require(displacements.isNotEmpty()) { "At least one cut displacement must be given" }
        require(displacements.all { it > 0.0 && it.isFinite() }) {
            "Every cut displacement must be a positive finite fraction"
        }
        require(baseline.observations.contentEquals(mySortedData)) {
            "The baseline results were fitted to different data than this modeler holds"
        }
        val best = baseline.best ?: return null
        val k = best.candidate.numComponents
        if (k < 2) return null
        // The partition that produced the reported fit, rather than one regenerated here, so that
        // the displaced partitions differ from it only by the displacement.
        val partition = baseline.refinementsByGroupCount[k]?.partition ?: return null
        if (partition.numGroups != k) return null
        val signed = displacements.flatMap { listOf(-it, it) }
        val movements = mutableListOf<Double?>()
        val reassigned = mutableListOf<Int?>()
        for (fraction in signed) {
            val displaced = displace(partition, fraction)
            if (displaced == null) {
                movements.add(null)
                reassigned.add(null)
                continue
            }
            reassigned.add(numReassigned(partition, displaced))
            val fits = myCache.fitAll(mySortedData, displaced)
            if (fits.any { !it.hasCandidates }) {
                movements.add(null)
                continue
            }
            val alternative = selector.select(mySortedData, displaced, fits, criterion).candidate
            if (alternative == null) {
                movements.add(null)
                continue
            }
            movements.add(
                DensityQuadrature.hellinger(
                    best.candidate.weights,
                    best.candidate.distributions,
                    alternative.weights,
                    alternative.distributions
                )
            )
            // `alternative` goes out of scope here, which is the whole discipline: the refits exist
            // to be measured and must not become results the analyst is invited to prefer.
        }
        return PartitionStability(
            k, mySortedData.size, signed, movements, reassigned,
            gapShare, gapMovement, followsShare
        )
    }

    /**
     *  Tests a component count the analyst proposed against one more than itself.
     *
     *  **The instrument the workflow otherwise lacks.** `fit(numComponents)` fits a hypothesised
     *  count and `countAdequacy` says whether a sample of this size could settle one, but nothing
     *  says whether *these* data contradict the count proposed. This does, by building the
     *  reference distribution of a quantity the library already computes: twice the gain in
     *  log-likelihood from an extra component, reported per count by `componentCountEvidence` as
     *  its marginal gain.
     *
     *  The null is drawn by sampling from the fitted mixture at the hypothesised count and running
     *  the same fitting procedure on each sample. See `ComponentCountTest` for why that, rather
     *  than a chi-square, is the only legitimate reference here, and for why the result must not be
     *  called a likelihood-ratio test.
     *
     *  **Never called automatically.** Each replicate is a refit, so a test at the default
     *  replicate count costs roughly two hundred times a fit. It is a question the analyst asks,
     *  not a stage this class imposes.
     *
     *  Nothing fitted here is returned. The replicate fits exist to be measured and are dropped.
     *
     *  @param hypothesisedNumComponents the count under test
     *  @param numReplicates how many samples to draw from the fitted hypothesised model
     *  @param level the level at which the verdict is read
     *  @param fitter a factory for the component fitter, fresh per replicate because the fitter is
     *  wrapped in a cache keyed by group range, and a cache carried across replicates would answer
     *  with another replicate's fits
     *  @param refiner a factory for the refiner, fresh per replicate for the same reason
     *  @param selector a factory for the family selector
     *  @param streamNum the stream to draw replicates with
     *  @param streamProvider the stream provider
     *  @return the test, or null when the hypothesised count or the count above it cannot be fitted
     *  to this sample at all, in which case the question does not arise
     */
    fun testComponentCount(
        hypothesisedNumComponents: Int,
        numReplicates: Int = ComponentCountTest.defaultNumReplicates,
        level: Double = ComponentCountTest.defaultLevel,
        fitter: () -> ComponentFitterIfc = { PDFComponentFitter() },
        refiner: () -> PartitionRefinerIfc = defaultRefiner,
        selector: () -> FamilySelectorIfc = defaultSelector,
        streamNum: Int = 0,
        streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider
    ): ComponentCountTest? {
        require(hypothesisedNumComponents >= 1) { "The hypothesised count must be at least 1" }
        require(numReplicates >= 1) { "There must be at least one replicate" }
        val k0 = hypothesisedNumComponents
        val k1 = k0 + 1
        if (certificate.infeasibilityReason(k0) != null) return null
        if (certificate.infeasibilityReason(k1) != null) return null

        val observed = improvementStatistic(mySortedData, k0, fitter, refiner, selector)

        // The mixture the null is drawn from. Fitted at k0 alone: the question is what an extra
        // component buys on data that genuinely has k0, so the null's truth must be a k0 model.
        val fittedAtK0 = MixtureModeler(mySortedData, fitter(), criterion)
            .fitSpecified(setOf(k0), refiner = refiner(), selector = selector())
            .bestByNumComponents()[k0]
            ?: return ComponentCountTest(
                k0, k1, mySortedData.size, observed, DoubleArray(0), numReplicates, level
            )
        val rv = fittedAtK0.distribution.randomVariable(streamNum, streamProvider)

        val nulls = mutableListOf<Double>()
        for (b in 1..numReplicates) {
            val sample = rv.sample(mySortedData.size)
            val statistic = improvementStatistic(sample, k0, fitter, refiner, selector)
            // A replicate that produced nothing is counted by its absence rather than dropped
            // silently: the usable share is what decides whether the null can be trusted.
            if (statistic != null) nulls.add(statistic)
        }
        return ComponentCountTest(
            k0, k1, mySortedData.size, observed, nulls.toDoubleArray(), numReplicates, level
        )
    }

    /**
     *  The component counts this sample does not rule out.
     *
     *  Runs `testComponentCount` at each count considered and collects the ones that survived. See
     *  `PlausibleComponentCounts` for why counts that could not be tested stay out of the set
     *  rather than being counted as survivors.
     *
     *  **The cost is the whole range's.** One test is already about two hundred refits, and this is
     *  one per count, so a five-count range is roughly a thousand. Narrow the range when only a
     *  particular reading of the data is in question.
     *
     *  Streams are taken one per count so the tests are independent. A `streamNum` of zero, the
     *  convention for "next available", gives each count the next stream from the provider, which
     *  is reproducible for a given provider and call order; any other value is used as the first of
     *  a consecutive block, one per count considered.
     *
     *  @param numComponentsRange the counts to test
     *  @param numReplicates how many samples to draw per count
     *  @param level the level at which each verdict is read
     *  @param fitter a factory for the component fitter
     *  @param refiner a factory for the refiner
     *  @param selector a factory for the family selector
     *  @param streamNum the first stream to draw with, or zero for next-available
     *  @param streamProvider the stream provider
     */
    fun plausibleComponentCounts(
        numComponentsRange: Iterable<Int> = defaultNumComponentsRange,
        numReplicates: Int = ComponentCountTest.defaultNumReplicates,
        level: Double = ComponentCountTest.defaultLevel,
        fitter: () -> ComponentFitterIfc = { PDFComponentFitter() },
        refiner: () -> PartitionRefinerIfc = defaultRefiner,
        selector: () -> FamilySelectorIfc = defaultSelector,
        streamNum: Int = 0,
        streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider
    ): PlausibleComponentCounts {
        val considered = numComponentsRange.distinct().sorted()
        require(considered.isNotEmpty()) { "At least one count must be considered" }
        require(considered.all { it >= 1 }) { "Every count must be at least 1" }
        val tests = LinkedHashMap<Int, ComponentCountTest>()
        for ((index, k) in considered.withIndex()) {
            val stream = if (streamNum == 0) 0 else streamNum + index
            val test = testComponentCount(
                hypothesisedNumComponents = k,
                numReplicates = numReplicates,
                level = level,
                fitter = fitter,
                refiner = refiner,
                selector = selector,
                streamNum = stream,
                streamProvider = streamProvider
            )
            if (test != null) tests[k] = test
        }
        return PlausibleComponentCounts(considered, tests, level)
    }

    /**
     *  Whether the fitted density reproduces the sample it came from.
     *
     *  **The question an input model is actually judged on.** `countAdequacy` asks whether the
     *  sample can settle how many components there are; this asks whether the density is a fair
     *  description of the data, which is what matters when the fit is going to drive a simulation.
     *  See `FitAdequacy` for why the goodness-of-fit p-value is bootstrapped rather than taken at
     *  face value, and why a two-sample comparison against the fitting data is not offered.
     *
     *  **Opt-in, like every other resampling step here.** Each replicate is a refit.
     *
     *  @param baseline the results whose density is being tested; must have been fitted to this
     *  modeler's data
     *  @param numReplicates how many samples to draw from the fitted density
     *  @param level the level at which the verdict is read
     *  @param fitter a factory for the component fitter, fresh per replicate because the cache is
     *  keyed by group range and one carried across replicates would answer with another's fits
     *  @param refiner a factory for the refiner, fresh per replicate for the same reason
     *  @param selector a factory for the family selector
     *  @param streamNum the stream to draw replicates with
     *  @param streamProvider the stream provider
     *  @return the assessment, or null when nothing was fitted
     */
    fun assessFitAdequacy(
        baseline: MixtureModelingResults,
        numReplicates: Int = FitAdequacy.defaultNumReplicates,
        level: Double = FitAdequacy.defaultLevel,
        fitter: () -> ComponentFitterIfc = { PDFComponentFitter() },
        refiner: () -> PartitionRefinerIfc = defaultRefiner,
        selector: () -> FamilySelectorIfc = defaultSelector,
        streamNum: Int = 0,
        streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider
    ): FitAdequacy? {
        require(numReplicates >= 1) { "There must be at least one replicate" }
        require(baseline.observations.contentEquals(mySortedData)) {
            "The baseline results were fitted to different data than this modeler holds"
        }
        val best = baseline.best ?: return null
        val k = best.candidate.numComponents
        val fitted = best.distribution

        val observed = Statistic.ksTestStatistic(mySortedData, fitted)
        val rv = fitted.randomVariable(streamNum, streamProvider)

        // The null: draw from the fit, refit, and measure the refit against its own draw. Refitting
        // is what makes this comparable -- the observed distance is between a sample and a density
        // fitted to that same sample, so the reference must carry the same advantage.
        // The counts the baseline actually fitted. A replicate must repeat the *whole* procedure,
        // the search over the count included: the observed distance enjoys the advantage of having
        // selected the count that fits best, so a null whose replicates are handed that count
        // rather than choosing it is not measuring the same quantity.
        val searchedCounts = baseline.bestByNumComponents().keys.sorted()
        val nulls = mutableListOf<Double>()
        for (b in 1..numReplicates) {
            val sample = rv.sample(mySortedData.size)
            val refit = MixtureModeler(sample, fitter(), criterion)
            val feasible = searchedCounts.filter { refit.certificate.infeasibilityReason(it) == null }
            if (feasible.isEmpty()) continue
            val best = refit
                .fit(feasible, refiner = refiner(), selector = selector())
                .best ?: continue
            val d = Statistic.ksTestStatistic(sample.sortedArray(), best.distribution)
            if (d.isFinite()) nulls.add(d)
        }

        return FitAdequacy(
            numObservations = mySortedData.size,
            observedStatistic = observed,
            nullStatistics = nulls.toDoubleArray(),
            numReplicates = numReplicates,
            functionals = functionalAgreement(fitted),
            level = level
        )
    }

    /**
     *  The sample's summaries beside the fitted density's own, taken from the distribution rather
     *  than from a sample of it so that they carry no simulation error.
     */
    private fun functionalAgreement(fitted: ContinuousDistributionIfc): List<FunctionalAgreement> {
        val statistics = Statistic(mySortedData)
        val dataMean = statistics.average
        val dataSd = statistics.standardDeviation
        val fitMean = fitted.mean()
        val fitSd = sqrt(fitted.variance())
        fun quantile(p: Double): Double {
            val index = (p * (mySortedData.size - 1)).toInt().coerceIn(0, mySortedData.size - 1)
            return mySortedData[index]
        }
        // Guarded, because a fitted mixture can report a non-finite mean or variance -- a fitted
        // component on a near-degenerate group is enough -- and an unguarded NaN propagates
        // silently into the summary table as a missing or absurd relative error.
        val out = mutableListOf<FunctionalAgreement>()
        if (fitMean.isFinite()) out.add(FunctionalAgreement("mean", dataMean, fitMean))
        if (fitSd.isFinite()) out.add(FunctionalAgreement("std. dev.", dataSd, fitSd))
        // The coefficient of variation earns its place: a queueing model's waiting time responds to
        // it far more strongly than to the shape of the density that produced it.
        if (dataMean != 0.0 && fitMean != 0.0 && fitMean.isFinite() && fitSd.isFinite()) {
            out.add(FunctionalAgreement("coeff. of var.", dataSd / dataMean, fitSd / fitMean))
        }
        for ((name, p) in listOf("median" to 0.5, "90th pct" to 0.9, "99th pct" to 0.99)) {
            val f = try {
                fitted.invCDF(p)
            } catch (e: IllegalArgumentException) {
                // A quantile the fitted mixture cannot invert; the isFinite guard below drops it
                // from the agreement table. Narrow so a fault in the inversion is not read as a
                // missing row.
                Double.NaN
            }
            if (f.isFinite()) out.add(FunctionalAgreement(name, quantile(p), f))
        }
        return out
    }

    /**
     *  Twice the gain in log-likelihood from fitting one more component than the given count, or
     *  null when either count cannot be fitted to this sample or either fit has an unusable
     *  log-likelihood.
     */
    private fun improvementStatistic(
        sample: DoubleArray,
        numComponents: Int,
        fitter: () -> ComponentFitterIfc,
        refiner: () -> PartitionRefinerIfc,
        selector: () -> FamilySelectorIfc
    ): Double? {
        val counts = setOf(numComponents, numComponents + 1)
        val modeler = MixtureModeler(sample, fitter(), criterion)
        for (k in counts) {
            if (modeler.certificate.infeasibilityReason(k) != null) return null
        }
        val byCount = modeler
            .fitSpecified(counts, refiner = refiner(), selector = selector())
            .bestByNumComponents()
        val lower = byCount[numComponents] ?: return null
        val upper = byCount[numComponents + 1] ?: return null
        val lowerLogLikelihood = lower.candidate.logLikelihood(sample)
        val upperLogLikelihood = upper.candidate.logLikelihood(sample)
        if (!lowerLogLikelihood.isUsable || !upperLogLikelihood.isUsable) return null
        return 2.0 * (upperLogLikelihood.value - lowerLogLikelihood.value)
    }

    /**
     *  The partition with every interior cut moved the supplied signed fraction of the span of the
     *  two groups it separates, or null when the result is not an admissible partition.
     *
     *  Null rather than a clamped position: a displacement that cannot be applied is an unanswered
     *  question, and nudging it back to the nearest legal cut would report a movement smaller than
     *  the one that was asked for.
     */
    private fun displace(partition: DataPartition, fraction: Double): DataPartition? {
        val cuts = partition.cutPositions
        val moved = IntArray(cuts.size)
        for (c in cuts.indices) {
            val low = partition.startIndex(c)
            val high = partition.endIndex(c + 1)
            val span = mySortedData[high - 1] - mySortedData[low]
            if (span <= 0.0 || !span.isFinite()) return null
            val at = cuts[c]
            val value = 0.5 * (mySortedData[at - 1] + mySortedData[at])
            moved[c] = firstIndexAbove(value + fraction * span)
        }
        for (i in moved.indices) {
            if (moved[i] <= 0 || moved[i] >= mySortedData.size) return null
            if (i > 0 && moved[i] <= moved[i - 1]) return null
        }
        val displaced = DataPartition(mySortedData.size, moved)
        for (g in 0 until displaced.numGroups) {
            if (!certificate.isValidGroup(displaced.startIndex(g), displaced.endIndex(g))) return null
        }
        return displaced
    }

    /**
     *  The index of the first observation strictly greater than the supplied value.
     */
    private fun firstIndexAbove(value: Double): Int {
        var low = 0
        var high = mySortedData.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (mySortedData[mid] <= value) low = mid + 1 else high = mid
        }
        return low
    }

    /**
     *  How many observations sit in a different group under the second partition than the first.
     */
    private fun numReassigned(from: DataPartition, to: DataPartition): Int {
        var count = 0
        for (c in from.cutPositions.indices) {
            count += kotlin.math.abs(from.cutPositions[c] - to.cutPositions[c])
        }
        return count
    }

    /**
     *  Fits and ranks mixtures over the requested numbers of components.
     *
     *  The defaults reproduce the configuration the designed experiment measured as best: a Jenks
     *  initial partition, refinement by local search over the cut positions, and separable family
     *  selection. A user who supplies nothing therefore gets the best-performing configuration
     *  rather than a baseline, and the alternatives remain available for comparison.
     *
     *  @param numComponentsRange the numbers of components to consider
     *  @param generator the partition generator supplying the initial partition
     *  @param refiner improves the generated partition. Supply `NoRefinement` to fit the
     *  partition exactly as generated, which is the baseline the experiment measures refinement
     *  against.
     *  @param selector chooses one family per group. The default is separable: it optimizes the
     *  classification objective, which decomposes across groups, and so assembles one mixture per
     *  candidate count instead of the product of the per-group candidate counts.
     */
    /**
     *  Fits a mixture with the number of components the caller has chosen.
     *
     *  For the analyst who has looked at a histogram and decided how many groups there are. That
     *  is a legitimate way to arrive at a component count and, on this project's own evidence, not
     *  obviously worse than the automatic one: the best criterion recovers the true count on well
     *  under half of attempts. So it is offered as its own entry point rather than left to be
     *  expressed as a one-element range.
     *
     *  **One component is always fitted alongside the requested count**, at the cost of the
     *  cheapest candidate there is, so that the comparison against a single distribution — which
     *  is the question the analyst still has, and arguably has more sharply having committed to a
     *  structure — remains available. The requested count is what gets recommended; the baseline
     *  cannot displace it.
     *
     *  @param numComponents the number of components to fit. Must be feasible for this sample;
     *  the reason is reported if it is not, rather than an empty result being returned.
     *  @param generator the partition generator supplying the initial partition
     *  @param refiner improves the generated partition
     *  @param selector chooses one family per group
     *  @throws IllegalArgumentException when the count cannot be fitted to this sample, naming the
     *  constraint that refuses it
     */
    fun fit(
        numComponents: Int,
        generator: PartitionGeneratorIfc = JenksPartitionGenerator(mySortedData, maxRequested(listOf(numComponents))),
        refiner: PartitionRefinerIfc = defaultRefiner(),
        selector: FamilySelectorIfc = defaultSelector()
    ): MixtureModelingResults = fitSpecified(setOf(numComponents), generator, refiner, selector)

    /**
     *  Fits the numbers of components the caller is choosing between.
     *
     *  The generalization of `fit(numComponents)` to an analyst weighing more than one reading of
     *  a histogram — two plausible groupings, say — who wants the criteria's opinion between them
     *  without pretending a range was searched. The recommendation is the best of the specified
     *  counts; one component is fitted alongside them for the comparison, as above.
     *
     *  Deliberately a separate name rather than another `fit` overload. A `Set<Int>` is an
     *  `Iterable<Int>`, so an overload taking one would silently capture existing calls that pass
     *  a set to the range form and change what they mean.
     *
     *  @param numComponents the counts to fit. Every one must be feasible for this sample.
     *  @param generator the partition generator supplying the initial partition
     *  @param refiner improves the generated partition
     *  @param selector chooses one family per group
     *  @throws IllegalArgumentException when the set is empty or holds a count that cannot be
     *  fitted to this sample, naming the constraint that refuses it
     */
    fun fitSpecified(
        numComponents: Set<Int>,
        generator: PartitionGeneratorIfc = JenksPartitionGenerator(mySortedData, maxRequested(numComponents)),
        refiner: PartitionRefinerIfc = defaultRefiner(),
        selector: FamilySelectorIfc = defaultSelector()
    ): MixtureModelingResults {
        require(numComponents.isNotEmpty()) { "At least one number of components must be given" }
        require(numComponents.all { it >= 1 }) { "Every number of components must be at least one" }
        // Checked here rather than left to accumulate in rejectedGroupCounts: the caller named
        // these counts, so refusing one is an answer to their question and not a detail of a
        // search. Every count is checked before any fitting, so a bad set fails before the work.
        for (k in numComponents.sorted()) {
            val reason = certificate.infeasibilityReason(k)
            require(reason == null) {
                "$k components cannot be fitted to this sample: $reason"
            }
        }
        val results = fit(numComponents + 1, generator, refiner, selector)
        return MixtureModelingResults(
            results.results, results.certificate, results.groupFitsByGroupCount,
            results.rejectedGroupCounts, results.cacheHitRate, results.truncatedGroupCounts,
            results.refinementsByGroupCount, mySortedData, criterion,
            specifiedComponentCounts = numComponents
        )
    }

    fun fit(
        numComponentsRange: Iterable<Int> = defaultNumComponentsRange,
        generator: PartitionGeneratorIfc = JenksPartitionGenerator(mySortedData, maxRequested(numComponentsRange)),
        refiner: PartitionRefinerIfc = defaultRefiner(),
        selector: FamilySelectorIfc = defaultSelector()
    ): MixtureModelingResults {
        val ranked = mutableListOf<RankedMixture>()
        val fitsByK = mutableMapOf<Int, List<GroupFitResult>>()
        val rejected = mutableMapOf<Int, String>()
        val truncated = mutableMapOf<Int, String>()
        val refinements = mutableMapOf<Int, RefinementResult>()

        for (k in numComponentsRange) {
            val reason = certificate.infeasibilityReason(k)
            if (reason != null) {
                rejected[k] = reason
                continue
            }
            val initial = generator.generate(mySortedData, k, certificate)
            if (initial == null) {
                rejected[k] = "The generator ${generator.name} produced no valid partition"
                continue
            }
            val refinement = refiner.refine(mySortedData, initial, certificate, myCache)
            refinements[k] = refinement
            val partition = refinement.partition
            val fits = myCache.fitAll(mySortedData, partition)
            fitsByK[k] = fits
            val unfittable = fits.withIndex().filter { !it.value.hasCandidates }
            if (unfittable.isNotEmpty()) {
                // A mixture needs a component per group, so one unfittable group ends this k.
                rejected[k] = "No family fitted group(s) " +
                        unfittable.joinToString { it.index.toString() }
                continue
            }
            val selection = selector.select(mySortedData, partition, fits, criterion)
            if (selection.wasCapped) {
                // The search was truncated, not refused. The reported best for this count may not
                // be its best, which is a different fact from the count being unusable.
                truncated[k] = "The ${selector.name} search was truncated after " +
                        "${selection.numMixturesEvaluated} of ${selection.numCandidatesConsidered} " +
                        "candidate combinations"
            }
            val candidate = selection.candidate
            if (candidate == null) {
                rejected[k] = "The ${selector.name} selector assembled no mixture"
                continue
            }
            ranked.add(RankedMixture(candidate, criterion.evaluate(candidate, mySortedData)))
        }
        // Rank comparable values first, in the criterion's direction; incomparable values follow
        // so that they can be reported but never win.
        val sorted = ranked.sortedWith(
            compareByDescending<RankedMixture> { it.criterionValue.isComparable }
                .thenBy { if (criterion.smallerIsBetter) it.criterionValue.value else -it.criterionValue.value }
        )
        return MixtureModelingResults(
            sorted, certificate, fitsByK, rejected, myCache.hitRate, truncated, refinements,
            mySortedData, criterion
        )
    }

    private fun maxRequested(range: Iterable<Int>): Int {
        val m = range.maxOrNull() ?: 1
        return maxOf(1, minOf(m, mySortedData.size))
    }

    override fun toString(): String {
        return "MixtureModeler(n=${mySortedData.size}, criterion=${criterion.name})"
    }

    companion object {

        /**
         *  The numbers of components considered when none are specified.
         */
        var defaultNumComponentsRange: IntRange = 1..5
            set(value) {
                require(value.first >= 1) { "The range must start at >= 1" }
                field = value
            }

        /**
         *  The criteria reported alongside the ranking criterion when none are named.
         *
         *  A factory, because the extended criterion is constructed against a catalog size and
         *  because a criterion is free to carry state. All five are penalised likelihoods
         *  differing only in penalty, so they are reported side by side rather than combined:
         *  averaging them would manufacture an agreement the evidence does not support.
         *
         *  **Ordered by penalty**, lightest first, because that is what makes their disagreement
         *  readable. AIC charges two per parameter and over-selects; BIC charges the log of the
         *  sample size and under-selects; Hannan-Quinn sits between them and was measured the most
         *  accurate of the five on this method's component count. A reader who sees AIC choose
         *  five and BIC choose two is looking at the two ends of that scale rather than at a
         *  contradiction.
         *
         *  @param catalogSize the number of families the fitter may choose from, which the
         *  extended criterion charges for
         */
        var defaultReportedCriteria: (catalogSize: Int) -> List<MixtureCriterionIfc> = { size ->
            listOf(
                MixtureAICCriterion(),
                MixtureHannanQuinnCriterion(),
                MixtureBICCriterion(),
                MixtureICLBICCriterion(),
                MixtureEBICCriterion(catalogSize = size)
            )
        }

        /**
         *  The refiner used when none is supplied.
         *
         *  A factory rather than a shared instance: refiners carry per-run state such as guard
         *  rejection counts, so handing the same object to two concurrent fits would let one run's
         *  diagnostics contaminate the other's.
         *
         *  The default is local search over the cut positions, which the designed experiment
         *  measured as the best of the available refinements. Pass `NoRefinement()` for the
         *  unrefined baseline.
         */
        var defaultRefiner: () -> PartitionRefinerIfc = { BreakShiftRefiner() }

        /**
         *  The family selector used when none is supplied.
         *
         *  Separable by default. The classification objective decomposes across groups, so one
         *  mixture is assembled per candidate count rather than the product of the per-group
         *  candidate counts, which is exponential in the number of components.
         */
        var defaultSelector: () -> FamilySelectorIfc = { SeparableCMLSelector() }

        /**
         *  The most assembled mixtures evaluated for a single number of components when no
         *  bound is specified. Retained for selectors that enumerate combinations; the separable
         *  selector does not, and ignores it.
         */
        var defaultMaxCandidatesPerGroupCount: Int = 5000
            set(value) {
                require(value > 0) { "The default maximum candidates must be > 0" }
                field = value
            }
    }
}
