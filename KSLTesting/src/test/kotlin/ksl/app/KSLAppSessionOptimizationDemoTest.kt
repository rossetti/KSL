package ksl.app

import kotlinx.coroutines.runBlocking
import ksl.app.session.RunEvent
import ksl.app.session.RunResult
import ksl.examples.general.appsession.runKSLAppSessionOptimizationDemo
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Acceptance tests for the optimization demo.  Mirrors the structure of
 * [KSLAppSessionSmokeDemoTest]: one test exercises the demo and checks
 * the lifecycle/result shape; a second test asserts the demo file does
 * not import any low-level orchestrator (the app-session boundary check).
 */
class KSLAppSessionOptimizationDemoTest {

    @Test
    fun `app session optimization demo exercises optimization workflow`() = runBlocking {
        val report = runKSLAppSessionOptimizationDemo { }

        val result = report.optimizationRun.result
        assertIs<RunResult.OptimizationCompleted>(result)
        assertTrue(result.iterationHistory.isNotEmpty())
        assertEquals(0, result.summary.failedItems)

        // OptimizationOrchestrator's lifecycle is iteration-based: it does not
        // emit RunStarted/ReplicationEnded (those belong to per-replication
        // orchestrators).  The demo therefore observes IterationCompleted
        // followed by the terminal RunCompleted.
        val events = report.optimizationRun.events
        assertTrue(events.any { it is RunEvent.IterationCompleted },
            "expected at least one IterationCompleted event")
        assertTrue(events.any { it is RunEvent.RunCompleted },
            "expected RunCompleted terminal event")
    }

    @Test
    fun `app session optimization demo does not import low-level orchestrators`() {
        val repoRoot = File(System.getProperty("user.dir")).parentFile
        val demoSource = repoRoot.resolve(
            "KSLExamples/src/main/kotlin/ksl/examples/general/appsession/KSLAppSessionOptimizationDemo.kt"
        ).readText()

        assertTrue("ksl.app.orchestrator" !in demoSource,
            "Demo source must not import any orchestrator from ksl.app.orchestrator; " +
                "the optimization path goes through KSLAppSession.")
    }
}
