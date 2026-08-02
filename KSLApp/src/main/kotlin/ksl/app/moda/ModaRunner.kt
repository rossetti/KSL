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

package ksl.app.moda

import ksl.utilities.moda.AdditiveMODAModel
import ksl.utilities.moda.AlternativeRejection
import ksl.utilities.moda.MetricIfc
import ksl.utilities.moda.ModaSnapshot
import ksl.utilities.moda.Score
import ksl.utilities.moda.ValueFunctionIfc

/**
 *  What came of running a study.
 */
sealed interface ModaRunResult {

    /** Everything worth telling the caller about the document, whether or not it ran. */
    val issues: List<ValidationIssue>

    /**
     *  The study was not run, because the document says something that cannot be carried out.
     *
     *  Nothing is attempted in this case. A study run on a document that is wrong somewhere would
     *  produce numbers, and numbers are believed.
     */
    data class Invalid(
        override val issues: List<ValidationIssue>
    ) : ModaRunResult {
        val errors: List<ValidationIssue>
            get() = issues.filter { it.severity == Severity.ERROR }
    }

    /**
     *  The study ran.
     *
     *  It may still have things to report. An alternative with no score for one of the metrics
     *  cannot be compared with one that has them all, so it is left out, and both the leaving out
     *  and the reason are reported rather than the result simply being smaller than expected.
     */
    data class Completed(
        val snapshot: ModaSnapshot,
        val rejected: List<AlternativeRejection>,
        val missing: List<MissingScore>,
        override val issues: List<ValidationIssue>
    ) : ModaRunResult {

        /** Whether every alternative asked for made it into the result. */
        val allAlternativesIncluded: Boolean
            get() = rejected.isEmpty() && missing.isEmpty()
    }
}

/**
 *  Runs a written-down study.
 *
 *  The document is checked first and in full, and nothing is run if anything in it cannot be
 *  carried out, so a study either produces a result that means what it says or produces no result
 *  and an explanation.
 *
 *  Metrics are built once here, from the document, and the same instances are used for every score
 *  and for the model. A model matches metrics by identity rather than by name, so a score built
 *  against a separately created metric of the same name belongs to no metric the model holds and
 *  its alternative silently drops out. Building them in one place and using them throughout is what
 *  makes that impossible rather than merely unlikely.
 *
 *  @param registry the value functions available
 *  @param resolver how the document's source reference becomes something to read scores from
 */
class ModaRunner(
    private val registry: ValueFunctionRegistry = ValueFunctionRegistry.Default,
    private val resolver: ModaSourceResolver = ModaSourceResolver()
) {

    private val validator = ModaDocumentValidator(registry, resolver)

    fun run(document: ModaDocument): ModaRunResult {
        val issues = validator.validate(document)
        if (issues.any { it.severity == Severity.ERROR }) {
            return ModaRunResult.Invalid(issues)
        }

        val source = resolver.resolve(document.source)
        val table = source.scores(
            document.alternatives.toSet(),
            document.metrics.map { it.name }.toSet()
        )

        // Built once, and used for both the model and every score, so identity always matches.
        val metrics: List<MetricIfc> = document.metrics.map { it.toMetric() }
        val byName = metrics.associateBy { it.name }
        val functions: Map<MetricIfc, ValueFunctionIfc> = document.metrics.associate { spec ->
            byName[spec.name]!! to registry.resolve(spec.valueFunctionId, spec.parameters)
        }

        val weights = weightsFor(document, metrics)
        val model = AdditiveMODAModel(functions, weights, name = document.name)

        val scores = table.values.mapValues { (_, byMetric) ->
            metrics.map { metric -> Score(metric, byMetric[metric.name]!!) }
        }
        val defined = model.defineAlternativesReporting(scores, document.rescalePolicy.allowsRescaling)

        if (model.alternatives.size < 2) {
            // Comparing needs something to compare against. Say which of the two ways it ran short.
            return ModaRunResult.Invalid(
                issues + ValidationIssue(
                    Severity.ERROR, "alternatives",
                    "Only ${model.alternatives.size} alternative(s) could be evaluated, which is too " +
                            "few to compare. " + shortfallDetail(table, defined.rejected)
                )
            )
        }

        val snapshot = ModaSnapshot.of(
            model,
            document.rankingMethodOrNull() ?: model.defaultRankingMethod,
            document.aggregationMethodOrNull() ?: ksl.utilities.moda.AggregationMethod.WEIGHTED_VALUE
        )

        // A last check that the ranges the weights were given against are the ranges actually used.
        // Validation compares them against what the document declares; this compares them against
        // what the run turned out to evaluate over, which is what the weights have to match.
        val elicitation = document.elicitation?.toRecord()
        val invalidation = elicitation?.invalidationReason(snapshot)
        if (invalidation != null) {
            return ModaRunResult.Invalid(
                issues + ValidationIssue(Severity.ERROR, "elicitation", invalidation)
            )
        }

        return ModaRunResult.Completed(
            snapshot = snapshot,
            rejected = defined.rejected,
            missing = table.missing,
            issues = issues
        )
    }

    /**
     *  The weights to run with: those elicited from someone if the document carries them, otherwise
     *  those it declares. Elicited weights take precedence because they were arrived at by asking a
     *  question with the metric ranges built into it, which is what an additive model's weights are
     *  supposed to mean.
     */
    private fun weightsFor(document: ModaDocument, metrics: List<MetricIfc>): Map<MetricIfc, Double> {
        val elicited = document.elicitation?.toRecord()?.weights()
            ?: return document.weightsBy(metrics)
        return metrics.associateWith { elicited[it.name] ?: 0.0 }
    }

    private fun shortfallDetail(table: ScoreTable, rejected: List<AlternativeRejection>): String {
        val parts = mutableListOf<String>()
        if (table.missing.isNotEmpty()) {
            val names = table.missing.map { it.alternative }.distinct().sorted()
            parts.add("Left out for want of a score: ${names.joinToString(", ")}.")
        }
        if (rejected.isNotEmpty()) {
            parts.add("Not taken in: " + rejected.joinToString("; ") { it.message })
        }
        return parts.joinToString(" ").ifEmpty { "The source supplied nothing for them." }
    }
}
