package ksl.simulation

import ksl.examples.book.chapter4.DriveThroughPharmacyWithQ
import ksl.modeling.variable.TWResponse
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.statistic.BatchStatisticIfc
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertTrue

/**
 * Regression tests for StatisticalBatchingElement.
 *
 * The baseline DriveThroughPharmacyWithQ configuration mirrors EventModelingTest,
 * but replaces 5 shorter replications with one long replication using the same
 * total simulated time and post-warmup horizon.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StatisticalBatchingElementTest {

    companion object {
        private const val BASELINE_REPS = 5
        private const val BASELINE_LENGTH = 2_000.0
        private const val BASELINE_WARMUP = 500.0

        private const val BATCH_REPS = 1
        private const val BATCH_LENGTH = BASELINE_REPS * BASELINE_LENGTH
        private const val BATCH_WARMUP = BASELINE_REPS * BASELINE_WARMUP
        private const val BATCH_INTERVAL = 10.0

        private const val DTP_BATCH_SYSTEM_TIME_AVG = 5.738639506756251
        private const val DTP_BATCH_SYSTEM_TIME_VAR = 2.743602553213361
        private const val DTP_BATCH_PROB_GT4_AVG = 0.48671875000000003
        private const val DTP_BATCH_NUM_BUSY_AVG = 0.5054002602667899
        private const val DTP_BATCH_NUM_IN_SYSTEM_AVG = 0.9570963734448953
    }

    private lateinit var model: Model
    private lateinit var dtp: DriveThroughPharmacyWithQ
    private lateinit var batching: StatisticalBatchingElement

    @BeforeAll
    fun runSimulation() {
        model = Model("DTP-StatisticalBatching-Test")
        model.numberOfReplications = BATCH_REPS
        model.lengthOfReplication = BATCH_LENGTH
        model.lengthOfReplicationWarmUp = BATCH_WARMUP

        dtp = DriveThroughPharmacyWithQ(model, 1)
        dtp.arrivalGenerator.setInitialEventTimeProcesses(ExponentialRV(6.0, 1))
        dtp.serviceRV.initialRandomSource = ExponentialRV(3.0, 2)

        batching = model.statisticalBatching(BATCH_INTERVAL)
        model.simulate()
    }

    @Test
    fun statisticalBatchingCapturesExpectedResponseTypes() {
        val responseNames = batching.allResponseBatchStatisticsAsMap.keys.map { it.name }.toSet()
        val timeWeightedNames = batching.allTimeWeightedBatchStatisticsAsMap.keys.map { it.name }.toSet()

        assertTrue("System Time" in responseNames, "Expected System Time response batch statistic")
        assertTrue("SysTime >= 4 minutes" in responseNames, "Expected indicator response batch statistic")
        assertTrue("NumBusy" in timeWeightedNames, "Expected NumBusy time-weighted batch statistic")
        assertTrue("Num in System" in timeWeightedNames, "Expected Num in System time-weighted batch statistic")
    }

    @Test
    fun statisticalBatchingFacadeCapturesAllModelResponses() {
        val expectedResponses = model.responses
            .filter { it !is TWResponse }
            .toSet()
        val expectedTimeWeightedResponses = model.responses
            .filterIsInstance<TWResponse>()
            .toSet()

        assertEquals(expectedResponses, batching.allResponseBatchStatisticsAsMap.keys)
        assertEquals(expectedTimeWeightedResponses, batching.allTimeWeightedBatchStatisticsAsMap.keys)

        val batchedElementIds = batching.allResponseBatchStatisticsAsMap.keys.map { it.id }.toSet() +
            batching.allTimeWeightedBatchStatisticsAsMap.keys.map { it.id }.toSet()
        val counterElementIds = model.counters.map { it.id }.toSet()
        assertTrue(
            batchedElementIds.intersect(counterElementIds).isEmpty(),
            "StatisticalBatchingElement should batch responses and TWResponses, not counters"
        )
    }

    @Test
    fun capturedBatchStatisticsHaveUsableBatchMeans() {
        val allNamedStats = batching.allResponseBatchStatisticsAsMap.map { (response, stat) -> response.name to stat } +
            batching.allTimeWeightedBatchStatisticsAsMap.map { (response, stat) -> response.name to stat }

        assertTrue(allNamedStats.isNotEmpty(), "Expected statistical batching to capture at least one response")
        for ((name, stat) in allNamedStats) {
            assertUsableBatchStatistic(name, stat)
        }
    }

    @Test
    fun directLookupReturnsCapturedStatisticsAndObservers() {
        val systemTimeStat = batching.allResponseBatchStatisticsAsMap[dtp.systemTime]
        val numInSystemStat = batching.allTimeWeightedBatchStatisticsAsMap[dtp.numInSystem]

        assertNotNull(systemTimeStat, "Expected System Time batch statistic")
        assertNotNull(numInSystemStat, "Expected Num in System batch statistic")
        assertSame(systemTimeStat, batching.batchStatisticFor(dtp.systemTime))
        assertSame(numInSystemStat, batching.batchStatisticFor(dtp.numInSystem))

        assertNotNull(
            batching.batchStatisticObserverFor(dtp.systemTime),
            "Expected System Time batch statistic observer"
        )
        assertNotNull(
            batching.batchStatisticObserverFor(dtp.numInSystem),
            "Expected Num in System batch statistic observer"
        )
    }

    @Test
    fun statisticReporterContainsBatchStatistics() {
        val reporter = batching.statisticReporter
        val report = reporter.halfWidthSummaryReport().toString()

        assertEquals("Batch Summary Report", reporter.reportTitle)
        assertEquals(batching.allStatistics.size, reporter.statistics.size)
        assertTrue(report.contains("System Time"), "Expected System Time in batch report")
        assertTrue(report.contains("NumBusy"), "Expected NumBusy in batch report")
        assertTrue(report.contains("Num in System"), "Expected Num in System in batch report")
    }

    @Test
    fun driveThroughBatchStatisticsGoldenValues() {
        val systemTime = responseBatchStatistic("System Time")
        val probGt4 = responseBatchStatistic("SysTime >= 4 minutes")
        val numBusy = timeWeightedBatchStatistic("NumBusy")
        val numInSystem = timeWeightedBatchStatistic("Num in System")

        assertGolden(DTP_BATCH_SYSTEM_TIME_AVG, systemTime.average, "DTP_BATCH_SYSTEM_TIME_AVG")
        assertGolden(DTP_BATCH_SYSTEM_TIME_VAR, systemTime.variance, "DTP_BATCH_SYSTEM_TIME_VAR")
        assertGolden(DTP_BATCH_PROB_GT4_AVG, probGt4.average, "DTP_BATCH_PROB_GT4_AVG")
        assertGolden(DTP_BATCH_NUM_BUSY_AVG, numBusy.average, "DTP_BATCH_NUM_BUSY_AVG")
        assertGolden(DTP_BATCH_NUM_IN_SYSTEM_AVG, numInSystem.average, "DTP_BATCH_NUM_IN_SYSTEM_AVG")
    }

    private fun responseBatchStatistic(name: String): BatchStatisticIfc {
        return batching.allResponseBatchStatisticsAsMap.entries
            .first { it.key.name == name }
            .value
    }

    private fun timeWeightedBatchStatistic(name: String): BatchStatisticIfc {
        return batching.allTimeWeightedBatchStatisticsAsMap.entries
            .first { it.key.name == name }
            .value
    }

    private fun assertUsableBatchStatistic(name: String, stat: BatchStatisticIfc) {
        assertTrue(stat.totalNumberOfObservations > 0.0, "$name should have raw observations")
        assertTrue(stat.count > 0.0, "$name should have completed batch observations")
        assertTrue(stat.numBatches > 0, "$name should have completed batches")
        assertEquals(stat.numBatches, stat.batchMeans.size, "$name batch means should match numBatches")
        assertTrue(stat.batchMeans.all { it.isFinite() }, "$name batch means should be finite")
        assertTrue(stat.average.isFinite(), "$name batch average should be finite")
        assertTrue(stat.variance.isFinite(), "$name batch variance should be finite")
    }

    private fun assertGolden(expected: Double, actual: Double, name: String) {
        if (expected.isNaN()) {
            println("GOLDEN_DISCOVERY: $name = $actual")
            return
        }
        assertEquals(expected, actual, 0.0, "Golden regression failed for $name")
    }
}
