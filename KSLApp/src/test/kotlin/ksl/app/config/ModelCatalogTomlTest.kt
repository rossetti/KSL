package ksl.app.config

import ksl.simulation.ModelCatalog
import ksl.simulation.NominatedInput
import ksl.simulation.NominatedInputKind
import ksl.simulation.NominatedOutput
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Round-trip and tidiness tests for the [ModelCatalogToml] codec. */
class ModelCatalogTomlTest {

    private val sample = ModelCatalog(
        nominatedInputs = listOf(
            NominatedInput(
                key = "MM1Queue.numServers",
                kind = NominatedInputKind.NUMERIC_CONTROL,
                displayName = "Number of Servers",
                unit = "servers",
            ),
            NominatedInput(
                key = "MM1Queue:ServiceTime.mean",
                kind = NominatedInputKind.RV_PARAMETER,
                displayName = "Mean Service Time",
                unit = "min",
            ),
        ),
        nominatedOutputs = listOf(
            NominatedOutput(name = "systemTime", displayName = "Avg Time in System", unit = "min"),
            NominatedOutput(name = "numInSystem"), // all optional fields null
        ),
    )

    @Test
    fun `round trips through TOML`() {
        val back = ModelCatalogToml.decode(ModelCatalogToml.encode(sample))
        assertEquals(sample, back)
    }

    @Test
    fun `omits null optional fields (explicitNulls = false)`() {
        val toml = ModelCatalogToml.encode(sample)
        // No entry sets a description, and nothing should be written as an explicit null.
        // Match a TOML key line (not the header prose, which mentions "description").
        val descriptionKey = Regex("""(?m)^\s*description\s*=""")
        assertFalse(toml.contains("= null"), "explicitNulls=false should omit null fields entirely")
        assertFalse(descriptionKey.containsMatchIn(toml), "unset optional fields must not be emitted as keys")
    }

    @Test
    fun `empty catalog round trips to empty`() {
        val back = ModelCatalogToml.decode(ModelCatalogToml.encode(ModelCatalog()))
        assertTrue(back.isEmpty)
    }
}
