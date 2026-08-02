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

import ksl.utilities.moda.ModaSnapshot

/**
 *  What running a study once per replication came to.
 */
sealed interface PerReplicationResult {

    /**
     *  The study was not run this way, because the runs will not support it.
     */
    data class Refused(
        val issues: List<ValidationIssue>
    ) : PerReplicationResult {
        val reasons: List<String>
            get() = issues.map { it.message }
    }

    /**
     *  The study was run once for each replication the alternatives share.
     *
     *  @param byReplication the study as it came out for each replication, by replication number
     *  @param overallValuesByAlternative each alternative's overall value in each replication,
     *  ordered to match [replicationIds], so the values for one replication line up across
     *  alternatives and paired comparisons remain valid
     *  @param replicationIds the replications the study was run over, in order
     *  @param winCounts how many replications each alternative came first in
     */
    data class Completed(
        val byReplication: Map<Int, ModaSnapshot>,
        val overallValuesByAlternative: Map<String, List<Double>>,
        val replicationIds: List<Int>,
        val winCounts: Map<String, Int>
    ) : PerReplicationResult {

        /**
         *  The alternative winning most replications, ties resolved by name so the same runs give
         *  the same answer every time.
         */
        val mostFrequentWinner: String
            get() = winCounts.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .first().key

        /** The share of replications each alternative came first in. */
        val winProportions: Map<String, Double>
            get() = winCounts.mapValues { it.value.toDouble() / replicationIds.size.toDouble() }
    }
}

/**
 *  Runs a study once for every replication, rather than once over the averages.
 *
 *  Running over the averages says which alternative is best on average. Running per replication
 *  says how often each alternative comes first, which is a different question and usually the more
 *  honest one: an alternative that wins narrowly on the averages but only in half the replications
 *  is not really a recommendation.
 *
 *  This is asked for rather than done automatically, because it needs more of the runs than
 *  averaging does. Every alternative must have been observed over the same replications, so that
 *  each comparison is between alternatives run under the same conditions. Where a study drives
 *  every alternative with the same random numbers, that pairing is what makes the comparison
 *  sharper than comparing averages; where the counts differ, the pairing does not exist and the
 *  study is refused rather than run on whatever happens to line up.
 */
class ReplicatedModaRunner(
    private val registry: ValueFunctionRegistry = ValueFunctionRegistry.Default
) {

    /**
     *  Runs [document] once for each replication the alternatives share, taking scores from
     *  [source].
     */
    fun runPerReplication(
        document: ModaDocument,
        source: ReplicatedModaSourceIfc
    ): PerReplicationResult {
        val issues = mutableListOf<ValidationIssue>()

        // The document is checked the same way as for any run, except that where its scores come
        // from is settled by the source it was handed rather than by what it says.
        val documentIssues = ModaDocumentValidator(registry, resolverAccepting(document))
            .validate(document)
            .filter { it.element != "source" }
        issues.addAll(documentIssues)
        if (issues.any { it.severity == Severity.ERROR }) {
            return PerReplicationResult.Refused(issues)
        }

        val counts = document.alternatives.associateWith { source.replicationIds(it).size }
        val absent = counts.filterValues { it == 0 }.keys
        if (absent.isNotEmpty()) {
            issues.add(
                ValidationIssue(
                    Severity.ERROR, "alternatives",
                    "No replications were found for: ${absent.sorted().joinToString(", ")}."
                )
            )
            return PerReplicationResult.Refused(issues)
        }
        if (counts.values.distinct().size > 1) {
            issues.add(
                ValidationIssue(
                    Severity.ERROR, "alternatives",
                    "Comparing replication by replication needs every alternative observed over the " +
                            "same number of replications, so that each comparison is between runs " +
                            "under the same conditions. Counts were: " +
                            counts.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}=${it.value}" } +
                            ". Compare the averages instead, or re-run so the counts match."
                )
            )
            return PerReplicationResult.Refused(issues)
        }

        val shared = source.commonReplicationIds(document.alternatives)
        if (shared.size < 2) {
            issues.add(
                ValidationIssue(
                    Severity.ERROR, "alternatives",
                    "The alternatives share only ${shared.size} replication(s), which is too few to " +
                            "compare them replication by replication. Replications are matched by " +
                            "number, so alternatives run over different replication numbers share none."
                )
            )
            return PerReplicationResult.Refused(issues)
        }

        val snapshots = LinkedHashMap<Int, ModaSnapshot>()
        val overall = document.alternatives.associateWith { mutableListOf<Double>() }
        val wins = document.alternatives.associateWith { 0 }.toMutableMap()

        for (replication in shared) {
            val table = tableFor(source, document, replication)
            val single = ModaRunner(registry, resolverFor(table))
                .run(document.copy(name = "${document.name} (replication $replication)"))
            when (single) {
                is ModaRunResult.Invalid -> {
                    issues.addAll(single.errors.map {
                        it.copy(message = "In replication $replication: ${it.message}")
                    })
                    return PerReplicationResult.Refused(issues)
                }
                is ModaRunResult.Completed -> {
                    snapshots[replication] = single.snapshot
                    for (alternative in document.alternatives) {
                        overall[alternative]!!.add(single.snapshot.overallValues[alternative] ?: Double.NaN)
                    }
                    wins[single.snapshot.primaryRecommendation] =
                        (wins[single.snapshot.primaryRecommendation] ?: 0) + 1
                }
            }
        }

        return PerReplicationResult.Completed(
            byReplication = snapshots,
            overallValuesByAlternative = overall.mapValues { it.value.toList() },
            replicationIds = shared,
            winCounts = wins
        )
    }

    /** The scores of one replication, in the shape a source supplies. */
    private fun tableFor(
        source: ReplicatedModaSourceIfc,
        document: ModaDocument,
        replication: Int
    ): ScoreTable {
        val values = mutableMapOf<String, Map<String, Double>>()
        val missing = mutableListOf<MissingScore>()
        for (alternative in document.alternatives) {
            val ids = source.replicationIds(alternative)
            val position = ids.indexOf(replication)
            val row = mutableMapOf<String, Double>()
            var whole = true
            for (metric in document.metrics.map { it.name }.sorted()) {
                val series = source.replicationScores(alternative, metric)
                val score = if (position < 0 || series == null || position >= series.size) {
                    null
                } else {
                    series[position]
                }
                if (score == null || !score.isFinite()) {
                    missing.add(MissingScore(alternative, metric))
                    whole = false
                } else {
                    row[metric] = score
                }
            }
            if (whole) values[alternative] = row
        }
        return ScoreTable(values, missing)
    }

    /** A resolver that hands back the scores of a single replication whatever it is asked for. */
    private fun resolverFor(table: ScoreTable): ModaSourceResolver = ModaSourceResolver(
        providers = mapOf(
            SimulationModaSource.SIMULATION_PROVIDER_ID to ModaSourceProviderIfc {
                ModaSourceIfc { alternatives, metrics ->
                    ScoreTable(
                        table.values.filterKeys { it in alternatives }
                            .mapValues { row -> row.value.filterKeys { it in metrics } },
                        table.missing
                    )
                }
            }
        )
    )

    /**
     *  A resolver that accepts whatever source the document names, since the scores are coming from
     *  the one handed in rather than from anything the document points at.
     */
    private fun resolverAccepting(document: ModaDocument): ModaSourceResolver =
        when (val reference = document.source) {
            is ModaSourceReference.RegisteredProvider -> ModaSourceResolver(
                providers = mapOf(
                    reference.providerId to ModaSourceProviderIfc {
                        ModaSourceIfc { _, _ -> ScoreTable(emptyMap(), emptyList()) }
                    }
                )
            )
            else -> ModaSourceResolver()
        }
}
