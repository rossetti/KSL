package ksl.simulation

import kotlinx.serialization.json.Json
import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.controls.KSLJsonControl
import ksl.controls.KSLStringControl
import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponse
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The recommended pattern: a top-level system element that curates from whole-system context. */
class TheSystem(parent: ModelElement, name: String = "TheSystem") : ModelElement(parent, name) {

    @set:KSLControl(controlType = ControlType.INTEGER, lowerBound = 1.0, upperBound = 10.0)
    var numServers: Int = 3

    @set:KSLStringControl(allowedValues = ["FCFS", "PRIORITY"])
    var policy: String = "FCFS"

    @set:KSLJsonControl(comment = "weights")
    var weights: List<Double> = listOf(1.0, 2.0)

    val serviceRV = RandomVariable(this, ExponentialRV(2.0, 1), "ServiceTimeRV")

    private val mySystemTime = Response(this, "System Time")
    val systemTime: ResponseCIfc get() = mySystemTime

    private val myThroughput = Counter(this, "Throughput")
    val throughput: CounterCIfc get() = myThroughput

    override fun specifyCatalog(catalog: ElementCatalogScope) = with(catalog) {
        input(this@TheSystem, TheSystem::numServers) { displayName = "Number of Servers"; unit = "servers" }
        input(this@TheSystem, TheSystem::policy)
        input(this@TheSystem, TheSystem::weights)
        rvParameter(serviceRV, "mean") { unit = "min" }
        output(mySystemTime) { displayName = "Avg Time in System"; unit = "min" }
        output(myThroughput)
    }
}

/** A fine-grained, reusable element that nominates itself — the flooding source. */
class WorkStation(parent: ModelElement, name: String) : ModelElement(parent, name) {
    private val myUtil = TWResponse(this, "$name:Util")
    val util: ResponseCIfc get() = myUtil
    override fun specifyCatalog(catalog: ElementCatalogScope) {
        catalog.output(myUtil) { displayName = "$name Utilization" }
    }
}

/** A subsystem holding several workstations — used to test subtree denomination. */
class StationBank(parent: ModelElement, name: String, n: Int) : ModelElement(parent, name) {
    val stations: List<WorkStation> = (1..n).map { WorkStation(this, "$name-WS$it") }
}

/** An element whose specifyCatalog contains a bad nomination — must not crash the model. */
class BadElement(parent: ModelElement, name: String) : ModelElement(parent, name) {
    private val myR = Response(this, "$name:R")
    val r: ResponseCIfc get() = myR
    override fun specifyCatalog(catalog: ElementCatalogScope) {
        catalog.output(myR)                  // valid
        catalog.output("NoSuchResponse")     // invalid -> warn + skip
    }
}

class ModelCatalogTest {

    // ── Roll-up from a system element ────────────────────────────────────────

    @Test
    fun `element specifyCatalog rolls up into the model catalog with correct kinds`() {
        val m = Model("M", autoCSVReports = false)
        TheSystem(m)
        val cat = m.modelCatalog
        assertNotNull(cat)
        assertEquals(
            listOf(
                "TheSystem.numServers" to NominatedInputKind.NUMERIC_CONTROL,
                "TheSystem.policy" to NominatedInputKind.STRING_CONTROL,
                "TheSystem.weights" to NominatedInputKind.JSON_CONTROL,
                "ServiceTimeRV.mean" to NominatedInputKind.RV_PARAMETER,
            ),
            cat.nominatedInputs.map { it.key to it.kind }
        )
        assertEquals(listOf("System Time", "Throughput"), cat.nominatedOutputs.map { it.name })
        assertEquals("Number of Servers", cat.nominatedInputs[0].displayName)
        assertEquals("min", cat.nominatedOutputs[0].unit)
    }

    @Test
    fun `a model with no specifyCatalog overrides has a null catalog`() {
        val m = Model("M", autoCSVReports = false)
        assertNull(m.modelCatalog)
        assertNull(m.modelDescriptor().catalog)
    }

    // ── Flooding & pruning ───────────────────────────────────────────────────

    @Test
    fun `reused elements flood the catalog and a subtree can be pruned`() {
        val m = Model("M", autoCSVReports = false)
        TheSystem(m)
        val bank = StationBank(m, "Bank", 5)
        // 5 station utilizations + the system's 2 outputs
        assertEquals(7, m.modelCatalog!!.nominatedOutputs.size)

        m.curateCatalog { denominateSubtree(bank) }
        assertEquals(listOf("System Time", "Throughput"), m.modelCatalog!!.nominatedOutputs.map { it.name })
    }

    @Test
    fun `denominateAllFrom removes only one element's contribution`() {
        val m = Model("M", autoCSVReports = false)
        val bank = StationBank(m, "Bank", 2)
        m.curateCatalog { denominateAllFrom(bank.stations[0]) }
        val names = m.modelCatalog!!.nominatedOutputs.map { it.name }
        assertFalse("Bank-WS1:Util" in names)
        assertTrue("Bank-WS2:Util" in names)
    }

    @Test
    fun `clearElementNominations then re-curate yields only the curated items`() {
        val m = Model("M", autoCSVReports = false)
        val sys = TheSystem(m)
        StationBank(m, "Bank", 3)
        m.curateCatalog {
            clearElementNominations()
            output(sys.systemTime) { displayName = "Only This" }
        }
        val cat = m.modelCatalog!!
        assertEquals(listOf("System Time"), cat.nominatedOutputs.map { it.name })
        assertEquals("Only This", cat.nominatedOutputs[0].displayName)
        assertTrue(cat.nominatedInputs.isEmpty())
    }

    @Test
    fun `denominate by predicate drops a whole category`() {
        val m = Model("M", autoCSVReports = false)
        TheSystem(m)
        m.curateCatalog { denominateInputs { it.kind == NominatedInputKind.RV_PARAMETER } }
        val cat = m.modelCatalog!!
        assertTrue(cat.nominatedInputs.none { it.kind == NominatedInputKind.RV_PARAMETER })
        assertEquals(3, cat.nominatedInputs.size)
    }

    // ── Override precedence (model wins) ─────────────────────────────────────

    @Test
    fun `model curation overrides an element's nomination metadata`() {
        val m = Model("M", autoCSVReports = false)
        val sys = TheSystem(m)
        m.curateCatalog { output(sys.systemTime) { displayName = "Relabelled" } }
        val outs = m.modelCatalog!!.nominatedOutputs
        assertEquals(1, outs.count { it.name == "System Time" })
        assertEquals("Relabelled", outs.first { it.name == "System Time" }.displayName)
    }

    // ── Validation asymmetry: element warn+skip, model throw ─────────────────

    @Test
    fun `an invalid element nomination is skipped and reported, not thrown`() {
        val m = Model("M", autoCSVReports = false)
        BadElement(m, "B")
        val cat = m.modelCatalog            // must not throw
        assertNotNull(cat)
        assertEquals(listOf("B:R"), cat.nominatedOutputs.map { it.name })
        assertTrue(m.catalogIssues().any { it.contains("NoSuchResponse") })
    }

    @Test
    fun `an invalid model curation throws when the catalog is assembled`() {
        val m = Model("M", autoCSVReports = false)
        TheSystem(m)
        m.curateCatalog { output("NoSuchResponse") }
        val ex = assertFailsWith<IllegalArgumentException> { m.modelCatalog }
        assertTrue(ex.message!!.contains("NoSuchResponse"))
    }

    // ── Descriptor integration & serialization ───────────────────────────────

    @Test
    fun `descriptor carries the rolled-up catalog and round-trips through JSON`() {
        val m = Model("M", autoCSVReports = false)
        TheSystem(m)
        val descriptor = m.modelDescriptor()
        assertNotNull(descriptor.catalog)
        assertFalse(descriptor.catalog!!.isEmpty)

        val json = Json { allowSpecialFloatingPointValues = true; encodeDefaults = true }
        val restored = json.decodeFromString(ModelDescriptor.serializer(), descriptor.toJson())
        assertEquals(descriptor.catalog, restored.catalog)
    }
}
