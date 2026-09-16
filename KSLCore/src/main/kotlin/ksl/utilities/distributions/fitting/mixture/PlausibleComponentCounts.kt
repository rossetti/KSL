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

/**
 *  The component counts a sample does not rule out.
 *
 *  **The answer in the analyst's own currency.** The rest of the workflow reports a count and then
 *  qualifies it: the criterion recommends five, the adequacy verdict says the sample cannot settle
 *  the question, the criteria disagree over an interval. None of that answers the question an
 *  analyst who arrived with a number actually has, which is whether their number survives. This
 *  does, by running the increment test at each count and collecting the ones that were not
 *  contradicted.
 *
 *  **A wide set is the honest outcome, not a failure of the method.** Where the sample cannot
 *  settle the count, very little is contradicted and the set is broad — which says the same thing
 *  `CountAdequacy` says, in a form the analyst can act on. The set is evidence about what the data
 *  rule out; it is not a ranking, and its smallest member is not a recommendation.
 *
 *  Four outcomes are kept apart rather than collapsed, because collapsing them would let one be
 *  read as another:
 *
 *  - **not rejected** — the test ran and the count survived
 *  - **rejected** — the test ran and an extra component bought more than chance
 *  - **inconclusive** — the test ran and could not say, through too few usable replicates
 *  - **not assessable** — no test could be formed, because the count or the one above it cannot be
 *    fitted to a sample of this size at all
 *
 *  Only the first is support. In particular a count nobody could evaluate is not evidence of
 *  plausibility, so inconclusive and not-assessable counts stay out of the set.
 *
 *  @param numComponentsConsidered every count the caller asked about, in ascending order
 *  @param tests the test at each count that could be tested
 *  @param level the level each verdict was read at
 */
class PlausibleComponentCounts(
    val numComponentsConsidered: List<Int>,
    val tests: Map<Int, ComponentCountTest>,
    val level: Double
) {

    init {
        require(numComponentsConsidered.isNotEmpty()) { "At least one count must be considered" }
        require(level > 0.0 && level < 1.0) { "The level must be strictly between 0 and 1" }
        require(tests.keys.all { it in numComponentsConsidered }) {
            "A test was supplied for a count that was not considered"
        }
    }

    private fun countsWith(evidence: CountEvidence): Set<Int> =
        numComponentsConsidered.filter { tests[it]?.verdict == evidence }.toSortedSet()

    /**
     *  The counts this sample does not rule out.
     */
    val notRejected: Set<Int>
        get() = countsWith(CountEvidence.NOT_CONTRADICTED)

    /**
     *  The counts contradicted by this sample.
     */
    val rejected: Set<Int>
        get() = countsWith(CountEvidence.CONTRADICTED)

    /**
     *  The counts whose test could not say either way.
     */
    val inconclusiveAt: Set<Int>
        get() = countsWith(CountEvidence.INCONCLUSIVE)

    /**
     *  The counts for which no test could be formed at all, because the count or the one above it
     *  is not admissible for a sample of this size.
     */
    val notAssessable: Set<Int>
        get() = numComponentsConsidered.filter { it !in tests }.toSortedSet()

    /**
     *  The smallest count not ruled out, or null when none survived.
     *
     *  **Not a recommendation.** It is the most parsimonious reading the data permit, which is a
     *  different claim from the most likely one, and reading it as a selection rule would rebuild
     *  the very thing this class exists to qualify.
     */
    val smallestNotRejected: Int?
        get() = notRejected.minOrNull()

    /**
     *  The largest count not ruled out, or null when none survived.
     */
    val largestNotRejected: Int?
        get() = notRejected.maxOrNull()

    /**
     *  Whether the surviving counts form an unbroken run.
     *
     *  Reported rather than assumed. Nothing about testing each count against the next guarantees
     *  the survivors are an interval, and quietly publishing the smallest and largest as a range
     *  would assert coverage of a middle count that may have been rejected.
     */
    val isContiguous: Boolean
        get() {
            val sorted = notRejected.toList()
            if (sorted.size <= 1) return true
            return sorted.last() - sorted.first() == sorted.size - 1
        }

    /**
     *  How many counts survived.
     */
    val size: Int
        get() = notRejected.size

    /**
     *  What must accompany any display of this set.
     */
    fun caveats(): List<String> = listOf(
        "These are the counts the sample does not rule out, not the counts it supports. A sample " +
                "too small to settle the question rules out very little.",
        "Each test is calibrated to this fitting procedure rather than to an ideal estimator, so " +
                "the set describes what this method can distinguish on these data.",
        "The smallest surviving count is the most parsimonious reading the data permit. It is not " +
                "a recommendation and not an estimate."
    ) + if (inconclusiveAt.isNotEmpty() || notAssessable.isNotEmpty()) {
        listOf(
            "Counts that could not be tested are absent from the set without being ruled out: " +
                    describeUntested() + "."
        )
    } else emptyList()

    private fun describeUntested(): String {
        val parts = mutableListOf<String>()
        if (inconclusiveAt.isNotEmpty()) {
            parts.add("${inconclusiveAt.joinToString(", ")} could not be decided")
        }
        if (notAssessable.isNotEmpty()) {
            parts.add("${notAssessable.joinToString(", ")} cannot be fitted to a sample this size")
        }
        return parts.joinToString("; ")
    }

    /**
     *  A sentence an analyst can read.
     */
    fun explain(): String {
        if (notRejected.isEmpty()) {
            return "No component count in ${numComponentsConsidered.first()} to " +
                    "${numComponentsConsidered.last()} survived at a level of $level. That is " +
                    "unusual and is more likely to mean the family catalog fits these data badly " +
                    "than that every count is wrong."
        }
        val listed = notRejected.joinToString(", ")
        val gap = if (isContiguous) "" else
            " The surviving counts are not an unbroken run, so this is a set rather than a range."
        return "At a level of $level these data do not rule out $listed " +
                "component${if (notRejected.size == 1) "" else "s"}. The most parsimonious " +
                "reading they permit is ${smallestNotRejected}.$gap"
    }

    override fun toString(): String = buildString {
        appendLine("Component counts not ruled out, at a level of $level")
        appendLine("-".repeat(72))
        appendLine("  %-6s %12s %12s %10s %s".format("k", "statistic", "p", "usable", "verdict"))
        for (k in numComponentsConsidered) {
            val t = tests[k]
            if (t == null) {
                appendLine("  %-6d %12s %12s %10s %s".format(k, "--", "--", "--", "not assessable"))
                continue
            }
            appendLine("  %-6d %12s %12s %10s %s".format(
                k,
                t.improvementStatistic?.let { "%.3f".format(it) } ?: "--",
                t.pValue?.let { "%.4f".format(it) } ?: "--",
                "${t.numUsable}/${t.numReplicates}",
                t.verdict))
        }
        appendLine()
        appendLine("  not rejected   ${if (notRejected.isEmpty()) "none" else notRejected.joinToString(", ")}")
        appendLine("  rejected       ${if (rejected.isEmpty()) "none" else rejected.joinToString(", ")}")
        if (inconclusiveAt.isNotEmpty()) appendLine("  inconclusive   ${inconclusiveAt.joinToString(", ")}")
        if (notAssessable.isNotEmpty()) appendLine("  not assessable ${notAssessable.joinToString(", ")}")
        appendLine("  contiguous     $isContiguous")
        appendLine()
        appendLine(explain())
        appendLine()
        for (c in caveats()) appendLine("  - $c")
    }
}
