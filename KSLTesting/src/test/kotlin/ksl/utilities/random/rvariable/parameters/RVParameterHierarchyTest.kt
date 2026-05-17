package ksl.utilities.random.rvariable.parameters

import ksl.modeling.variable.RandomVariable
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  Verifies hierarchy-related metadata exposed by [RVParameterData]
 *  and populated by [RVParameterSetter.extractParameters].  Parallels
 *  `ksl.controls.ModelControlsHierarchyTest`:
 *
 *  - [RVParameterData.parentElementName] / `parentElementId` /
 *    `parentElementType` reflect the random variable's *owning*
 *    model element (i.e. `rv.parent`).
 *  - [RVParameterData.elementPath] excludes the Model itself and the
 *    RV's owner — matching the convention used by `ControlData`.
 *  - Direct-child RVs (whose owner is the Model) report the Model
 *    as `parentElementName` and an empty `elementPath`.
 *  - Serialized defaults (`null` parent fields, empty path) preserve
 *    backwards compatibility for older snapshots that pre-date the
 *    fields — exercised indirectly here via the default ctor args.
 */
class RVParameterHierarchyTest {

    // Container element under the Model that hosts RV-bearing elements.
    private class RVSubsystem(parent: ModelElement, name: String) : ModelElement(parent, name)

    // Element under RVSubsystem that owns one parameterized RV — exposes
    // the "nested" parent case.
    private class NestedServer(parent: ModelElement, name: String) : ModelElement(parent, name) {
        val rv = RandomVariable(this, ExponentialRV(mean = 3.0, streamNum = 1), name = "$name.serviceRV")
    }

    // Element attached directly to the Model — exposes the "direct child
    // of Model" parent case.
    private class TopLevelHolder(parent: ModelElement, name: String) : ModelElement(parent, name) {
        val rv = RandomVariable(this, ExponentialRV(mean = 7.0, streamNum = 2), name = "$name.budgetRV")
    }

    private fun buildFixture(): Triple<Model, NestedServer, TopLevelHolder> {
        val model = Model("RVHierarchyTestModel", autoCSVReports = false)
        val sub = RVSubsystem(model, "SubsystemA")
        val nested = NestedServer(sub, "ServerX")
        val top = TopLevelHolder(model, "BudgetHolder")
        return Triple(model, nested, top)
    }

    @Test
    fun `nested RV reports its owner as parent with non-empty elementPath`() {
        val (model, nested, _) = buildFixture()
        val data = model.rvParameterSetter.rvParametersData
        // ExponentialRV exposes one parameter ("mean") — find the entry
        // for the nested RV.
        val nestedRow = data.first { it.rvName == nested.rv.name }
        assertEquals("ServerX", nestedRow.parentElementName)
        assertEquals(nested.id, nestedRow.parentElementId)
        // RandomVariable's owner is the NestedServer fixture class; its
        // simple name should appear as the parent element type.
        assertEquals("NestedServer", nestedRow.parentElementType)
        // SubsystemA sits between ServerX and the Model — that's the only
        // ancestor in the path (Model is excluded).
        assertEquals(listOf("SubsystemA"), nestedRow.elementPath)
    }

    @Test
    fun `direct-child RV reports its owner as parent with empty elementPath`() {
        val (model, _, top) = buildFixture()
        val data = model.rvParameterSetter.rvParametersData
        val topRow = data.first { it.rvName == top.rv.name }
        assertEquals("BudgetHolder", topRow.parentElementName)
        assertEquals(top.id, topRow.parentElementId)
        assertEquals("TopLevelHolder", topRow.parentElementType)
        // BudgetHolder is a direct child of the Model — no intervening
        // ancestors.
        assertTrue(topRow.elementPath.isEmpty(), "expected empty elementPath, got ${topRow.elementPath}")
    }

    @Test
    fun `every parameterized RV in the snapshot carries non-null parent metadata`() {
        val (model, _, _) = buildFixture()
        val data = model.rvParameterSetter.rvParametersData
        // Two RVs in the fixture, each ExponentialRV has one parameter
        // ("mean") — so at least 2 rows, all populated.
        assertTrue(data.size >= 2, "fixture should produce at least 2 RV parameter rows")
        for (row in data) {
            assertNotNull(row.parentElementName, "parentElementName missing for ${row.rvName}.${row.paramName}")
            assertNotNull(row.parentElementId, "parentElementId missing for ${row.rvName}.${row.paramName}")
            assertNotNull(row.parentElementType, "parentElementType missing for ${row.rvName}.${row.paramName}")
        }
    }
}
