package ksl.app.swing.bundle

import ksl.modeling.variable.Counter
import ksl.modeling.variable.RandomVariable
import ksl.simulation.Model
import ksl.simulation.ModelCatalog
import ksl.simulation.ModelDescriptor
import ksl.simulation.NominatedInputKind
import ksl.simulation.NominatedOutput
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogDraftTest {

    private fun plainDescriptor(): ModelDescriptor {
        val model = Model("draftTest")
        Counter(model, name = "served")
        RandomVariable(model, ExponentialRV(2.0), name = "Demand")
        return model.modelDescriptor()
    }

    private fun curatedDescriptor(): ModelDescriptor {
        val model = Model("draftTest2")
        Counter(model, name = "served")
        // Catalogs are post-build data now: attach a ModelCatalog directly rather
        // than via a (removed) in-model DSL.
        return model.modelDescriptor().copy(
            catalog = ModelCatalog(
                nominatedOutputs = listOf(NominatedOutput("served", displayName = "Served", unit = "customers")),
            )
        )
    }

    @Test
    fun `from enumerates candidate inputs and outputs unnominated by default`() {
        val draft = CatalogDraft.from(plainDescriptor())
        assertTrue(draft.outputs.any { it.name == "served" }, "expected the counter as an output candidate")
        assertTrue(
            draft.inputs.any { it.kind == NominatedInputKind.RV_PARAMETER && it.key.contains("Demand") },
            "expected the RV parameter as an input candidate: ${draft.inputs}"
        )
        assertTrue(draft.outputs.none { it.nominated })
        assertTrue(draft.inputs.none { it.nominated })
    }

    @Test
    fun `from marks rows already in the descriptor catalog and features them first`() {
        val draft = CatalogDraft.from(curatedDescriptor())
        val served = draft.outputs.first { it.name == "served" }
        assertTrue(served.nominated)
        assertEquals("Served", served.displayName)
        assertEquals("customers", served.unit)
        assertEquals("served", draft.outputs.first().name, "nominated rows should be listed first")
    }

    @Test
    fun `nominating and editing projects to a catalog`() {
        val draft = CatalogDraft.from(plainDescriptor())
            .withOutputNominated("served", true)
            .withOutputMetadata("served", displayName = "Customers Served", description = null, unit = "customers")
        val catalog = draft.toCatalog()
        val out = catalog.nominatedOutputs.single()
        assertEquals("served", out.name)
        assertEquals("Customers Served", out.displayName)
        assertEquals("customers", out.unit)
    }

    @Test
    fun `blank metadata is normalized to null in the projected catalog`() {
        val draft = CatalogDraft.from(plainDescriptor())
            .withOutputNominated("served", true)
            .withOutputMetadata("served", displayName = "  ", description = "", unit = null)
        val out = draft.toCatalog().nominatedOutputs.single()
        assertEquals(null, out.displayName)
        assertEquals(null, out.description)
    }

    @Test
    fun `editing metadata on an unnominated row auto-features it`() {
        val draft = CatalogDraft.from(plainDescriptor())
            .withOutputMetadata("served", displayName = "Served", description = null, unit = null)
        assertTrue(draft.outputs.first { it.name == "served" }.nominated, "labelling should feature the row")
        assertEquals("served", draft.toCatalog().nominatedOutputs.single().name)
    }

    @Test
    fun `blank-only metadata does not feature a row`() {
        val draft = CatalogDraft.from(plainDescriptor())
            .withOutputMetadata("served", displayName = "  ", description = "", unit = null)
        assertFalse(draft.outputs.first { it.name == "served" }.nominated)
        assertTrue(draft.toCatalog().nominatedOutputs.isEmpty())
    }

    @Test
    fun `swapping two featured outputs re-orders the projected catalog`() {
        val model = Model("swapTest")
        Counter(model, name = "a")
        Counter(model, name = "b")
        val draft = CatalogDraft.from(model.modelDescriptor())
            .withOutputNominated("a", true)
            .withOutputNominated("b", true)
        val before = draft.toCatalog().nominatedOutputs.map { it.name }
        val after = draft.swapOutputs(before[0], before[1]).toCatalog().nominatedOutputs.map { it.name }
        assertEquals(before.reversed(), after, "swap should exchange the two outputs' catalog order")
    }

    @Test
    fun `nominateAll then clear toggles every row`() {
        val all = CatalogDraft.from(plainDescriptor()).nominateAll()
        assertTrue(all.inputs.all { it.nominated } && all.outputs.all { it.nominated })
        val cleared = all.clearNominations()
        assertFalse(cleared.toCatalog().let { it.nominatedInputs.isNotEmpty() || it.nominatedOutputs.isNotEmpty() })
    }
}
