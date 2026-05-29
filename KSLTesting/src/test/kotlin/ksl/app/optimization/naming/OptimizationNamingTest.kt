package ksl.app.optimization.naming

import ksl.app.config.ModelReference
import ksl.app.config.optimization.AlgorithmKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 *  Substrate-level tests for the pure naming helpers in
 *  [ksl.app.optimization.naming].  No `ModelDescriptor` fixture is
 *  built — every test passes `null` for descriptors so we cover
 *  the reference-derived fallback paths and the no-input cases.
 *  Descriptor-rich behaviour is exercised end-to-end by the
 *  controller-integration tests in KSLAppSwingSimopt.
 */
class OptimizationNamingTest {

    // ── deriveModelIdentifier ────────────────────────────────────

    @Test
    fun `deriveModelIdentifier returns null when both descriptor and reference are null`() {
        assertNull(deriveModelIdentifier(descriptor = null, modelReference = null))
    }

    @Test
    fun `deriveModelIdentifier formats ByBundleAndModelId as bundleId colon modelId`() {
        val id = deriveModelIdentifier(
            descriptor = null,
            modelReference = ModelReference.ByBundleAndModelId(
                bundleId = "ksl.examples.mm1", modelId = "MM1"
            )
        )
        assertEquals("ksl.examples.mm1:MM1", id)
    }

    @Test
    fun `deriveModelIdentifier returns providerId for ByProviderId`() {
        val id = deriveModelIdentifier(
            descriptor = null,
            modelReference = ModelReference.ByProviderId("LKModel")
        )
        assertEquals("LKModel", id)
    }

    // ── deriveProblemName ───────────────────────────────────────

    @Test
    fun `deriveProblemName uses explicit name when non-blank`() {
        val name = deriveProblemName(
            explicitProblemName = "MyCustomProblem",
            descriptor = null,
            modelReference = ModelReference.ByProviderId("Whatever")
        )
        assertEquals("MyCustomProblem", name)
    }

    @Test
    fun `deriveProblemName falls back to model-reference identifier when explicit name is blank`() {
        val name = deriveProblemName(
            explicitProblemName = "  ",
            descriptor = null,
            modelReference = ModelReference.ByProviderId("LKModel")
        )
        assertEquals("LKModel", name)
    }

    @Test
    fun `deriveProblemName falls back to Optimization when nothing else is available`() {
        val name = deriveProblemName(
            explicitProblemName = null,
            descriptor = null,
            modelReference = null
        )
        assertEquals("Optimization", name)
    }

    // ── deriveSolverName ────────────────────────────────────────

    @Test
    fun `deriveSolverName uses explicit name when non-blank`() {
        val name = deriveSolverName(
            explicitSolverName = "shc-baseline",
            algorithmKind = AlgorithmKind.STOCHASTIC_HILL_CLIMBING
        )
        assertEquals("shc-baseline", name)
    }

    @Test
    fun `deriveSolverName falls back to algorithm displayName when explicit is blank`() {
        val name = deriveSolverName(
            explicitSolverName = "  ",
            algorithmKind = AlgorithmKind.SIMULATED_ANNEALING
        )
        assertEquals("Simulated Annealing", name)
    }

    @Test
    fun `deriveSolverName returns null when no algorithm picked`() {
        val name = deriveSolverName(
            explicitSolverName = null,
            algorithmKind = null
        )
        assertNull(name)
    }
}
