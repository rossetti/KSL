package ksl.service.capability.run

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import ksl.app.config.ExperimentRunOverrides
import ksl.app.config.ModelReference
import ksl.app.config.OutputConfig
import ksl.app.config.RunConfiguration
import ksl.app.config.RunConfigurationJson
import ksl.app.config.ScenarioSpec
import ksl.app.session.RunEvent
import ksl.app.session.RunResult
import ksl.service.job.JobManager
import ksl.service.store.ArtifactStore
import ksl.service.store.ResultStore
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Directly exercises the extracted async run-lifecycle orchestrator: submit -> poll -> Ready (with
 * store-on-completion) and the exact-hit cache short-circuit. Incremental top-up is covered by
 * IncrementalEquivalenceTest and end-to-end by the MCP suite.
 */
class RunApplicationServiceTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun mm1(reps: Int) = RunConfiguration(
        scenarios = listOf(
            ScenarioSpec(
                name = "run",
                modelReference = ModelReference.ByProviderId("MM1"),
                runOverrides = ExperimentRunOverrides(numberOfReplications = reps),
            ),
        ),
        outputConfig = OutputConfig(reports = emptySet()),
    )

    @Test
    @DisplayName("submit -> poll -> Ready stores the result; an identical submit is a cache hit")
    fun submitPollStoreAndCacheHit(@TempDir tmp: Path) = runBlocking {
        val registry = TestBundles.registry()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runService = RunService.fromRegistry(registry)
        try {
            val runApp = RunApplicationService(
                registry,
                runService,
                JobManager(scope, 4),
                ResultStore(tmp.resolve("results")),
                ArtifactStore(tmp.resolve("artifacts")),
                json,
            )

            val config = mm1(reps = 3)
            val key = ResultKeys.forRunConfig(config, CacheVersion.forRun(registry, config))
            val request = json.parseToJsonElement(RunConfigurationJson.encode(config))

            // First submit runs (useCache=false avoids any cross-test store contamination).
            val started = runApp.submitRun(config, key, request, useCache = false)
            assertIs<RunSubmitOutcome.Started>(started)

            val ready = withTimeout(60.seconds) {
                var outcome = runApp.getRunResult(started.jobId)
                while (outcome is RunResultOutcome.Running) {
                    delay(50)
                    outcome = runApp.getRunResult(started.jobId)
                }
                outcome
            }
            assertIs<RunResultOutcome.Ready>(ready)
            assertEquals(key, ready.cached.stored.resultId, "stored under its content key")

            // Store-on-completion happened, so an identical submit is now an exact cache hit.
            val cached = runApp.submitRun(config, key, request, useCache = true)
            assertIs<RunSubmitOutcome.AlreadyCached>(cached)
            assertEquals(key, cached.resultId)
        } finally {
            runService.close()
            scope.cancel()
            registry.close()
        }
    }

    @Test
    @DisplayName("cancel of an unknown job is a clean NotRunning, not an error")
    fun cancelUnknownIsNotRunning(@TempDir tmp: Path) {
        val registry = TestBundles.registry()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runService = RunService.fromRegistry(registry)
        try {
            val runApp = RunApplicationService(
                registry,
                runService,
                JobManager<RunEvent, RunResult>(scope, 4),
                ResultStore(tmp.resolve("r")),
                ArtifactStore(tmp.resolve("a")),
                json,
            )
            assertIs<RunCancelOutcome.NotRunning>(runApp.cancelRun("no-such-job", "x"))
        } finally {
            runService.close()
            scope.cancel()
            registry.close()
        }
    }
}
