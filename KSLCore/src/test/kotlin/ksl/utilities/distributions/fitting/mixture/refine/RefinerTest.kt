package ksl.utilities.distributions.fitting.mixture.refine

import ksl.utilities.distributions.fitting.estimators.NormalMLEParameterEstimator
import ksl.utilities.distributions.fitting.mixture.AdmissibilityCertificate
import ksl.utilities.distributions.fitting.mixture.ComponentFitCache
import ksl.utilities.distributions.fitting.mixture.DataPartition
import ksl.utilities.distributions.fitting.mixture.PDFComponentFitter
import ksl.utilities.random.rvariable.NormalRV
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RefinerTest {

    private fun fitter() = ComponentFitCache(PDFComponentFitter(setOf(NormalMLEParameterEstimator)))

    /** Two well separated normals, deliberately partitioned at the wrong place. */
    private fun twoNormals(seed: Int = 21): Pair<DoubleArray, AdmissibilityCertificate> {
        val left = NormalRV(0.0, 1.0, streamNum = seed).sample(400)
        val right = NormalRV(8.0, 1.0, streamNum = seed + 1).sample(400)
        val data = (left + right).sortedArray()
        return data to AdmissibilityCertificate(data, 5, 2)
    }

    @Test
    fun `no refinement returns the initial partition untouched`() {
        val (data, cert) = twoNormals()
        val initial = DataPartition(data.size, intArrayOf(200))
        val result = NoRefinement().refine(data, initial, cert, fitter())
        assertEquals(initial, result.partition)
        assertEquals(RefinementOutcome.NOT_ATTEMPTED, result.outcome)
        assertEquals(0, result.numIterations)
    }

    @Test
    fun `classification refinement moves a badly placed cut toward the true split`() {
        val (data, cert) = twoNormals()
        // the true split is at 400; start well away from it
        val initial = DataPartition(data.size, intArrayOf(200))
        val result = ClassificationEMRefiner().refine(data, initial, cert, fitter())
        val cut = result.partition.cutPositions[0]
        assertNotEquals(200, cut, "the refiner should have moved the cut")
        assertTrue(
            abs(cut - 400) < 40,
            "the cut should land near the true split at 400, it was $cut"
        )
    }

    @Test
    fun `the classification objective never decreases across accepted iterations`() {
        val (data, cert) = twoNormals(31)
        val initial = DataPartition(data.size, intArrayOf(150))
        val result = ClassificationEMRefiner().refine(data, initial, cert, fitter())
        assertTrue(result.objectiveTrace.size >= 1)
        assertTrue(
            result.isMonotone,
            "the objective decreased along the trace: ${result.objectiveTrace}"
        )
        for (i in 1 until result.objectiveTrace.size) {
            assertTrue(
                result.objectiveTrace[i] > result.objectiveTrace[i - 1],
                "accepted steps must strictly improve: ${result.objectiveTrace}"
            )
        }
    }

    @Test
    fun `classification refinement terminates and reports why`() {
        val (data, cert) = twoNormals(41)
        val initial = DataPartition(data.size, intArrayOf(320))
        val result = ClassificationEMRefiner(maxIterations = 50).refine(data, initial, cert, fitter())
        assertTrue(
            result.outcome in setOf(
                RefinementOutcome.CONVERGED,
                RefinementOutcome.NO_IMPROVEMENT,
                RefinementOutcome.OBJECTIVE_UNAVAILABLE
            ),
            "it should stop for a stated reason, not by exhausting iterations: ${result.outcome}"
        )
        assertTrue(result.numIterations <= 50)
    }

    @Test
    fun `a partition already at the optimum is left unchanged`() {
        val (data, cert) = twoNormals(51)
        val once = ClassificationEMRefiner()
            .refine(data, DataPartition(data.size, intArrayOf(250)), cert, fitter())
        // refining the result again must not move it
        val twice = ClassificationEMRefiner().refine(data, once.partition, cert, fitter())
        assertEquals(
            once.partition, twice.partition,
            "a fixed point must be stable under a second refinement"
        )
    }

    @Test
    fun `a single group is returned immediately`() {
        val (data, cert) = twoNormals()
        val single = DataPartition.single(data.size)
        val result = ClassificationEMRefiner().refine(data, single, cert, fitter())
        assertEquals(single, result.partition)
        assertEquals(RefinementOutcome.CONVERGED, result.outcome)
    }

    @Test
    fun `the guard rejection count is reported`() {
        val (data, cert) = twoNormals(61)
        val refiner = ClassificationEMRefiner()
        refiner.refine(data, DataPartition(data.size, intArrayOf(200)), cert, fitter())
        assertTrue(refiner.guardRejections >= 0, "the count must be available after a run")
    }

    @Test
    fun `break shift refinement improves or preserves its criterion`() {
        val (data, cert) = twoNormals(71)
        val initial = DataPartition(data.size, intArrayOf(300))
        val result = BreakShiftRefiner(maxShift = 20, maxIterations = 10)
            .refine(data, initial, cert, fitter())
        assertTrue(result.objectiveTrace.isNotEmpty())
        // the criterion is smaller-is-better, so the trace must not increase
        for (i in 1 until result.objectiveTrace.size) {
            assertTrue(
                result.objectiveTrace[i] < result.objectiveTrace[i - 1],
                "accepted moves must strictly improve: ${result.objectiveTrace}"
            )
        }
    }

    @Test
    fun `every refiner returns a partition satisfying the certificate`() {
        val (data, cert) = twoNormals(81)
        val initial = DataPartition(data.size, intArrayOf(250))
        val refiners = listOf(
            NoRefinement(),
            ClassificationEMRefiner(),
            BreakShiftRefiner(maxShift = 10, maxIterations = 5)
        )
        for (refiner in refiners) {
            val result = refiner.refine(data, initial, cert, fitter())
            val p = result.partition
            for (c in p.cutPositions) {
                assertTrue(cert.isAdmissibleCut(c), "${refiner.name} produced an inadmissible cut")
            }
            for (g in 0 until p.numGroups) {
                assertTrue(
                    cert.isValidGroup(p.startIndex(g), p.endIndex(g)),
                    "${refiner.name} produced an invalid group $g"
                )
            }
        }
    }

    @Test
    fun `refinement recovers an overlapping mixture better than the initial partition`() {
        // components one standard deviation apart overlap substantially
        val left = NormalRV(0.0, 1.0, streamNum = 91).sample(500)
        val right = NormalRV(3.0, 1.0, streamNum = 92).sample(500)
        val data = (left + right).sortedArray()
        val cert = AdmissibilityCertificate(data, 5, 2)
        val initial = DataPartition(data.size, intArrayOf(250))
        val result = ClassificationEMRefiner().refine(data, initial, cert, fitter())
        val cut = result.partition.cutPositions[0]
        assertTrue(
            abs(cut - 500) < abs(250 - 500),
            "refinement should move the cut toward the true split at 500, it reached $cut"
        )
    }
}
