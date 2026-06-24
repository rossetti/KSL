package ksl.app.swing.bundle

import ksl.app.config.ModelReference
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecipeDraftTest {

    @Test
    fun `valid draft projects to a single-scenario run configuration`() {
        val draft = RecipeDraft(
            name = "light-load",
            numberOfReplications = 50,
            lengthOfReplication = 500.0,
            lengthOfReplicationWarmUp = 50.0,
            antithetic = true,
        )
        assertTrue(draft.validate().isEmpty())
        val config = draft.toRunConfiguration("test.bundle", "m")
        val scenario = config.scenarios.single()
        assertEquals("light-load", scenario.name)
        assertEquals(ModelReference.ByBundleAndModelId("test.bundle", "m"), scenario.modelReference)
        val overrides = scenario.runOverrides!!
        assertEquals(50, overrides.numberOfReplications)
        assertEquals(500.0, overrides.lengthOfReplication)
        assertEquals(true, overrides.antitheticOption)
    }

    @Test
    fun `an all-null override yields a scenario with no run overrides`() {
        val config = RecipeDraft(name = "defaults").toRunConfiguration("b", "m")
        assertEquals(null, config.scenarios.single().runOverrides, "empty overrides should collapse to null")
    }

    @Test
    fun `validation flags a blank name, bad numbers, and unsafe file names`() {
        assertTrue(RecipeDraft(name = "  ").validate().any { it.contains("blank") })
        assertTrue(RecipeDraft(name = "a/b").validate().any { it.contains("file name") })
        assertTrue(RecipeDraft(name = "ok", numberOfReplications = 0).validate().any { it.contains("Replications") })
        assertTrue(RecipeDraft(name = "ok", lengthOfReplication = 0.0).validate().any { it.contains("Length") })
        assertTrue(RecipeDraft(name = "ok", lengthOfReplicationWarmUp = -1.0).validate().any { it.contains("Warm-up") })
    }
}
