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

package ksl.utilities.moda

import ksl.utilities.Interval
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.io.dbutil.WithinRepViewData
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rvariable.ExponentialRV
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 *  Canonical baseline for the MODA engine, recorded before any of the engine work so that an
 *  intended change shows up as a readable diff rather than as a pass/fail.
 *
 *  Three areas are recorded, because they are the three that the engine work can disturb:
 *
 *  1. `PDFModeler` evaluation — the distribution it recommends and the ranking behind that
 *     recommendation. Rescaling runs here (`PDFModeler` calls `defineAlternatives` with rescaling
 *     left on), so this is the area carrying real risk. The recorded metric domains are the
 *     *effective* domains actually used to transform scores; a change to how rescaling is
 *     represented must not move them.
 *  2. `MODAAnalyzer` — which never rescales, so it should be bit-identical throughout. It is
 *     recorded precisely so that "should be inert" is checked rather than assumed.
 *  3. The tied-score defect, recorded as it behaves *today*: a crash for three or more tied
 *     alternatives and a silent NaN for one or two tied at an integral score. This is the one
 *     block expected to change, and changing it is the point of the work.
 *
 *  Canonicalization follows the rules the plan sets out. Surrogate database ids and database
 *  metadata are excluded, since they are counters rather than results. Ordering is made stable by
 *  sorting on name rather than relying on map iteration order. Categorical output — names,
 *  directions, orderings, exception types — is compared exactly. Floating point output is rounded
 *  to nine decimals, which is far tighter than any difference the engine work could legitimately
 *  cause while still absorbing last-bit noise.
 *
 *  The inputs are fixed: data comes from a named random stream, and the analyzer data is computed
 *  by formula rather than sampled, so there are no seeds to drift.
 *
 *  To re-record after an approved behaviour change:
 *
 *      ./gradlew :KSLCore:test --tests '*ModaEngineBaselineTest*' -Dmoda.baseline.record=true
 *
 *  and commit the resulting files under `src/test/resources/moda/` as part of the same change, so
 *  the diff shows exactly what moved.
 */
class ModaEngineBaselineTest {

    // ----------------------------------------------------------------------------------------
    // 1. PDFModeler evaluation — the area rescaling actually touches
    // ----------------------------------------------------------------------------------------

    @Test
    fun `PDFModeler evaluation baseline`() {
        assertMatchesBaseline("pdfmodeler", pdfModelerReport())
    }

    private fun pdfModelerReport(): String {
        // A provider of its own rather than the shared default one. Streams from the default
        // provider carry whatever position earlier tests left them in, which would make this
        // fixture depend on what else ran and in what order.
        val data = ExponentialRV(10.0, streamNum = 2, streamProvider = RNStreamProvider()).sample(500)
        val modeler = PDFModeler(data)
        val estimates = modeler.estimateParameters(PDFModeler.allEstimators)
        val model = modeler.evaluateScoringResults(modeler.scoringResults(estimates))
        return report("PDFModeler default evaluation, Exponential(10.0) stream 2, n=500") {
            appendModel(model)
        }
    }

    // ----------------------------------------------------------------------------------------
    // 2. MODAAnalyzer — never rescales, so this must not move at all
    // ----------------------------------------------------------------------------------------

    @Test
    fun `MODAAnalyzer baseline`() {
        assertMatchesBaseline("modaanalyzer", modaAnalyzerReport())
    }

    /**
     *  Replication data built by formula rather than sampled, so it is reproducible without
     *  depending on any stream. Alternatives separate cleanly on both responses, and the two
     *  responses disagree about ordering, so ranking behaviour is actually exercised.
     */
    private fun analyzerData(): List<WithinRepViewData> {
        val list = mutableListOf<WithinRepViewData>()
        for ((index, alternative) in ANALYZER_ALTERNATIVES.withIndex()) {
            for (response in ANALYZER_RESPONSES) {
                for (rep in 1..ANALYZER_REPLICATIONS) {
                    // Cost rises across alternatives; Delay falls, so no alternative dominates.
                    val base = if (response == "Cost") 10.0 + 2.0 * index else 9.0 - 1.5 * index
                    list.add(
                        WithinRepViewData(
                            exp_name = alternative,
                            run_name = "baseline",
                            num_reps = ANALYZER_REPLICATIONS,
                            start_rep_id = 1,
                            last_rep_id = ANALYZER_REPLICATIONS,
                            stat_name = response,
                            rep_id = rep,
                            rep_value = base + 0.1 * rep
                        )
                    )
                }
            }
        }
        return list
    }

    private fun modaAnalyzerReport(): String {
        val analyzer = MODAAnalyzer(
            alternativeNames = ANALYZER_ALTERNATIVES.toSet(),
            responseDefinitions = ANALYZER_RESPONSES.map { MODAAnalyzerData(it) }.toSet(),
            responseData = analyzerData()
        )
        analyzer.analyze()
        return report("MODAAnalyzer, ${ANALYZER_ALTERNATIVES.size} alternatives x ${ANALYZER_REPLICATIONS} replications") {
            appendLine("[overall value by alternative, per replication]")
            for (alternative in analyzer.overallValueByAlternative.keys.sorted()) {
                val values = analyzer.overallValueByAlternative[alternative]!!
                appendLine("  $alternative = ${values.joinToString(", ") { num(it) }}")
            }
            appendLine()
            appendLine("[averageMODA]")
            appendModel(analyzer.averageMODA())
        }
    }

    // ----------------------------------------------------------------------------------------
    // 3. Tied scores — recorded as the defect behaves today
    // ----------------------------------------------------------------------------------------

    /**
     *  This is the block the engine work is expected to change. Recording it first means the
     *  change shows up as a diff against a stated starting point instead of as a test that simply
     *  starts passing.
     */
    @Test
    fun `tied score baseline`() {
        assertMatchesBaseline("tied-scores", tiedScoreReport())
    }

    private fun tiedScoreReport(): String =
        report("Tied scores on a single metric with domain [0, 100], rescaling enabled") {
            for ((alternatives, score) in TIED_CASES) {
                appendLine("[$alternatives alternative(s) all scoring ${num(score)}]")
                appendLine("  " + describeTiedOutcome(alternatives, score))
                appendLine()
            }
        }

    /**
     *  Builds a one-metric model in which every alternative ties, and describes what the engine
     *  does with it — including failing, which is one of the outcomes being recorded.
     */
    private fun describeTiedOutcome(alternativeCount: Int, score: Double): String {
        val metric = Metric("Tied", Interval(0.0, 100.0))
        val model = AdditiveMODAModel(mapOf(metric to LinearValueFunction()), name = "tied")
        val alternatives = (1..alternativeCount).associate { "Alt$it" to listOf(Score(metric, score)) }
        return try {
            model.defineAlternatives(alternatives)
            val values = model.alternatives.sorted().joinToString(", ") { "$it=${num(model.multiObjectiveValue(it))}" }
            val warnings = model.warnings.joinToString("; ") { it::class.simpleName ?: "?" }
            "declared=${interval(metric.domain)}, effective=${interval(model.effectiveDomainOf(metric))}" +
                    "; overall value: $values" +
                    "; warnings: ${warnings.ifEmpty { "none" }}"
        } catch (e: Exception) {
            "threw ${e::class.simpleName}: ${e.message}"
        }
    }

    // ----------------------------------------------------------------------------------------
    // Canonical rendering
    // ----------------------------------------------------------------------------------------

    /**
     *  Renders a model in a fixed order. Alternatives are sorted by name so that map iteration
     *  order cannot leak into the fixture; metrics keep declaration order, because that order is
     *  itself part of the model's contract.
     *
     *  The domain printed here is whichever domain the model actually transforms scores against,
     *  which is the property the engine work must preserve.
     */
    private fun StringBuilder.appendModel(model: AdditiveMODAModel) {
        appendLine("[metrics]")
        for (metric in model.metrics) {
            // Both domains are recorded. 'declared' is what the caller supplied and must never be
            // modified by evaluating a model; 'effective' is what the value functions were applied
            // over and is what explains the values below.
            appendLine(
                "  ${metric.name}: direction=${metric.direction}" +
                        ", declared=${interval(metric.domain)}" +
                        ", effective=${interval(model.effectiveDomainOf(metric))}" +
                        ", weight=${num(model.weights[metric] ?: Double.NaN)}" +
                        ", adjustLower=${metric.allowLowerLimitAdjustment}" +
                        ", adjustUpper=${metric.allowUpperLimitAdjustment}"
            )
        }
        appendLine()
        appendLine("[raw scores and values by alternative]")
        for (alternative in model.alternatives.sorted()) {
            val values = model.valuesByAlternative(alternative)
            val rendered = model.metrics.joinToString(", ") { metric ->
                "${metric.name}: value=${num(values[metric] ?: Double.NaN)}"
            }
            appendLine("  $alternative -> $rendered")
        }
        appendLine()
        appendLine("[overall value by alternative]")
        for (alternative in model.alternatives.sorted()) {
            appendLine("  $alternative = ${num(model.multiObjectiveValue(alternative))}")
        }
        appendLine()
        appendLine("[recommendation order, best first]")
        val ordered = model.alternatives
            .map { it to model.multiObjectiveValue(it) }
            // Sort by value descending, then by name, so ties cannot reorder between runs.
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
        appendLine("  " + ordered.joinToString(" > ") { it.first })
        appendLine()
        appendLine("[first rank counts]")
        for ((alternative, count) in model.alternativeFirstRankCounts(false).sortedBy { it.first }) {
            appendLine("  $alternative = $count")
        }
        appendLine()
        appendLine("[average ranking]")
        for ((alternative, average) in model.alternativeAverageRanking(false).sortedBy { it.first }) {
            appendLine("  $alternative = ${num(average)}")
        }
    }

    private fun report(title: String, body: StringBuilder.() -> Unit): String =
        StringBuilder().apply {
            appendLine("# $title")
            appendLine()
            body()
        }.toString()

    private fun interval(interval: Interval): String =
        "[${num(interval.lowerLimit)}, ${num(interval.upperLimit)}]"

    /**
     *  Nine decimals is tight enough that any real behaviour change shows, and loose enough that
     *  last-bit floating point noise does not. The named cases keep sentinel values readable
     *  rather than rendering them as a wall of digits.
     */
    private fun num(value: Double): String = when {
        value.isNaN() -> "NaN"
        value == Double.MAX_VALUE -> "MAX_VALUE"
        value == -Double.MAX_VALUE -> "-MAX_VALUE"
        value == Double.POSITIVE_INFINITY -> "+Infinity"
        value == Double.NEGATIVE_INFINITY -> "-Infinity"
        else -> String.format(Locale.ROOT, "%.9f", value)
    }

    // ----------------------------------------------------------------------------------------
    // Comparison against the recorded fixture
    // ----------------------------------------------------------------------------------------

    private fun assertMatchesBaseline(name: String, actual: String) {
        val normalized = normalize(actual)
        if (System.getProperty(RECORD_PROPERTY) == "true") {
            val path = Path.of("src", "test", "resources", RESOURCE_DIR, "baseline-$name.txt")
            Files.createDirectories(path.parent)
            Files.writeString(path, normalized + "\n")
            return
        }
        val resource = javaClass.getResourceAsStream("/$RESOURCE_DIR/baseline-$name.txt")
            ?: fail(
                "The MODA baseline '$name' has not been recorded. Re-run with -D$RECORD_PROPERTY=true " +
                        "and commit src/test/resources/$RESOURCE_DIR/baseline-$name.txt.\n" +
                        "--- generated ---\n$normalized"
            )
        val expected = normalize(resource.readBytes().toString(Charsets.UTF_8))
        assertEquals(
            expected, normalized,
            "The MODA engine baseline '$name' changed. If the change is intended, re-record with " +
                    "-D$RECORD_PROPERTY=true and commit the fixture alongside the change."
        )
    }

    /** Line endings and trailing whitespace are not part of what is being recorded. */
    private fun normalize(text: String): String =
        text.replace("\r\n", "\n").trimEnd().lines().joinToString("\n") { it.trimEnd() }

    companion object {
        private const val RECORD_PROPERTY = "moda.baseline.record"
        private const val RESOURCE_DIR = "moda"

        private val ANALYZER_ALTERNATIVES = listOf("Alpha", "Beta", "Gamma")
        private val ANALYZER_RESPONSES = listOf("Cost", "Delay")
        private const val ANALYZER_REPLICATIONS = 5

        /**
         *  The tied cases the defect report distinguishes: three or more tied alternatives take the
         *  range-estimate path, one or two take the floor/ceiling path, and within that path an
         *  integral score behaves differently from a fractional one.
         */
        private val TIED_CASES = listOf(
            1 to 7.0,
            2 to 7.0,
            2 to 7.5,
            3 to 7.0,
            5 to 7.0,
            3 to 0.0
        )
    }
}
