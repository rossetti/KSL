package ksl.app.config

import ksl.controls.experiments.ExperimentRunParameters
import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.Model
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Phase 5.85 Step 3.5 acceptance: domain invariants for `ksl.app.config`
 * data classes (excluding the `optimization` sub-package, which is covered
 * by [ksl.app.config.optimization.OptimizationInvariantsTest]).
 *
 * Each test asserts that constructing a `@Serializable` data class with a
 * domain-violating value throws [IllegalArgumentException].
 */
class ConfigInvariantsTest {

    private fun runParameters(): ExperimentRunParameters =
        Model("MM1", autoCSVReports = false).also { model ->
            GIGcQueue(model, numServers = 1, name = "MM1Queue")
            model.numberOfReplications = 1
            model.lengthOfReplication = 100.0
        }.extractRunParameters()

    // ── ModelReference.ByProviderId ──────────────────────────────────────────

    @Test fun `ByProviderId rejects blank providerId`() {
        assertThrows<IllegalArgumentException> { ModelReference.ByProviderId("") }
        assertThrows<IllegalArgumentException> { ModelReference.ByProviderId("   ") }
    }

    // ── ModelReference.ByJar ─────────────────────────────────────────────────

    @Test fun `ByJar rejects blank jarPath`() {
        assertThrows<IllegalArgumentException> { ModelReference.ByJar(jarPath = "") }
    }
    @Test fun `ByJar rejects blank builderClassName when non-null`() {
        assertThrows<IllegalArgumentException> {
            ModelReference.ByJar(jarPath = "/tmp/m.jar", builderClassName = "")
        }
    }

    // ── RVParameterOverride ──────────────────────────────────────────────────

    @Test fun `RVParameterOverride rejects blank rvName or paramName`() {
        assertThrows<IllegalArgumentException> {
            RVParameterOverride(rvName = "", paramName = "mean", value = 1.0)
        }
        assertThrows<IllegalArgumentException> {
            RVParameterOverride(rvName = "rv", paramName = " ", value = 1.0)
        }
    }
    @Test fun `RVParameterOverride rejects non-finite value`() {
        assertThrows<IllegalArgumentException> {
            RVParameterOverride(rvName = "rv", paramName = "mean", value = Double.NaN)
        }
        assertThrows<IllegalArgumentException> {
            RVParameterOverride(rvName = "rv", paramName = "mean", value = Double.POSITIVE_INFINITY)
        }
    }

    // ── ScenarioSpec ─────────────────────────────────────────────────────────

    @Test fun `ScenarioSpec rejects blank name`() {
        assertThrows<IllegalArgumentException> {
            ScenarioSpec(name = "", runParameters = runParameters())
        }
    }
    @Test fun `ScenarioSpec rejects blank modelConfiguration keys`() {
        assertThrows<IllegalArgumentException> {
            ScenarioSpec(
                name = "s",
                runParameters = runParameters(),
                modelConfiguration = mapOf("" to "v")
            )
        }
    }

    // ── TracingConfig ────────────────────────────────────────────────────────

    @Test fun `TracingConfig rejects blank animationTraceFile when non-null`() {
        assertThrows<IllegalArgumentException> {
            TracingConfig(animationTraceFile = "")
        }
    }
    @Test fun `TracingConfig rejects non-positive flushEveryNEvents`() {
        assertThrows<IllegalArgumentException> {
            TracingConfig(flushEveryNEvents = 0)
        }
        assertThrows<IllegalArgumentException> {
            TracingConfig(flushEveryNEvents = -10)
        }
    }

    // ── ModelRunTemplate ─────────────────────────────────────────────────────

    @Test fun `ModelRunTemplate rejects blank modelConfiguration keys`() {
        assertThrows<IllegalArgumentException> {
            ModelRunTemplate(
                modelReference = ModelReference.ByProviderId("MM1"),
                runParameters = runParameters(),
                modelConfiguration = mapOf(" " to "v")
            )
        }
    }

    // ── RunConfiguration ─────────────────────────────────────────────────────

    @Test fun `RunConfiguration rejects duplicate scenario names`() {
        assertThrows<IllegalArgumentException> {
            RunConfiguration(
                modelReference = ModelReference.ByProviderId("MM1"),
                experimentRunParameters = runParameters(),
                scenarios = listOf(
                    ScenarioSpec(name = "s", runParameters = runParameters()),
                    ScenarioSpec(name = "s", runParameters = runParameters())
                )
            )
        }
    }
}
