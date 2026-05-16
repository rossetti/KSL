package ksl.controls

import kotlinx.serialization.json.Json
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies hierarchy-related metadata exposed by `ControlIfc`,
 * `StringControlIfc`, and `JsonControlIfc` and round-tripped
 * through their DTOs.  Covers:
 *
 *  - `parentElementName` / `parentElementId` / `parentElementType`
 *    populated from the model-element parent.
 *  - `elementPath` ordering (root-to-direct-parent, excluding
 *    the Model itself and the element holding the control).
 *  - DTO round-trip across both JSON and (transitively) any
 *    `@Serializable` codec — exercised here via the JSON path.
 *  - Backwards-compatible deserialization of old snapshots that
 *    pre-date the new fields.
 */
/** Top-level container.  No annotations; its job is to host a child container. */
class HierarchyTestSubsystem(parent: ModelElement, name: String) : ModelElement(parent, name)

/**
 * Grand-child of the model: exposes one control per family.  Top-level
 * (not nested) so the controls reflection layer can reach the public
 * setter via `KCallable.call`.
 */
class HierarchyTestServer(parent: ModelElement, name: String) : ModelElement(parent, name) {

    @set:KSLControl(controlType = ControlType.INTEGER, lowerBound = 1.0, upperBound = 10.0)
    var capacity: Int = 3

    @set:KSLStringControl(allowedValues = ["FCFS", "PRIORITY"])
    var policy: String = "FCFS"

    @set:KSLJsonControl(comment = "Per-class weights")
    var weights: List<Double> = listOf(1.0, 2.0, 3.0)
}

class ModelControlsHierarchyTest {

    private fun buildFixture(): Pair<Model, HierarchyTestServer> {
        val model = Model("HierarchyTestModel", autoCSVReports = false)
        val sub = HierarchyTestSubsystem(model, "SubsystemA")
        val server = HierarchyTestServer(sub, "ServerX")
        return model to server
    }

    // ── Direct property reads ──────────────────────────────────────────────

    @Test
    fun `numeric control surfaces parent metadata and elementPath`() {
        val (model, _) = buildFixture()
        val controls = model.controls()
        val capacity = controls.control("ServerX.capacity")
        assertNotNull(capacity)
        assertEquals("SubsystemA", capacity!!.parentElementName)
        assertEquals("HierarchyTestSubsystem",capacity.parentElementType)
        assertNotNull(capacity.parentElementId)
        assertEquals(listOf("SubsystemA"), capacity.elementPath)
    }

    @Test
    fun `string control surfaces parent metadata and elementPath`() {
        val (model, _) = buildFixture()
        val controls = model.controls()
        val policy = controls.stringControl("ServerX.policy")
        assertNotNull(policy)
        assertEquals("SubsystemA", policy!!.parentElementName)
        assertEquals("HierarchyTestSubsystem",policy.parentElementType)
        assertEquals(listOf("SubsystemA"), policy.elementPath)
    }

    @Test
    fun `json control surfaces parent metadata and elementPath`() {
        val (model, _) = buildFixture()
        val controls = model.controls()
        val weights = controls.jsonControl("ServerX.weights")
        assertNotNull(weights)
        assertEquals("SubsystemA", weights!!.parentElementName)
        assertEquals("HierarchyTestSubsystem",weights.parentElementType)
        assertEquals(listOf("SubsystemA"), weights.elementPath)
    }

    // ── Direct child of Model ──────────────────────────────────────────────

    @Test
    fun `control on a direct child of Model reports the Model as parent and empty elementPath`() {
        val model = Model("DirectChildModel", autoCSVReports = false)
        val server = HierarchyTestServer(model, "DirectServer")
        val capacity = model.controls().control("DirectServer.capacity")
        assertNotNull(capacity)
        assertEquals(model.name, capacity!!.parentElementName)
        // Model.modelDescriptor().elementType for the Model itself comes from `Model::class.simpleName`,
        // which is "Model".  We don't assert the exact string here in case the runtime returns a more
        // specific subtype — just verify it's populated.
        assertNotNull(capacity.parentElementType)
        assertEquals(emptyList(), capacity.elementPath)
    }

    // ── DTO round-trip ─────────────────────────────────────────────────────

    @Test
    fun `controlData carries the new fields into the DTO`() {
        val (model, _) = buildFixture()
        val dto = model.controls().controlData().first { it.keyName == "ServerX.capacity" }
        assertEquals("SubsystemA", dto.parentElementName)
        assertEquals("HierarchyTestSubsystem",dto.parentElementType)
        assertNotNull(dto.parentElementId)
        assertEquals(listOf("SubsystemA"), dto.elementPath)
    }

    @Test
    fun `stringControlData carries the new fields into the DTO`() {
        val (model, _) = buildFixture()
        val dto = model.controls().stringControlData().first { it.keyName == "ServerX.policy" }
        assertEquals("SubsystemA", dto.parentElementName)
        assertEquals(listOf("SubsystemA"), dto.elementPath)
    }

    @Test
    fun `jsonControlData carries the new fields into the DTO`() {
        val (model, _) = buildFixture()
        val dto = model.controls().jsonControlData().first { it.keyName == "ServerX.weights" }
        assertEquals("SubsystemA", dto.parentElementName)
        assertEquals(listOf("SubsystemA"), dto.elementPath)
    }

    @Test
    fun `ModelControlsExport round-trips through JSON with hierarchy info preserved`() {
        val (model, _) = buildFixture()
        val export = model.controls().exportAll()
        val json = Json { allowSpecialFloatingPointValues = true }
        val text = json.encodeToString(ModelControlsExport.serializer(), export)
        val decoded = json.decodeFromString(ModelControlsExport.serializer(), text)
        assertEquals(export, decoded)
        val numeric = decoded.numericControls.first { it.keyName == "ServerX.capacity" }
        assertEquals("SubsystemA", numeric.parentElementName)
        assertEquals(listOf("SubsystemA"), numeric.elementPath)
    }

    // ── Backwards compat: old snapshots without the new fields ─────────────

    @Test
    fun `old snapshot JSON missing the new fields deserializes with defaults`() {
        // Hand-written snapshot that pre-dates the hierarchy fields.  All four
        // new properties must populate with their @Serializable defaults.
        val legacy = """
            {
              "modelName": "Legacy",
              "numericControls": [{
                "controlType": "INTEGER",
                "value": 3.0,
                "keyName": "Legacy.value",
                "lowerBound": 0.0,
                "upperBound": 10.0,
                "elementName": "LegacyElement",
                "elementId": 1,
                "elementType": "LegacyType",
                "propertyName": "value",
                "comment": "",
                "modelName": "Legacy"
              }],
              "stringControls": [],
              "jsonControls": []
            }
        """.trimIndent()
        val export = Json { ignoreUnknownKeys = true }
            .decodeFromString(ModelControlsExport.serializer(), legacy)
        val data = export.numericControls.single()
        assertNull(data.parentElementName)
        assertNull(data.parentElementId)
        assertNull(data.parentElementType)
        assertTrue(data.elementPath.isEmpty())
    }
}
