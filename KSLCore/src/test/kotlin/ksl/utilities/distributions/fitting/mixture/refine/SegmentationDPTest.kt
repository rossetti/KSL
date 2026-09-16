package ksl.utilities.distributions.fitting.mixture.refine

import ksl.utilities.distributions.fitting.mixture.AdmissibilityCertificate
import ksl.utilities.distributions.fitting.mixture.DataPartition
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  Cross-validates the Kotlin segmentation against the Python verification worksheet.
 *
 *  The worksheet in `verify/` was written before this implementation, in a different language,
 *  and was itself checked against exhaustive enumeration. Its outputs are frozen into a test
 *  resource so that this implementation is validated against an independent artifact rather than
 *  against a second implementation by the same author. That distinction is the whole reason the
 *  worksheet exists: three earlier statements of the proposition were wrong, and each was caught
 *  by execution rather than by reading.
 *
 *  Regenerate the resource with the generator recorded in the plan when the specification
 *  changes; do not edit it by hand.
 */
class SegmentationDPTest {

    private data class OracleCase(
        val x: DoubleArray,
        val scores: Array<DoubleArray>,
        val k: Int,
        val m: Int,
        val mu: Int,
        val status: String,
        val value: Double?,
        val cuts: IntArray?
    )

    private fun loadOracle(): List<OracleCase> {
        val text = checkNotNull(
            javaClass.classLoader.getResourceAsStream("segmentation-oracle.json")
        ) { "the oracle resource is missing" }.bufferedReader().readText()
        return parse(text)
    }

    /** A minimal reader for the flat structure the generator writes. */
    private fun parse(text: String): List<OracleCase> {
        val cases = mutableListOf<OracleCase>()
        // objects are separated at top level by "}," followed by a newline and "{"
        val objects = Regex("\\{[^{}]*(?:\\[[^\\]]*\\][^{}]*)*\\}").findAll(text)
        for (match in objects) {
            val body = match.value
            val x = numberList(field(body, "x")).toDoubleArray()
            val sRaw = field(body, "s")
            val rows = Regex("\\[[^\\[\\]]*\\]").findAll(sRaw).map { it.value }.toList()
            val scores = Array(rows.size) { numberList(rows[it]).toDoubleArray() }
            val k = scalar(body, "k").toInt()
            val m = scalar(body, "m").toInt()
            val mu = scalar(body, "mu").toInt()
            val status = Regex("\"status\":\\s*\"([A-Z]+)\"").find(body)!!.groupValues[1]
            val valueText = Regex("\"value\":\\s*(\"-inf\"|null|-?[0-9.eE+-]+)")
                .find(body)!!.groupValues[1]
            val value = when (valueText) {
                "\"-inf\"" -> Double.NEGATIVE_INFINITY
                "null" -> null
                else -> valueText.toDouble()
            }
            val cutsText = Regex("\"cuts\":\\s*(null|\\[[^\\]]*\\])").find(body)!!.groupValues[1]
            val cuts = if (cutsText == "null") null else
                numberList(cutsText).map { it.toInt() }.toIntArray()
            cases.add(OracleCase(x, scores, k, m, mu, status, value, cuts))
        }
        return cases
    }

    private fun field(body: String, name: String): String {
        val i = body.indexOf("\"$name\":")
        val start = body.indexOf('[', i)
        var depth = 0
        var j = start
        while (j < body.length) {
            if (body[j] == '[') depth++
            if (body[j] == ']') {
                depth--
                if (depth == 0) break
            }
            j++
        }
        return body.substring(start, j + 1)
    }

    private fun scalar(body: String, name: String): Double =
        Regex("\"$name\":\\s*(-?[0-9.eE+-]+)").find(body)!!.groupValues[1].toDouble()

    private fun numberList(text: String): List<Double> {
        return Regex("(\"-inf\"|-?\\d+\\.?\\d*(?:[eE][+-]?\\d+)?)").findAll(text)
            .map { if (it.value == "\"-inf\"") Double.NEGATIVE_INFINITY else it.value.toDouble() }
            .toList()
    }

    @Test
    fun `agrees with the python oracle on status, value and the exact cut tuple`() {
        val cases = loadOracle()
        assertTrue(cases.size > 300, "expected the full oracle, got ${cases.size}")
        var feasible = 0
        var infeasible = 0
        var negativeInfinite = 0
        for ((index, c) in cases.withIndex()) {
            val certificate = AdmissibilityCertificate(c.x, c.m, c.mu)
            val result = SegmentationDP(certificate).maximize(c.scores, c.k)
            val context = "case $index: x=${c.x.toList()} k=${c.k} m=${c.m} mu=${c.mu}"
            assertEquals(c.status, result.status.name, "status differs for $context")
            if (c.status == "INFEASIBLE") {
                infeasible++
                continue
            }
            feasible++
            val expected = c.value!!
            if (expected == Double.NEGATIVE_INFINITY) {
                negativeInfinite++
                assertEquals(Double.NEGATIVE_INFINITY, result.value, "value differs for $context")
            } else {
                assertEquals(expected, result.value, 1e-9, "value differs for $context")
            }
            val partition = result.partition
            assertNotNull(partition, "a feasible case must yield a partition: $context")
            assertContentEquals(
                c.cuts, partition.cutPositions,
                "cut tuple differs for $context"
            )
        }
        assertTrue(feasible > 100, "too few feasible cases exercised: $feasible")
        assertTrue(infeasible > 50, "too few infeasible cases exercised: $infeasible")
        assertTrue(
            negativeInfinite > 50,
            "too few feasible-but-negative-infinite cases exercised: $negativeInfinite"
        )
    }

    // ------------------------------------------------------------------ named regressions

    @Test
    fun `a feasible set whose every partition scores negative infinity still returns one`() {
        val x = doubleArrayOf(1.0, 2.0)
        val scores = arrayOf(
            doubleArrayOf(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY),
            doubleArrayOf(0.0, 0.0)
        )
        val result = SegmentationDP(AdmissibilityCertificate(x, 1, 1)).maximize(scores, 2)
        assertEquals(SegmentationStatus.FEASIBLE, result.status)
        assertEquals(Double.NEGATIVE_INFINITY, result.value)
        assertNotNull(result.partition)
        assertContentEquals(intArrayOf(1), result.partition.cutPositions)
    }

    @Test
    fun `the minimum distinct value requirement is enforced by the recursion`() {
        val x = doubleArrayOf(0.0, 0.0, 1.0, 2.0, 3.0, 4.0)
        val scores = arrayOf(DoubleArray(6), DoubleArray(6))
        val lax = SegmentationDP(AdmissibilityCertificate(x, 2, 1)).maximize(scores, 2)
        val strict = SegmentationDP(AdmissibilityCertificate(x, 2, 2)).maximize(scores, 2)
        assertContentEquals(intArrayOf(2), lax.partition!!.cutPositions)
        assertContentEquals(
            intArrayOf(3), strict.partition!!.cutPositions,
            "with two distinct values required, the first group cannot be the tied pair"
        )
    }

    @Test
    fun `the minimum group size is enforced inside the recursion, not after it`() {
        val x = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0)
        val scores = arrayOf(
            doubleArrayOf(100.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            doubleArrayOf(0.0, 100.0, 0.0, 0.0, 0.0, 0.0),
            doubleArrayOf(0.0, 0.0, 100.0, 100.0, 100.0, 100.0)
        )
        val result = SegmentationDP(AdmissibilityCertificate(x, 2, 1)).maximize(scores, 3)
        // the unconstrained optimum is 600 at cuts (1,2), which is infeasible at m=2
        assertEquals(300.0, result.value, 1e-9)
        assertContentEquals(intArrayOf(2, 4), result.partition!!.cutPositions)
    }

    @Test
    fun `a cut never splits a run of equal values`() {
        val x = doubleArrayOf(0.0, 0.0, 0.0, 1.0, 2.0, 2.0)
        val scores = arrayOf(
            doubleArrayOf(9.0, 9.0, 0.0, 0.0, 0.0, 0.0),
            doubleArrayOf(0.0, 0.0, 9.0, 9.0, 9.0, 9.0)
        )
        val certificate = AdmissibilityCertificate(x, 1, 1)
        val result = SegmentationDP(certificate).maximize(scores, 2)
        for (c in result.partition!!.cutPositions) {
            assertTrue(certificate.isAdmissibleCut(c), "cut $c splits a tied block")
        }
    }

    @Test
    fun `positive infinity and not-a-number scores are rejected as a precondition`() {
        val x = doubleArrayOf(1.0, 2.0, 3.0)
        val dp = SegmentationDP(AdmissibilityCertificate(x, 1, 1))
        assertFailsWith<IllegalArgumentException> {
            dp.maximize(
                arrayOf(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(Double.POSITIVE_INFINITY, 0.0, 0.0)),
                2
            )
        }
        assertFailsWith<IllegalArgumentException> {
            dp.maximize(
                arrayOf(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(Double.NaN, 0.0, 0.0)),
                2
            )
        }
    }

    // ---------------------------------------------------------------- incumbent retention

    @Test
    fun `an incumbent that also attains the optimum is retained`() {
        val x = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        val scores = arrayOf(DoubleArray(4), DoubleArray(4))   // every partition scores zero
        val dp = SegmentationDP(AdmissibilityCertificate(x, 1, 1))
        val canonical = dp.maximize(scores, 2).partition!!
        assertContentEquals(intArrayOf(1), canonical.cutPositions, "smallest cut is canonical")
        val kept = dp.maximize(scores, 2, incumbent = DataPartition(4, intArrayOf(3))).partition!!
        assertContentEquals(
            intArrayOf(3), kept.cutPositions,
            "an equally good incumbent must be retained so the loop cannot cycle"
        )
    }

    @Test
    fun `an incumbent is retained when every feasible partition scores negative infinity`() {
        val x = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        val scores = arrayOf(
            DoubleArray(4) { Double.NEGATIVE_INFINITY },
            DoubleArray(4)
        )
        val dp = SegmentationDP(AdmissibilityCertificate(x, 1, 1))
        val result = dp.maximize(scores, 2, incumbent = DataPartition(4, intArrayOf(3)))
        assertEquals(Double.NEGATIVE_INFINITY, result.value)
        assertContentEquals(
            intArrayOf(3), result.partition!!.cutPositions,
            "all feasible partitions are optimal here, so the incumbent must survive"
        )
    }

    @Test
    fun `an inadmissible incumbent is ignored rather than returned`() {
        val x = doubleArrayOf(0.0, 0.0, 1.0, 2.0)
        val scores = arrayOf(DoubleArray(4), DoubleArray(4))
        val dp = SegmentationDP(AdmissibilityCertificate(x, 1, 1))
        // cut 1 splits the tied zeros and is outside the feasible set
        val result = dp.maximize(scores, 2, incumbent = DataPartition(4, intArrayOf(1)))
        val cuts = result.partition!!.cutPositions
        assertTrue(
            cuts.none { it == 1 },
            "an incumbent violating the constraints must not be returned, got ${cuts.toList()}"
        )
    }

    @Test
    fun `an incumbent with the wrong number of groups is ignored`() {
        val x = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        val scores = arrayOf(DoubleArray(4), DoubleArray(4))
        val dp = SegmentationDP(AdmissibilityCertificate(x, 1, 1))
        val result = dp.maximize(scores, 2, incumbent = DataPartition(4, intArrayOf(1, 2)))
        assertEquals(2, result.partition!!.numGroups)
    }

    // ------------------------------------------------------------------------- scaling

    @Test
    fun `the work grows linearly in the number of observations`() {
        // The class earns its keep by being proportional to the number of observations rather than
        // to its square, and that rests entirely on the sliding window: each candidate predecessor
        // is admitted once per group and evicted at most once, with no backward scan. Swap the
        // window for a scan over all predecessors and every correctness test here still passes
        // while the refiner silently becomes quadratic.
        //
        // Counted, not timed. The version of this test that measured elapsed seconds passed alone
        // and failed on a loaded machine, which told us about the build agent rather than about
        // the code.
        val sizes = intArrayOf(4000, 8000, 16000)
        val work = LongArray(sizes.size)
        for ((index, n) in sizes.withIndex()) {
            val x = DoubleArray(n) { it.toDouble() }
            val scores = Array(4) { g -> DoubleArray(n) { i -> ((i * 7 + g * 13) % 100) / 10.0 } }
            val dp = SegmentationDP(AdmissibilityCertificate(x, 5, 2))
            assertEquals(0L, dp.numTransitionsExamined, "the counter must start at zero")
            dp.maximize(scores, 4)
            work[index] = dp.numTransitionsExamined
        }
        assertTrue(
            work.all { it > 0 },
            "the counter is not being incremented, so this test cannot see a regression: " +
                    "${work.toList()}"
        )
        val slope = (kotlin.math.ln(work.last().toDouble()) - kotlin.math.ln(work.first().toDouble())) /
                (kotlin.math.ln(sizes.last().toDouble()) - kotlin.math.ln(sizes.first().toDouble()))
        assertTrue(
            slope < 1.2,
            "the predecessor search looks superlinear: log-log slope $slope over ${work.toList()}"
        )
    }

    @Test
    fun `the transition count is reset by each call rather than accumulated`() {
        // Otherwise the growth test above would read the sum of everything the instance had ever
        // done, and would pass on a quadratic algorithm called once.
        val x = DoubleArray(600) { it.toDouble() }
        val scores = Array(3) { g -> DoubleArray(600) { i -> ((i * 7 + g * 13) % 100) / 10.0 } }
        val dp = SegmentationDP(AdmissibilityCertificate(x, 5, 2))
        dp.maximize(scores, 3)
        val first = dp.numTransitionsExamined
        dp.maximize(scores, 3)
        assertEquals(
            first, dp.numTransitionsExamined,
            "the same problem must report the same work, not twice as much"
        )
    }
}
