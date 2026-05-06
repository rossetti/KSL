package ksl.app

import kotlinx.coroutines.runBlocking
import ksl.app.session.KSLRuntimeError
import ksl.app.session.RunEvent
import ksl.app.session.RunResult
import ksl.app.session.RunWarningType
import ksl.examples.general.appsession.runKSLAppSessionSmokeDemo
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KSLAppSessionSmokeDemoTest {

    @Test
    fun `app session smoke demo exercises app-facing workflow`() = runBlocking {
        val report = runKSLAppSessionSmokeDemo { }

        assertIs<RunResult.Completed>(report.successfulSingleRun.result)
        assertTrue(report.successfulSingleRun.events.any { it is RunEvent.RunStarted })
        assertTrue(report.successfulSingleRun.events.any { it is RunEvent.ReplicationEnded })
        assertTrue(report.successfulSingleRun.events.any { it is RunEvent.RunCompleted })

        assertIs<RunResult.Completed>(report.warningSingleRun.result)
        val warningIndex = report.warningSingleRun.events.indexOfFirst {
            it is RunEvent.RunWarning &&
                it.warning is RunWarningType.ConfigurationWarnings
        }
        val startedIndex = report.warningSingleRun.events.indexOfFirst {
            it is RunEvent.RunStarted
        }
        assertTrue(warningIndex >= 0)
        assertTrue(startedIndex >= 0)
        assertTrue(warningIndex < startedIndex)

        val invalidResult = report.invalidSingleRun.result
        assertIs<RunResult.Failed>(invalidResult)
        assertIs<KSLRuntimeError.ConfigurationError>(invalidResult.error)

        val cancelledResult = report.cancelledSingleRun.result
        assertIs<RunResult.Cancelled>(cancelledResult)
        assertEquals("Demo cancellation", cancelledResult.reason)

        val scenarioResult = report.scenarioRun.result
        assertIs<RunResult.BatchCompleted>(scenarioResult)
        assertEquals(2, scenarioResult.snapshots.size)
        assertEquals(0, scenarioResult.summary.failedItems)
        assertTrue(report.scenarioRun.events.any { it is RunEvent.ScenarioCompleted })
    }

    @Test
    fun `app session smoke demo does not import low-level orchestrators`() {
        val repoRoot = File(System.getProperty("user.dir")).parentFile
        val demoSource = repoRoot.resolve(
            "KSLExamples/src/main/kotlin/ksl/examples/general/appsession/KSLAppSessionSmokeDemo.kt"
        ).readText()

        assertTrue("ksl.app.orchestrator" !in demoSource)
    }
}
