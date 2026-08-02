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

import ksl.utilities.moda.AggregationMethod
import ksl.utilities.statistic.Statistic
import java.nio.file.Paths

/**
 *  How much a problem with a document matters.
 */
enum class Severity {

    /** The study cannot be run as written. */
    ERROR,

    /** The study can be run, but it probably does not say what was meant. */
    WARNING
}

/**
 *  Something wrong, or worth remarking on, in a document.
 *
 *  Each names the part of the document it concerns, so a long list can be worked through one entry
 *  at a time rather than re-read against the document to work out where each applies.
 */
data class ValidationIssue(
    val severity: Severity,
    val element: String,
    val message: String
) {
    override fun toString(): String = "[$severity] $element: $message"
}

/**
 *  Checks a document before anything is run.
 *
 *  Everything wrong is reported together rather than stopping at the first problem, because fixing
 *  a study one error per attempt is tedious where the errors are usually independent. Problems that
 *  make a study impossible are separated from ones that merely suggest it does not say what was
 *  meant, so a study with only the latter still runs.
 *
 *  @param registry the value functions available to run with, so a document naming one that is not
 *  there is caught here rather than part way through a run
 *  @param resolver how sources are resolved, so a study whose data cannot be reached is caught
 *  before it starts rather than after some of it has happened
 */
class ModaDocumentValidator(
    private val registry: ValueFunctionRegistry = ValueFunctionRegistry.Default,
    private val resolver: ModaSourceResolver = ModaSourceResolver()
) {

    fun validate(document: ModaDocument): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        checkVersion(document, issues)
        checkShape(document, issues)
        checkMetrics(document, issues)
        checkWeights(document, issues)
        checkMethods(document, issues)
        checkSource(document, issues)
        checkElicitation(document, issues)
        return issues
    }

    /** Whether the document can be run as written. */
    fun isRunnable(document: ModaDocument): Boolean =
        validate(document).none { it.severity == Severity.ERROR }

    private fun MutableList<ValidationIssue>.error(element: String, message: String) =
        add(ValidationIssue(Severity.ERROR, element, message))

    private fun MutableList<ValidationIssue>.warn(element: String, message: String) =
        add(ValidationIssue(Severity.WARNING, element, message))

    private fun checkVersion(document: ModaDocument, issues: MutableList<ValidationIssue>) {
        if (document.schemaVersion > ModaDocument.SCHEMA_VERSION) {
            issues.error(
                "schemaVersion",
                "This document is version ${document.schemaVersion} but this reader understands up " +
                        "to ${ModaDocument.SCHEMA_VERSION}. It was written by a later version and " +
                        "cannot be read faithfully."
            )
        }
        if (document.schemaVersion < 1) {
            issues.error("schemaVersion", "A document's version must be at least 1. It was ${document.schemaVersion}.")
        }
    }

    private fun checkShape(document: ModaDocument, issues: MutableList<ValidationIssue>) {
        if (document.name.isBlank()) {
            issues.error("name", "A study must be named, so its results can be told apart from another's.")
        }
        if (document.metrics.isEmpty()) {
            issues.error("metrics", "A study must have at least one metric to compare alternatives on.")
        }
        if (document.alternatives.size < 2) {
            issues.error(
                "alternatives",
                "A study must compare at least two alternatives. It has ${document.alternatives.size}."
            )
        }
        val duplicateAlternatives = document.alternatives.groupBy { it }.filter { it.value.size > 1 }.keys
        if (duplicateAlternatives.isNotEmpty()) {
            issues.error(
                "alternatives",
                "Alternatives must be named only once. Repeated: ${duplicateAlternatives.sorted().joinToString(", ")}."
            )
        }
        if (document.metrics.isNotEmpty() && document.alternatives.isNotEmpty() &&
            document.metrics.size > document.alternatives.size
        ) {
            issues.warn(
                "metrics",
                "There are more metrics (${document.metrics.size}) than alternatives " +
                        "(${document.alternatives.size}). That is allowed, but with so few " +
                        "alternatives the metrics have little to separate."
            )
        }
    }

    private fun checkMetrics(document: ModaDocument, issues: MutableList<ValidationIssue>) {
        val duplicates = document.metrics.groupBy { it.name }.filter { it.value.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            issues.error(
                "metrics",
                "Metric names must be unique within a study, since every report and record refers " +
                        "to them by name. Repeated: ${duplicates.sorted().joinToString(", ")}."
            )
        }
        for (metric in document.metrics) {
            val element = "metrics.${metric.name}"
            if (metric.name.isBlank()) {
                issues.error("metrics", "Every metric must be named.")
            }
            if (!registry.contains(metric.valueFunctionId)) {
                issues.error(
                    element,
                    "No value function is registered under '${metric.valueFunctionId}'. " +
                            "Available: ${registry.availableIds.joinToString(", ")}."
                )
            }
            if (metric.directionOrNull() == null) {
                issues.error(
                    element,
                    "'${metric.direction}' is not a direction. It must be one of: " +
                            ksl.utilities.moda.MetricIfc.Direction.entries.joinToString(", ") { it.name } + "."
                )
            }
            if (!metric.hasUsableLimits) {
                issues.error(
                    element,
                    "The lower limit (${metric.lowerLimit}) must be below the upper limit " +
                            "(${metric.upperLimit}), and the range between them must be finite."
                )
            }
            if (document.rescalePolicy == RescalePolicy.FIXED && !metric.hasFiniteLimits) {
                issues.error(
                    element,
                    "The ${RescalePolicy.FIXED} policy needs real limits, because the weights are " +
                            "tied to them. This metric leaves a limit unbounded " +
                            "(${metric.lowerLimit} to ${metric.upperLimit})."
                )
            }
            if (metric.upperLimit == Double.MAX_VALUE && document.rescalePolicy != RescalePolicy.FIXED) {
                issues.warn(
                    element,
                    "This metric has no real upper limit, which is the default rather than a " +
                            "considered choice. Values will be computed against whatever range the " +
                            "scores happen to cover."
                )
            }
        }
    }

    private fun checkWeights(document: ModaDocument, issues: MutableList<ValidationIssue>) {
        if (document.metrics.isEmpty()) return
        for (metric in document.metrics) {
            if (metric.weight < 0.0) {
                issues.error(
                    "metrics.${metric.name}",
                    "A weight cannot be negative. It was ${metric.weight}."
                )
            }
        }
        val total = document.metrics.sumOf { it.weight }
        if (total <= 0.0) {
            issues.error(
                "metrics",
                "The weights must add up to something above zero, otherwise no metric counts " +
                        "towards the result."
            )
        }
    }

    private fun checkMethods(document: ModaDocument, issues: MutableList<ValidationIssue>) {
        if (document.rankingMethodOrNull() == null) {
            issues.error(
                "rankingMethod",
                "'${document.rankingMethod}' is not a ranking method. It must be one of: " +
                        Statistic.Companion.Ranking.entries.joinToString(", ") { it.name } + "."
            )
        }
        if (document.aggregationMethodOrNull() == null) {
            issues.error(
                "aggregationMethod",
                "'${document.aggregationMethod}' is not an aggregation method. It must be one of: " +
                        AggregationMethod.entries.joinToString(", ") { it.name } + "."
            )
        }
    }

    private fun checkSource(document: ModaDocument, issues: MutableList<ValidationIssue>) {
        val problem = resolver.resolutionProblem(document.source)
        if (problem != null) {
            issues.error("source", problem)
        }
        val source = document.source
        if (source is ModaSourceReference.DelimitedFile) {
            if (Paths.get(source.path).isAbsolute) {
                issues.warn(
                    "source",
                    "The path '${source.path}' is absolute, so this study will only run on a machine " +
                            "with that exact layout. A path relative to the document travels with it."
                )
            }
            val named = document.metrics.map { it.name }.toSet()
            val unmatched = named - source.metricColumns.toSet()
            if (unmatched.isNotEmpty()) {
                issues.error(
                    "source",
                    "No column is named for metric(s): ${unmatched.sorted().joinToString(", ")}. " +
                            "The file supplies: ${source.metricColumns.joinToString(", ")}."
                )
            }
        }
        if (source is ModaSourceReference.InlineScores) {
            val missing = document.alternatives.filter { !source.table.containsKey(it) }
            if (missing.isNotEmpty()) {
                issues.error(
                    "source",
                    "The document holds no scores for: ${missing.sorted().joinToString(", ")}."
                )
            }
        }
    }

    private fun checkElicitation(document: ModaDocument, issues: MutableList<ValidationIssue>) {
        val elicitation = document.elicitation ?: return
        if (document.rescalePolicy != RescalePolicy.FIXED) {
            val detail = if (document.rescalePolicy == RescalePolicy.FROM_SCORES) {
                "This study fits its limits to the scores, so the ranges the weights were given " +
                        "against would change underneath them."
            } else {
                "Elicited weights need limits that are guaranteed not to move and that were real " +
                        "bounds when they were shown."
            }
            issues.error(
                "elicitation",
                "Weights elicited from someone require the ${RescalePolicy.FIXED} policy. $detail"
            )
        }
        val metricNames = document.metrics.map { it.name }.toSet()
        val unknown = elicitation.ratings.keys - metricNames
        if (unknown.isNotEmpty()) {
            issues.error(
                "elicitation",
                "Weights were given for metric(s) the study does not have: ${unknown.sorted().joinToString(", ")}."
            )
        }
        val unrated = metricNames - elicitation.ratings.keys
        if (unrated.isNotEmpty()) {
            issues.error(
                "elicitation",
                "No weight was given for metric(s): ${unrated.sorted().joinToString(", ")}. " +
                        "Every metric must be rated for the weights to be comparable."
            )
        }
        if (elicitation.ratings.values.sum() <= 0.0) {
            issues.error("elicitation", "At least one swing must have been rated above zero.")
        }
        // The ranges someone was shown, against what the document now declares. A document edited
        // after the weights were given is the case this catches, and the numbers look fine.
        val moved = document.metrics.filter { metric ->
            val elicited = elicitation.elicitedAgainst[metric.name] ?: return@filter false
            elicited.lowerLimit != metric.lowerLimit || elicited.upperLimit != metric.upperLimit
        }.map { it.name }
        if (moved.isNotEmpty()) {
            issues.error(
                "elicitation",
                "The weights were given against ranges the study no longer declares, for: " +
                        "${moved.sorted().joinToString(", ")}. A swing weight is tied to the range " +
                        "it was given against, so these weights no longer describe the study."
            )
        }
    }
}
