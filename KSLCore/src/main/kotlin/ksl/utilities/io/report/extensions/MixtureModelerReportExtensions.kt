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
package ksl.utilities.io.report.extensions

import ksl.utilities.distributions.fitting.mixture.AdequacyVerdict
import ksl.utilities.distributions.fitting.mixture.AdmissibleCutPlot
import ksl.utilities.distributions.fitting.mixture.CutDependence
import ksl.utilities.distributions.fitting.mixture.FitAdequacy
import ksl.utilities.distributions.fitting.mixture.FitVerdict
import ksl.utilities.distributions.fitting.mixture.FittedAgainstDataPlot
import ksl.utilities.distributions.fitting.mixture.PartitionCutPlot
import ksl.utilities.distributions.fitting.mixture.SampleDotPlot
import ksl.utilities.distributions.fitting.mixture.EntryGuidance
import ksl.utilities.distributions.fitting.mixture.GainVersusPenaltyPlot
import ksl.utilities.distributions.fitting.mixture.MixtureBootstrapResults
import ksl.utilities.distributions.fitting.mixture.MixtureDataDescription
import ksl.utilities.distributions.fitting.mixture.MixtureModelingResults
import ksl.utilities.distributions.fitting.mixture.PartitionStability
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.ReportBuilder
import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.renderer.RenderContext
import ksl.utilities.io.report.showInBrowser
import ksl.utilities.io.report.toHtml
import ksl.utilities.io.report.toMarkdown
import ksl.utilities.statistic.Statistic
import java.io.File

/**
 *  Report fragments for mixture modeling results, matching the shape of the library's
 *  `PDFModelerReportExtensions`.
 *
 *  Each function adds one section to a `ReportBuilder`; `toReport` assembles them into a document.
 *  Nothing here renders — the AST is handed to the HTML, Markdown, LaTeX or text renderer, so one
 *  definition serves every output format.
 *
 *  The package is deliberately the library's rather than the mixture package's, so that these move
 *  upstream unchanged if the facade does.
 *
 *  **What a mixture report carries that a single-distribution report does not.** The admissibility
 *  certificate, because which component counts can be fitted at all is decided before any fitting.
 *  The whole criterion profile rather than its argument minimum, because a minimum at the end of a
 *  searched range is not a selection. The share of bounded families, because it is the signature of
 *  a fit that has carved the sample up. And held-out *coverage* rather than a held-out likelihood,
 *  which is undefined exactly when bounded components were chosen and therefore missing in a way
 *  that is not at random.
 */

// -------------------------------------------------------------------------- content fragments

/**
 *  What was known about the sample before anything was fitted to it.
 *
 *  **First, when it is available at all.** An analyst is best served by meeting the ceiling on the
 *  component count and the fragility of the humps they counted *before* being shown a fit that
 *  appears to settle both. Both facts are cheap and neither is available from the fit.
 *
 *  The entry gate warns and proceeds; it never refuses. Refusing would be wrong in exactly the case
 *  the method is for — a mixture on unimodal data with a mechanism the analyst knows about and the
 *  tool does not.
 *
 *  @param description the pre-fit characterisation, from `MixtureModeler.describe`
 *  @param hasMechanism whether the analyst has a priori reason to believe in a mixing mechanism
 *  @param title the section title
 *  @param level the significance level for the modality test
 */
fun ReportBuilder.mixtureBeforeFitting(
    description: MixtureDataDescription,
    hasMechanism: Boolean = false,
    title: String = "Before fitting anything",
    level: Double = 0.05
) {
    section(title) {
        dataTable(
            listOf("Property", "Value"),
            listOf(
                listOf("Observations", "${description.numObservations}"),
                listOf("Distinct values", "${description.numDistinctValues}"),
                listOf(
                    "Most components this sample can support",
                    "${description.maximumFeasibleComponents}"
                ),
                listOf("Apparent modes", "${description.modality.apparentModes}"),
                listOf(
                    "Silverman's test of a single mode",
                    "p = %.3f".format(description.modality.unimodalityTest.pValue)
                ),
                listOf(
                    "Apparent count depends on the bin count",
                    if (description.modality.apparentModesDependOnBinning) "yes" else "no"
                )
            ),
            "The structural facts, before any fitting"
        )
        paragraph(
            "Modes bound the number of components from below only: a two-component mixture can " +
                    "be unimodal, so one hump does not mean one component."
        )
        val warning = description.warningOrNull(hasMechanism, level)
        if (warning != null) paragraph(warning)
        paragraph(
            when (description.entryGuidance(hasMechanism, level)) {
                EntryGuidance.PROCEED ->
                    "A warrant for a mixture is present: visible structure, a claimed mechanism, " +
                            "or both."
                EntryGuidance.PROCEED_WITH_WEAK_IDENTIFICATION ->
                    "Worth proceeding, but the components will be hard to identify — this sample " +
                            "supports fewer components than an analyst is likely to be " +
                            "hypothesising, or the apparent structure moves with the bin count."
                EntryGuidance.PROCEED_WITH_WARNING ->
                    "Neither warrant is present. Proceed if you have a reason to; the components " +
                            "will not correspond to anything if you do not."
            }
        )
    }
}

/**
 *  Whether this sample is large enough for the component count to be answerable at all.
 *
 *  **Before any count is reported, not after it.** The component count is the least reliable thing
 *  this facade produces, and how reliable it can possibly be is governed by the sample size and by
 *  how far the fitted mixture sits from the nearest mixture with one component fewer. Where that
 *  product is small, no procedure could settle the question and the number that follows is close to
 *  a guess. A reader who meets the count first will anchor on it.
 *
 *  @param results the modeling results
 *  @param title the section title
 */
fun ReportBuilder.mixtureAdequacy(
    results: MixtureModelingResults,
    title: String = "Can this sample settle the component count?"
) {
    section(title) {
        val adequacy = results.countAdequacy()
        if (adequacy == null) {
            paragraph(
                "The question does not arise: either nothing was fitted, the recommendation has " +
                        "a single component and so has no count to settle, or the separability " +
                        "could not be computed. Absent rather than adequate."
            )
            return@section
        }
        dataTable(
            listOf("Property", "Value"),
            listOf(
                listOf("Verdict", adequacy.verdict.name),
                listOf("Components", "${adequacy.hypothesisedCount}"),
                listOf("Observations", "${adequacy.sampleSize}"),
                listOf("Separability of the fitted mixture", "%.4f".format(adequacy.separability)),
                listOf("n times separability squared", "%.2f".format(adequacy.adequacyRatio)),
                listOf("Observations for even odds", "${adequacy.sizeForEvenOdds}")
            ),
            "How difficult a question this sample is being asked"
        )
        paragraph(adequacy.explain())
        for (caveat in adequacy.caveats()) paragraph(caveat)
    }
}

/**
 *  The evidence for and against each candidate count, with the criteria side by side.
 *
 *  **The margin is labelled as the criterion's firmness, not as reliability**, because measurement
 *  refutes the reading a reader will otherwise take. Across the designed experiment the criterion
 *  is *confidently* wrong when it is wrong — the median gap to the truth is around nineteen — so
 *  the size of a margin is not evidence that the choice behind it is sound. What does carry
 *  information is whether criteria of different penalty strength agree.
 *
 *  @param results the modeling results
 *  @param title the section title
 *  @param includePlot whether to draw the gain-against-penalty figure
 */
fun ReportBuilder.mixtureCountEvidence(
    results: MixtureModelingResults,
    title: String = "Evidence about the number of components",
    includePlot: Boolean = true
) {
    section(title) {
        val evidence = results.componentCountEvidence()
        if (evidence.rows.isEmpty()) {
            paragraph("No candidate was fitted, so there is no evidence to weigh.")
            return@section
        }
        val criteria = evidence.criterionChoices.keys.toList()
        dataTable(
            listOf("k", "Log-likelihood", "Marginal gain", "Penalty increment", "Firmness") +
                    criteria,
            evidence.rows.map { row ->
                listOf(
                    "${row.numComponents}",
                    row.logLikelihood?.let { "%.3f".format(it) } ?: "—",
                    row.marginalGain?.let { "%.3f".format(it) } ?: "—",
                    row.penaltyIncrement?.let { "%.3f".format(it) } ?: "—",
                    row.margin?.let { "%.3f".format(it) } ?: "—"
                ) + criteria.map { name ->
                    row.criterionValues[name]?.let { "%.3f".format(it) } ?: "—"
                }
            },
            "What each count buys and what it costs, with every reported criterion"
        )
        paragraph(
            "Firmness is how far the criterion prefers a count over its nearest rival. It is not " +
                    "a measure of how likely that preference is to be correct, so a large " +
                    "firmness is not evidence that the count is right."
        )
        val interval = evidence.criterionInterval
        if (evidence.criteriaAgree) {
            paragraph(
                "Every reported criterion picks the same count. That agreement is the strongest " +
                        "signal available here, because the criteria differ only in how heavily " +
                        "they charge for parameters."
            )
        } else if (interval != null) {
            paragraph(
                "The criteria disagree. They span ${interval.first} to ${interval.last}, a width " +
                        "of ${evidence.criterionIntervalWidth}. Read that span as the answer " +
                        "rather than any one end of it: the lightest penalty over-selects and the " +
                        "heaviest under-selects, so the truth is usually inside."
            )
        }
        if (includePlot) {
            val gain = GainVersusPenaltyPlot(evidence)
            plot(gain, "What each additional component buys against what it costs")
            // The caveat used to be drawn on the figure so it would travel with it. The plot
            // library does not wrap a caption, so it was rendered as one long line that ran off
            // the canvas and was clipped mid-word. It is printed here instead, where it wraps.
            paragraph(gain.caveat)
        }
        // The full profile, formerly its own section. Where each criterion's minimum sits is part
        // of the same question this section asks, and a minimum at the end of the searched range
        // is not a selection at all -- which is only visible with every count in view.
        criterionProfileContent(results)
    }
}

/**
 *  Whether the reported density depends on where the cuts between components fell.
 *
 *  Every component is estimated from its own group, so every reported component depends on two cut
 *  positions — and nothing in the result shows it. This reports what happened when the cuts were
 *  moved.
 *
 *  The reading is three-valued rather than a yes or no because a yes-or-no does not survive
 *  measurement: calibrated against the designed experiment, a threshold on the density movement is
 *  barely better than chance, while the extremes are strongly informative and say different things.
 *
 *  @param stability the measurement, from `MixtureModeler.partitionStability`
 *  @param title the section title
 */
fun ReportBuilder.mixtureCutDependence(
    stability: PartitionStability,
    title: String = "Does the fit depend on where the cuts fell?",
    results: MixtureModelingResults? = null,
    variableName: String = "observed value"
) {
    section(title) {
        // The arrangement the verdict is about, drawn before the verdict is given. Cuts standing
        // in visible gaps and cuts standing inside a dense stack look nothing alike, and no
        // summary statistic conveys that difference as directly as the picture does.
        if (results != null && results.observations.isNotEmpty()) {
            val partitions = results.refinementsByGroupCount
                .mapNotNull { (k, refinement) -> refinement.partition?.let { k to it } }
                .toMap()
            if (partitions.isNotEmpty()) {
                plot(
                    PartitionCutPlot(results.observations, partitions, variableName = variableName),
                    "The cuts the method made, on the data it cut"
                )
                paragraph(
                    "Where the dots are sparse a cut's position is nearly arbitrary — moving it " +
                            "reassigns almost nothing, and the components either side are divided " +
                            "by a gap the data actually has. Where the dots are dense a cut " +
                            "divides observations that look alike, and the components either side " +
                            "are slices of one population rather than two."
                )
            }
        }
        dataTable(
            listOf("Cut displacement", "Observations reassigned", "Density moved"),
            stability.displacements.indices.map { i ->
                val sign = if (stability.displacements[i] < 0.0) "-" else "+"
                listOf(
                    "$sign%.2f of the local span".format(kotlin.math.abs(stability.displacements[i])),
                    stability.numObservationsReassigned[i]?.toString() ?: "not applicable",
                    stability.hellingerFromBaseline[i]?.let { "%.4f".format(it) } ?: "not measurable"
                )
            },
            "What moving each cut a little way along the data axis did"
        )
        paragraph(
            when (stability.cutDependence) {
                CutDependence.CUTS_LIE_IN_A_GAP ->
                    "The cuts lie in a gap: they can be moved and essentially no observation " +
                            "changes group. The components are divided by gaps the data actually " +
                            "has, rather than by where the partition happened to fall."
                CutDependence.DENSITY_FOLLOWS_THE_CUTS ->
                    "The density follows the cuts: displacing them reassigns much of the sample. " +
                            "The reported components are slices of a continuum, and their shapes " +
                            "should not be read as physical."
                CutDependence.INCONCLUSIVE ->
                    "Neither signature is present, so this diagnostic has nothing to add about " +
                            "this fit. That is the usual answer, and it is reported rather than " +
                            "dressed up as a finding."
            }
        )
        paragraph(stability.caveat())
    }
}


/**
 *  The sample the mixtures were fitted to: its statistics, and the structural facts that decide
 *  what can be fitted to it.
 *
 *  The structural half has no counterpart in the single-distribution report and comes first,
 *  because a component count that the certificate refuses is not a modeling choice at all.
 *
 *  @param results the modeling results
 *  @param title the section title
 *  @param level the confidence level used by the statistical summary
 */
fun ReportBuilder.mixtureDataSummary(
    results: MixtureModelingResults,
    title: String = "The sample",
    level: Double = 0.95,
    includePlots: Boolean = false,
    variableName: String = "observed value"
) {
    section(title) {
        val data = results.observations
        if (data.isNotEmpty()) {
            statPropertyTable(Statistic(data), "Statistics of the observations", level)
        }
        if (includePlots && data.isNotEmpty()) {
            // The sample before anything is fitted, and then the positions a cut is allowed to
            // take. Together they say what the search had to work with -- which is the honest
            // context for the largest feasible group count in the table below, a number that
            // otherwise arrives without explanation.
            plot(
                SampleDotPlot(data, variableName = variableName),
                "Every observation, before any model touches it"
            )
            // Only when there is something to see. The certificate rules out a position only when
            // it sits inside a run of equal values, so on continuous measurements nothing is ruled
            // out and every mark would be the same colour -- a figure that looks broken rather
            // than one that says "no ties".
            val ties = AdmissibleCutPlot(data, results.certificate, variableName)
            if (ties.hasRefusedPositions) {
                plot(ties, "Positions no cut may take, because equal values must stay together")
                paragraph(
                    "Observations that are equal must fall in the same group, so a position " +
                            "inside a run of them is not a position at all. This sample has such " +
                            "runs, which is what the second colour marks."
                )
            }
        }
        val certificate = results.certificate
        dataTable(
            listOf("Property", "Value"),
            listOf(
                listOf("Observations", "${certificate.numObservations}"),
                listOf("Distinct values", "${certificate.numDistinctValues}"),
                listOf("Minimum group size", "${certificate.minimumGroupSize}"),
                listOf("Minimum distinct values per group", "${certificate.minimumDistinctValues}"),
                listOf("Admissible cut positions", "${certificate.admissibleCuts.size}"),
                listOf("Largest feasible number of groups", "${certificate.maximumFeasibleGroups}")
            ),
            "Structural facts of the sample"
        )
        if (results.rejectedGroupCounts.isNotEmpty()) {
            dataTable(
                listOf("Components", "Why it was not attempted"),
                results.rejectedGroupCounts.entries.sortedBy { it.key }
                    .map { listOf("${it.key}", it.value) },
                "Component counts that could not be fitted"
            )
        }
    }
}

/**
 *  The recommended mixture, one row per component.
 *
 *  @param results the modeling results
 *  @param title the section title
 */
fun ReportBuilder.mixtureComponents(
    results: MixtureModelingResults,
    title: String = "The recommended mixture"
) {
    section(title) {
        val best = results.best
        if (best == null) {
            paragraph("No mixture could be fitted to this sample.")
            return@section
        }
        if (results.numComponentsWasSpecified) {
            val asked = results.specifiedComponentCounts!!.sorted()
            paragraph(
                "The number of components was specified rather than selected: " +
                        asked.joinToString(", ") + ". Nothing in this report is evidence that it " +
                        "is the right number."
            )
        }
        val candidate = best.candidate
        dataTable(
            listOf("j", "Family", "Weight", "Observations", "Parameters"),
            (0 until candidate.numComponents).map { j ->
                listOf(
                    "${j + 1}",
                    candidate.components[j].rvType.toString(),
                    "%.4f".format(candidate.weights[j]),
                    "${candidate.partition.sizeOf(j)}",
                    candidate.components[j].name
                )
            },
            "Components of the recommended mixture"
        )
        paragraph(
            "A family name here says which shape fitted that stretch of the sorted data best. It " +
                    "is not an identification: families that look alike over a narrow range are " +
                    "chosen between on very little, and a component is a contiguous run of the " +
                    "data rather than a group of observations that belong together."
        )
        dataTable(
            listOf("Property", "Value"),
            listOfNotNull(
                listOf("Components", "${candidate.numComponents}"),
                listOf("Free parameters", "${best.numberOfParameters}"),
                listOf(results.criterion.name, "%.3f".format(best.criterionValue.value)),
                results.boundedComponentShare?.let {
                    listOf("Share of components with bounded support", "%.3f".format(it))
                }
            ),
            "The recommendation at a glance"
        )
    }
}

/**
 *  The recommendation against the best single distribution.
 *
 *  The comparison is exact rather than approximate: a one-component mixture *is* a single
 *  distribution, drawn from the same catalog and scored by the same criterion, so no separate fit
 *  is involved. The goodness-of-fit tests are given for both because a criterion difference alone
 *  is on an arbitrary scale and says nothing about whether either fit is defensible.
 *
 *  @param results the modeling results
 *  @param title the section title
 */
fun ReportBuilder.mixtureWorthIt(
    results: MixtureModelingResults,
    title: String = "Was a mixture worth it?"
) {
    section(title) {
        val best = results.best
        val single = results.singleDistribution
        if (best == null) {
            paragraph("No mixture could be fitted, so there is nothing to compare.")
            return@section
        }
        if (single == null) {
            paragraph(
                "One component was not among those fitted, so no comparison against a single " +
                        "distribution is available."
            )
            return@section
        }
        val data = results.observations
        val rows = mutableListOf<List<String>>()
        rows.add(
            listOf(
                "Free parameters", "${single.numberOfParameters}", "${best.numberOfParameters}"
            )
        )
        rows.add(
            listOf(
                results.criterion.name,
                "%.3f".format(single.criterionValue.value),
                "%.3f".format(best.criterionValue.value)
            )
        )
        // The goodness-of-fit statistics used to be repeated here. They are the same eight numbers
        // the goodness-of-fit section prints, on the same two fits, and printing them twice made
        // the reader check whether the two tables disagreed. This section answers "was it worth
        // it", which the criterion and the parameter count answer on their own.
        dataTable(listOf("", "Single distribution", "Mixture"), rows, "Side by side")
        paragraph("Best single distribution: ${single.candidate.components[0].name}")
        val improvement = results.improvementOverSingle
        paragraph(
            when {
                improvement == null || improvement.isNaN() ->
                    "Verdict: unavailable. The two values cannot be compared."
                improvement > 0.0 ->
                    "Verdict: YES. The mixture improves ${results.criterion.name} by " +
                            "${"%.3f".format(improvement)}."
                else ->
                    "Verdict: NO. The criterion preferred a single distribution by " +
                            "${"%.3f".format(-improvement)}."
            }
        )
        if (data.isNotEmpty()) {
            paragraph(
                "This says the mixture is the better of the two, not that it is adequate. " +
                        "Whether the fitted density actually describes the sample is a separate " +
                        "question with its own section."
            )
        }
    }
}

/**
 *  Whether the fitted density is a fair description of the sample it came from.
 *
 *  **The question the rest of this report does not ask.** `mixtureAdequacy` asks whether the sample
 *  can settle how many components there are; the criterion sections ask which count wins. All of
 *  them are about a structure this method recovers poorly. This asks whether the *density* is
 *  usable, which is what an input model is for, and it is the one section reporting on the output
 *  the method is built to get right.
 *
 *  **The summaries come before the verdict deliberately.** A test returns reject or do not reject.
 *  It does not say how far off the fit is on a quantity a simulation is sensitive to, and that is
 *  usually the question an input modeller actually has: a queue driven by these values responds to
 *  the mean and the coefficient of variation far more than to the shape between them, so an
 *  agreement of a percent on those may settle the matter whatever the test says.
 *
 *  Takes the assessment rather than computing it, for the reason `mixtureReliability` does: it
 *  refits the whole model on every replicate, and expensive work started by a call that looks like
 *  formatting is a surprise. The caller decides to pay for it.
 *
 *  @param adequacy the assessment, from `MixtureModeler.assessFitAdequacy`
 *  @param title the section title
 */
fun ReportBuilder.mixtureFitAdequacy(
    adequacy: FitAdequacy,
    title: String = "Is the fitted density adequate?"
) {
    section(title) {
        if (adequacy.functionals.isNotEmpty()) {
            dataTable(
                listOf("Summary", "From the data", "From the fit", "Relative error"),
                adequacy.functionals.map { f ->
                    listOf(
                        f.name,
                        "%.4f".format(f.fromData),
                        "%.4f".format(f.fromFit),
                        f.relativeError?.let { "%+.2f%%".format(100.0 * it) } ?: "—"
                    )
                },
                "The data's own summaries beside the same summaries of the fit"
            )
            paragraph(
                "The fitted values are computed from the distribution rather than from a sample " +
                        "of it, so they carry no simulation error of their own."
            )
            adequacy.largestRelativeError?.let {
                paragraph(
                    "The largest disagreement among these is " +
                            "${"%.2f".format(100.0 * kotlin.math.abs(it))}%. This is the number to " +
                            "put beside a tolerance: whether that is acceptable depends on what " +
                            "the model is for, which the fit cannot know."
                )
            }
        }
        dataTable(
            listOf("Property", "Value"),
            listOf(
                listOf("Observations", "${adequacy.numObservations}"),
                listOf(
                    "Distance to the data",
                    adequacy.observedStatistic?.let { "%.4f".format(it) } ?: "not measurable"
                ),
                listOf("p-value", adequacy.pValue?.let { "%.4f".format(it) } ?: "—"),
                listOf(
                    "Usable replicates",
                    "${adequacy.numUsable} of ${adequacy.numReplicates}"
                ),
                listOf("Verdict", adequacy.verdict.name)
            ),
            "The fit against a null that was put to the same work"
        )
        paragraph(adequacy.explain())
        paragraph(
            "The density was fitted to the data it is being tested against, which would make an " +
                    "ordinary goodness-of-fit p-value optimistic. This one is not: each replicate " +
                    "draws a fresh sample from the fitted density and runs the whole procedure " +
                    "again on it, the search over the component count included, so both sides of " +
                    "the comparison carry the same advantage and it cancels."
        )
        paragraph(
            "Consistency is not correctness. A wrong model that is flexible enough will not be " +
                    "contradicted by a sample this size, and the component structure can be wrong " +
                    "while the density is not."
        )
        if (!adequacy.canReject) {
            paragraph(
                "The p-value cannot fall below " +
                        "${"%.4f".format(adequacy.smallestAttainablePValue)} with " +
                        "${adequacy.numUsable} usable replicates, which is above the level asked " +
                        "of it, so this reading could not have rejected however the data fell."
            )
        }
    }
}

/**
 *  Every criterion at every candidate count, and where each one's minimum falls.
 *
 *  The section with no single-distribution counterpart and the one most worth reading. A criterion
 *  whose smallest value sits at an end of the searched range has not chosen a component count — it
 *  has run out of candidates — and the two are indistinguishable if only the winner is reported.
 *
 *  @param results the modeling results
 *  @param title the section title
 *  @param markBoundaryMinima whether to flag minima that sit at an end of the searched range.
 *  Suppressed automatically when the counts were specified rather than searched, since then every
 *  minimum sits at an end by construction and the flag would fire on every line.
 */
fun ReportBuilder.mixtureCriterionProfile(
    results: MixtureModelingResults,
    title: String = "Criterion behaviour across candidate counts",
    markBoundaryMinima: Boolean = true
) {
    section(title) { criterionProfileContent(results, markBoundaryMinima) }
}

/**
 *  The profile's tables without a section around them.
 *
 *  Exists so that the standard document can carry this content inside the count-evidence section
 *  while `mixtureCriterionProfile` remains available to a caller assembling their own document.
 *  Both tabulate criteria across candidate counts, and as two sections a reader had to compare two
 *  tables to answer one question.
 */
private fun ReportBuilder.criterionProfileContent(
    results: MixtureModelingResults,
    markBoundaryMinima: Boolean = true
) {
    run {
        val profile = results.criterionProfile()
        val counts = results.bestByNumComponents().keys.sorted()
        if (counts.isEmpty()) {
            paragraph("No candidate was fitted.")
            return@run
        }
        dataTable(
            listOf("Criterion") + counts.map { "k = $it" },
            profile.entries.map { (name, values) ->
                listOf(name) + counts.map { k ->
                    val v = values[k]
                    if (v != null && v.isComparable) "%.3f".format(v.value) else "—"
                }
            },
            "Criterion value at each number of components (smaller is better)"
        )

        val specified = results.numComponentsWasSpecified
        val flagBoundaries = markBoundaryMinima && !specified
        dataTable(
            listOf("Criterion", if (specified) "Prefers" else "Chooses", "Where the minimum sits"),
            results.componentCountAgreement().entries.map { (name, chosen) ->
                val where = when {
                    !flagBoundaries -> "—"
                    chosen == counts.last() -> "at the largest count fitted: it ran out of " +
                            "candidates rather than finding a best"
                    chosen == counts.first() -> "at the smallest count fitted"
                    else -> "interior"
                }
                listOf(name, "$chosen", where)
            },
            if (specified) "What each criterion prefers among the counts fitted"
            else "What each criterion chose, and whether it chose at all"
        )
        if (specified) {
            paragraph(
                "The counts were specified, not searched, so no criterion here ran out of " +
                        "candidates and none of them chose. The values are evidence about the " +
                        "choice, not a selection."
            )
        }
    }
}

/**
 *  What should make a reader doubt the recommendation.
 *
 *  Not optional, and placed after the recommendation rather than in a footnote. Measured across a
 *  designed experiment, this method recovers the density of a mixture well and its component
 *  structure poorly, so a report that presents the structure without its caveats invites a
 *  conclusion the evidence does not support.
 *
 *  @param results the modeling results
 *  @param heldOut observations the fit has not seen. When supplied, the share the fit cannot
 *  account for is reported, which is the strongest check available without a known truth.
 *  @param title the section title
 */
fun ReportBuilder.mixtureDoubts(
    results: MixtureModelingResults,
    heldOut: DoubleArray? = null,
    title: String = "What should make you doubt this"
) {
    section(title) {
        if (results.best == null) {
            paragraph("No mixture was fitted.")
            return@section
        }
        val checks = mutableListOf<List<String>>()

        val agreement = results.componentCountAgreement()
        val distinct = agreement.values.distinct()
        checks.add(
            when {
                results.numComponentsWasSpecified -> listOf(
                    "Component count",
                    "specified, not selected",
                    "Nothing here is evidence that it is the right number, only how the fitted " +
                            "counts compare against one component."
                )
                distinct.size > 1 -> listOf(
                    "Criterion agreement",
                    agreement.entries.joinToString(", ") { "${it.key}=${it.value}" },
                    "The criteria disagree. Treat the recommended count as one defensible answer " +
                            "among several."
                )
                else -> listOf(
                    "Criterion agreement",
                    "all chose ${distinct.firstOrNull() ?: "—"}",
                    "Agreement is weaker evidence than it looks: at a fixed count and partition " +
                            "the criteria share a likelihood and differ only in penalty."
                )
            }
        )

        results.boundedComponentShare?.let { share ->
            checks.add(
                listOf(
                    "Bounded-support components",
                    "%.0f%%".format(100.0 * share),
                    if (share > 0.0) {
                        "If the data has no reason to be bounded, this is the signature of a fit " +
                                "that has carved the sample up rather than described the process."
                    } else {
                        "No component has bounded support, so the fit cannot leave a support gap."
                    }
                )
            )
        }

        if (heldOut != null && heldOut.isNotEmpty()) {
            results.coverage(heldOut)?.let { coverage ->
                checks.add(
                    listOf(
                        "Held-out coverage",
                        "%.2f%% uncovered".format(100.0 * coverage.uncoveredShare),
                        if (coverage.uncoveredShare > 0.0) {
                            "Held-out data fell outside the fit's support, so the fit cannot " +
                                    "account for it at all."
                        } else {
                            "Every held-out observation received some density."
                        }
                    )
                )
                coverage.averageLogLikelihood?.let {
                    checks.add(
                        listOf(
                            "Held-out average log-likelihood", "%.4f".format(it),
                            "Defined only because nothing fell in a support gap."
                        )
                    )
                }
            }
        }

        if (results.truncatedGroupCounts.isNotEmpty()) {
            checks.add(
                listOf(
                    "Truncated searches",
                    "${results.truncatedGroupCounts.size} count(s)",
                    "The family search was cut short, so the reported best at those counts may " +
                            "not be their best."
                )
            )
        }

        dataTable(listOf("Check", "Value", "What it means"), checks, "Checks worth reading")
        paragraph(
            "This method is built to recover a density rather than a structure. Use the fit to " +
                    "generate values; be cautious about interpreting its parts."
        )
    }
}

/**
 *  Goodness-of-fit tests for the recommended mixture.
 *
 *  Mirrors the single-distribution report deliberately: a fitted mixture is an ordinary continuous
 *  distribution, so there is no reason for it to look different on the page.
 *
 *  @param results the modeling results
 *  @param title the section title
 */
fun ReportBuilder.mixtureGoodnessOfFit(
    results: MixtureModelingResults,
    title: String = "Goodness of fit"
) {
    section(title) {
        val gof = results.goodnessOfFit()
        if (gof == null) {
            paragraph("No mixture was fitted, so no tests were run.")
            return@section
        }
        // The single distribution beside the mixture, because the paragraph below tells the reader
        // these numbers are most useful as a comparison and a section that says so must supply it.
        // A one-component mixture *is* a single distribution fitted from the same catalog, so the
        // comparison is exact rather than approximate.
        val single = results.singleDistribution?.goodnessOfFit(results.observations)
        val headers = if (single == null) listOf("Test", "Statistic", "p-value")
        else listOf("Test", "Mixture statistic", "Mixture p", "Single statistic", "Single p")
        val rows = listOf(
            Triple("Anderson-Darling", gof.andersonDarlingStatistic, gof.andersonDarlingPValue),
            Triple("Cramer-von Mises", gof.cramerVonMisesStatistic, gof.cramerVonMisesPValue),
            Triple("Kolmogorov-Smirnov", gof.ksStatistic, gof.ksPValue)
        )
        val singleByName: Map<String, Pair<Double, Double>> = if (single == null) emptyMap() else
            mapOf(
                "Anderson-Darling" to
                        (single.andersonDarlingStatistic to single.andersonDarlingPValue),
                "Cramer-von Mises" to
                        (single.cramerVonMisesStatistic to single.cramerVonMisesPValue),
                "Kolmogorov-Smirnov" to (single.ksStatistic to single.ksPValue)
            )
        dataTable(
            headers,
            rows.map { (name, statistic, pValue) ->
                val base = listOf(name, "%.4f".format(statistic), "%.4f".format(pValue))
                val other = singleByName[name]
                if (other == null) base
                else base + listOf("%.4f".format(other.first), "%.4f".format(other.second))
            },
            if (single == null) "Tests of the recommended mixture"
            else "The recommended mixture against the best single distribution, on the same data"
        )
        if (single != null) {
            paragraph(
                "The single distribution is the best candidate at one component, fitted from the " +
                        "same catalog and ranked by the same criterion, so the two columns are " +
                        "comparable without qualification. It is named " +
                        "${results.singleDistribution?.name ?: "—"}."
            )
        }
        // Three paragraphs used to argue that these p-values are optimistic. The adequacy section
        // measures it instead, on this fit, so the argument is replaced by a pointer to a number.
        paragraph(
            "All three p-values are computed as though the mixture had been specified in advance. " +
                    "It was not — it was chosen by searching this sample — so none of them " +
                    "adjusts for the parameters estimated from it, and a mixture estimates many. " +
                    "Read them as a comparison between the two fits. The adequacy section gives " +
                    "the same distance against a null that carries the same advantage, which is " +
                    "the reading that can be taken at face value."
        )
        paragraph(
            "The chi-squared test the single-distribution report carries is absent because it is " +
                    "not trustworthy on a mixture: it needs a positive expected count in every " +
                    "bin, and components with bounded support leave bins with none or nearly " +
                    "none, so the statistic is dominated by the seams between components rather " +
                    "than by the quality of the fit. Coverage is reported in its place."
        )
    }
}

/**
 *  The four diagnostic panels for the recommendation and, by default, for the best single
 *  distribution beside it.
 *
 *  The comparison is the point: one shape against data that may have more than one is what the
 *  density panel shows at a glance, and a reader shown only the mixture has no alternative to
 *  judge it against.
 *
 *  @param results the modeling results
 *  @param includeSingleDistribution whether to draw the single-distribution panels too
 */
fun ReportBuilder.mixtureDiagnosticPlots(
    results: MixtureModelingResults,
    includeSingleDistribution: Boolean = true,
    variableName: String = "observed value"
) {
    val data = results.observations
    if (data.isEmpty()) return
    results.distributionFitPlot()?.let { plot ->
        section("Diagnostic plots for the recommended mixture") {
            // First, because it is the comparison an analyst asks for by name and the only one
            // here that needs no explanation of what it assumes. A two-sample test between these
            // two sets of dots would be invalid -- the drawn sample comes from a density fitted to
            // the real one and inherits its peculiarities -- but a picture claims no level, so
            // looking at them together assumes nothing.
            results.best?.let { best ->
                plot(
                    FittedAgainstDataPlot(data, best.distribution, variableName = variableName),
                    "The data, and a sample of the same size drawn from the fit"
                )
                paragraph(
                    "Read this for where the two part company rather than for whether they " +
                            "match. A fit that misses a small bump in the middle may be perfectly " +
                            "usable while one that misses the upper tail is not, and no single " +
                            "number separates those two cases. The drawn sample is the same size " +
                            "as the real one; drawing more would smooth it and flatter the fit."
                )
            }
            plot(plot.densityPlot, "Density against the data")
            plot(plot.ecdfPlot, "Empirical against fitted CDF")
            plot(plot.qqPlot, "Quantile-quantile")
            plot(plot.ppPlot, "Probability-probability")
        }
    }
    if (!includeSingleDistribution) return
    results.singleDistribution?.distributionFitPlot(data)?.let { plot ->
        section("Diagnostic plots for the best single distribution") {
            plot(plot.densityPlot, "Density against the data")
            plot(plot.ecdfPlot, "Empirical against fitted CDF")
            plot(plot.qqPlot, "Quantile-quantile")
            plot(plot.ppPlot, "Probability-probability")
        }
    }
}

/**
 *  How often the recommended component count survives resampling.
 *
 *  Takes results rather than running the bootstrap, because resampling refits the whole model many
 *  times and the caller must be the one who decides to pay for it.
 *
 *  Read the outcome as stability, never as correctness: it measures how much the data constrain
 *  the procedure, and a procedure can be stably wrong.
 *
 *  @param bootstrap the resampling results
 *  @param title the section title
 */
fun ReportBuilder.mixtureReliability(
    bootstrap: MixtureBootstrapResults,
    title: String = "Does the recommendation survive resampling?"
) {
    section(title) {
        dataTable(
            listOf("Property", "Value"),
            listOf(
                listOf("Resamples requested", "${bootstrap.numSamples}"),
                listOf("Resamples that fitted", "${bootstrap.numFitted}"),
                listOf("Modal component count", "${bootstrap.modalComponentCount ?: "—"}"),
                listOf("Stability of the modal count", "%.4f".format(bootstrap.stabilityOfModalCount)),
                listOf("Distinct values in the original", "${bootstrap.originalDistinctValues}"),
                listOf(
                    "Mean distinct values per resample",
                    "%.1f".format(bootstrap.distinctValues.average)
                )
            ),
            "Resampling summary"
        )
        val values = bootstrap.componentCountFrequency.values
        val counts = bootstrap.componentCountFrequency.frequencies
        dataTable(
            listOf("k", "Resamples choosing it", "Share"),
            values.indices.map { i ->
                listOf(
                    "${values[i]}",
                    "${counts[i]}",
                    "%.4f".format(bootstrap.componentCountFrequency.proportion(values[i]))
                )
            },
            "How often each component count was recommended"
        )
        paragraph(
            "This measures how much the data constrain the procedure, not whether the procedure " +
                    "is right. A stable recommendation can still be the wrong one."
        )
        if (!bootstrap.isParametric) {
            paragraph(
                "Resampling with replacement creates repeated values, which interacts with a " +
                        "method that cuts between sorted observations. The parametric variant " +
                        "removes the ties at the cost of assuming the fit is the truth."
            )
        }
    }
}

// -------------------------------------------------------------------------------- assembly

/**
 *  The standard mixture modeling report.
 *
 *  Sections are ordered to answer questions in the order a reader asks them: what the data are,
 *  whether a mixture was worth having, what was recommended, how the criteria behaved, and what
 *  should make you doubt it.
 *
 *  The bootstrap section appears only when results are supplied. This function never runs the
 *  resampling itself — expensive work started by a call that looks like formatting is a surprise,
 *  and the caller is better placed to decide.
 *
 *  @param title the document title
 *  @param heldOut observations the fit has not seen, used for the coverage check
 *  @param level the confidence level for the statistical summary
 *  @param bootstrap resampling results, when the caller has run them
 *  @param includePlots whether to include the diagnostic panels
 *  @param description the pre-fit characterisation, when the caller has taken one. Supplying it
 *  puts what was known before fitting in front of the fit, which is where it belongs.
 *  @param hasMechanism whether the analyst has a priori reason to believe in a mixing mechanism
 *  @param stability the cut-displacement measurement, when the caller has run it
 *  @param adequacy whether the fitted density reproduces its sample, from
 *  `MixtureModeler.assessFitAdequacy`. Supplied rather than computed for the same reason
 *  `bootstrap` is: it refits the model on every replicate, and a call that looks like formatting
 *  should not start that. Its section is omitted when absent.
 *  @param content extra sections, appended to the standard ones
 */
fun MixtureModelingResults.toReport(
    title: String = "Mixture Modeling Results",
    heldOut: DoubleArray? = null,
    level: Double = 0.95,
    bootstrap: MixtureBootstrapResults? = null,
    includePlots: Boolean = true,
    description: MixtureDataDescription? = null,
    hasMechanism: Boolean = false,
    stability: PartitionStability? = null,
    adequacy: FitAdequacy? = null,
    variableName: String = "observed value",
    content: ReportBuilder.() -> Unit = {}
): ReportNode.Document = report(title) {
    // An inadequate sample is said first and in the open, before the sample statistics and long
    // before any count. A reader who meets the recommendation first will anchor on it, and no
    // caveat further down undoes that.
    val countIsAnswerable = countAdequacy()
    val densityIsContradicted = adequacy?.verdict == FitVerdict.CONTRADICTED
    if (countIsAnswerable?.verdict == AdequacyVerdict.INSUFFICIENT || densityIsContradicted) {
        section("Read this first") {
            // The density being contradicted leads over the count being unanswerable, because it
            // is the more serious finding: an unsettled count still leaves a usable density, and a
            // contradicted density leaves nothing worth reading the count off.
            if (densityIsContradicted && adequacy != null) {
                paragraph(
                    "The fitted density does not describe this sample. It sits " +
                            "${"%.4f".format(adequacy.observedStatistic ?: 0.0)} from the data, " +
                            "further than resampling from the fit and refitting explains " +
                            "(p = ${"%.4f".format(adequacy.pValue ?: 0.0)}). Treat what follows " +
                            "as a description of what was fitted, not as an input model."
                )
            }
            if (countIsAnswerable?.verdict == AdequacyVerdict.INSUFFICIENT) {
                paragraph(
                    "This sample cannot settle how many components the mixture has. The fitted " +
                            "mixture sits %.4f from the nearest mixture with one component fewer, and "
                        .format(countIsAnswerable.separability) +
                            "at ${countIsAnswerable.sampleSize} observations that puts the " +
                            "difficulty at %.2f, where no procedure recovers the count reliably. " +
                            "About ".format(countIsAnswerable.adequacyRatio) +
                            "${countIsAnswerable.sizeForEvenOdds} observations would be needed " +
                            "for even odds."
                )
                paragraph(
                    "Everything below is still worth reading as a description of a density that " +
                            "fits the data. None of it is evidence about the number of components."
                )
            }
        }
    }
    description?.let { mixtureBeforeFitting(it, hasMechanism, level = 1.0 - level) }
    mixtureDataSummary(
        this@toReport, level = level, includePlots = includePlots, variableName = variableName
    )
    mixtureAdequacy(this@toReport)
    mixtureWorthIt(this@toReport)
    // Before the components, so that whether the density is usable is settled before any
    // structural claim is made. The density is what this method recovers well.
    adequacy?.let { mixtureFitAdequacy(it) }
    mixtureComponents(this@toReport)
    // The criterion profile is folded in here rather than given its own section: both tabulate
    // criteria across candidate counts, and splitting them made a reader compare two tables to
    // answer one question.
    mixtureCountEvidence(this@toReport, includePlot = includePlots)
    mixtureGoodnessOfFit(this@toReport)
    mixtureDoubts(this@toReport, heldOut)
    // After the doubts rather than before them. With its calibration figures gone this section is
    // usually silent, and a silent section between the recommendation and the doubts read as
    // though the question had been settled.
    stability?.let {
        mixtureCutDependence(
            it,
            results = if (includePlots) this@toReport else null,
            variableName = variableName
        )
    }
    bootstrap?.let { mixtureReliability(it) }
    if (includePlots) mixtureDiagnosticPlots(this@toReport, variableName = variableName)
    content()
}

/**
 *  The standard report as a self-contained HTML page.
 *
 *  Replaces a hand-built page that wrapped the console summary in a preformatted block. That form
 *  was readable and structurally empty — a renderer could not turn its tables into tables — so the
 *  page is now rendered from the same document every other format comes from. Plots are embedded
 *  in the page rather than written beside it, so the result is one file that can be sent as it is.
 *
 *  @param heldOut observations the fit has not seen, used for the coverage check
 *  @param title the document title
 *  @param bootstrap resampling results, when the caller has run them
 *  @param includePlots whether to include the diagnostic panels
 *  @param description the pre-fit characterisation, when the caller has taken one
 *  @param hasMechanism whether the analyst has a priori reason to believe in a mixing mechanism
 *  @param stability the cut-displacement measurement, when the caller has run it
 *  @param adequacy whether the fitted density reproduces its sample, when the caller has
 *  assessed it. Omitted from the document when absent.
 */
fun MixtureModelingResults.asHTML(
    heldOut: DoubleArray? = null,
    title: String = "Mixture Modeling Results",
    bootstrap: MixtureBootstrapResults? = null,
    includePlots: Boolean = true,
    description: MixtureDataDescription? = null,
    hasMechanism: Boolean = false,
    stability: PartitionStability? = null,
    adequacy: FitAdequacy? = null,
    variableName: String = "observed value"
): String = toReport(
    title, heldOut, bootstrap = bootstrap, includePlots = includePlots,
    description = description, hasMechanism = hasMechanism, stability = stability,
    adequacy = adequacy, variableName = variableName
)
    .toHtml(RenderContext())

/**
 *  The standard report as Markdown, for pasting into a document that is already text.
 *
 *  Plots are omitted by default: Markdown has no way to carry an embedded interactive plot, so
 *  including them would produce captions with nothing under them.
 *
 *  @param heldOut observations the fit has not seen, used for the coverage check
 *  @param title the document title
 *  @param bootstrap resampling results, when the caller has run them
 *  @param includePlots whether to include the diagnostic panels
 *  @param description the pre-fit characterisation, when the caller has taken one
 *  @param hasMechanism whether the analyst has a priori reason to believe in a mixing mechanism
 *  @param stability the cut-displacement measurement, when the caller has run it
 *  @param adequacy whether the fitted density reproduces its sample, when the caller has
 *  assessed it. Omitted from the document when absent.
 */
fun MixtureModelingResults.asMarkdown(
    heldOut: DoubleArray? = null,
    title: String = "Mixture Modeling Results",
    bootstrap: MixtureBootstrapResults? = null,
    includePlots: Boolean = false,
    description: MixtureDataDescription? = null,
    hasMechanism: Boolean = false,
    stability: PartitionStability? = null,
    adequacy: FitAdequacy? = null,
    variableName: String = "observed value"
): String = toReport(
    title, heldOut, bootstrap = bootstrap, includePlots = includePlots,
    description = description, hasMechanism = hasMechanism, stability = stability,
    adequacy = adequacy, variableName = variableName
)
    .toMarkdown(RenderContext())

/**
 *  Writes the HTML report to a file and opens it.
 *
 *  @param heldOut observations the fit has not seen, used for the coverage check
 *  @param title the document title
 *  @param bootstrap resampling results, when the caller has run them
 *  @param includePlots whether to include the diagnostic panels
 *  @param description the pre-fit characterisation, when the caller has taken one
 *  @param hasMechanism whether the analyst has a priori reason to believe in a mixing mechanism
 *  @param stability the cut-displacement measurement, when the caller has run it
 *  @param adequacy whether the fitted density reproduces its sample, when the caller has
 *  assessed it. Omitted from the document when absent.
 */
fun MixtureModelingResults.showHTMLInBrowser(
    heldOut: DoubleArray? = null,
    title: String = "Mixture Modeling Results",
    bootstrap: MixtureBootstrapResults? = null,
    includePlots: Boolean = true,
    description: MixtureDataDescription? = null,
    hasMechanism: Boolean = false,
    stability: PartitionStability? = null,
    adequacy: FitAdequacy? = null,
    variableName: String = "observed value"
): File = toReport(
    title, heldOut, bootstrap = bootstrap, includePlots = includePlots,
    description = description, hasMechanism = hasMechanism, stability = stability,
    adequacy = adequacy, variableName = variableName
)
    .showInBrowser(RenderContext())
