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
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Top-level fixture element (not nested, so the controls reflection layer can
 * reach the public setters) exposing one control per family, a named random
 * variable, a response, and a counter.
 */
class CatalogServer(parent: ModelElement, name: String) : ModelElement(parent, name) {

    @set:KSLControl(controlType = ControlType.INTEGER, lowerBound = 1.0, upperBound = 10.0)
    var numServers: Int = 1

    @set:KSLStringControl(allowedValues = ["FCFS", "PRIORITY"])
    var policy: String = "FCFS"

    @set:KSLJsonControl(comment = "weights")
    var weights: List<Double> = listOf(1.0, 2.0)

    val serviceRV = RandomVariable(this, ExponentialRV(2.0, 1), "ServiceTimeRV")

    private val mySystemTime = Response(this, "System Time")
    val systemTime: ResponseCIfc get() = mySystemTime

    private val myThroughput = Counter(this, "Throughput")
    val throughput: CounterCIfc get() = myThroughput
}

class ModelCatalogTest {

    private fun fixture(serverName: String = "Server"): Pair<Model, CatalogServer> {
        val model = Model("CatalogTestModel", autoCSVReports = false)
        val server = CatalogServer(model, serverName)
        return model to server
    }

    // ── Happy path: kinds and order ──────────────────────────────────────────

    @Test
    fun `nominate by string key populates the catalog with correct kinds and order`() {
        val (model, _) = fixture()
        model.nominate {
            input("Server.numServers")
            input("Server.policy")
            input("Server.weights")
            rvParameter("ServiceTimeRV", "mean")
            output("System Time")
            output("Throughput")
        }
        val cat = model.modelCatalog
        assertNotNull(cat)
        assertEquals(
            listOf(
                "Server.numServers" to NominatedInputKind.NUMERIC_CONTROL,
                "Server.policy" to NominatedInputKind.STRING_CONTROL,
                "Server.weights" to NominatedInputKind.JSON_CONTROL,
                "ServiceTimeRV.mean" to NominatedInputKind.RV_PARAMETER,
            ),
            cat.nominatedInputs.map { it.key to it.kind }
        )
        assertEquals(listOf("System Time", "Throughput"), cat.nominatedOutputs.map { it.name })
    }

    // ── Instance overloads ───────────────────────────────────────────────────

    @Test
    fun `nominate by object reference derives keys and names`() {
        val (model, server) = fixture()
        val numServersControl = model.controls().control("Server.numServers")
        assertNotNull(numServersControl)
        model.nominate {
            input(numServersControl)                       // ControlIfc instance
            input(server, "policy")                        // element + property name
            input(server, CatalogServer::weights)          // element + property reference
            rvParameter(server.serviceRV, "mean")          // RandomVariableCIfc
            output(server.systemTime)                      // ResponseCIfc
            output(server.throughput)                      // CounterCIfc
        }
        val cat = model.modelCatalog!!
        assertEquals(
            listOf("Server.numServers", "Server.policy", "Server.weights", "ServiceTimeRV.mean"),
            cat.nominatedInputs.map { it.key }
        )
        assertEquals(NominatedInputKind.NUMERIC_CONTROL, cat.nominatedInputs[0].kind)
        assertEquals(NominatedInputKind.STRING_CONTROL, cat.nominatedInputs[1].kind)
        assertEquals(NominatedInputKind.JSON_CONTROL, cat.nominatedInputs[2].kind)
        assertEquals(listOf("System Time", "Throughput"), cat.nominatedOutputs.map { it.name })
    }

    @Test
    fun `lean metadata is carried through`() {
        val (model, server) = fixture()
        model.nominate {
            output(server.systemTime) {
                displayName = "Avg Time in System"; description = "mean sojourn time"; unit = "min"
            }
        }
        val out = model.modelCatalog!!.nominatedOutputs.single()
        assertEquals("System Time", out.name)
        assertEquals("Avg Time in System", out.displayName)
        assertEquals("mean sojourn time", out.description)
        assertEquals("min", out.unit)
    }

    // ── Fail-fast validation ─────────────────────────────────────────────────

    @Test
    fun `unknown control key throws with a did-you-mean suggestion`() {
        val (model, _) = fixture()
        val ex = assertFailsWith<IllegalArgumentException> {
            model.nominate { input("Server.numServer") }   // missing trailing 's'
        }
        assertTrue(ex.message!!.contains("Server.numServers"), "should suggest the real key: ${ex.message}")
    }

    @Test
    fun `unknown rv parameter and response throw`() {
        val (model, _) = fixture()
        assertFailsWith<IllegalArgumentException> { model.nominate { rvParameter("ServiceTimeRV", "men") } }
        val ex = assertFailsWith<IllegalArgumentException> { model.nominate { output("System Tim") } }
        assertTrue(ex.message!!.contains("System Time"), "should suggest the real response: ${ex.message}")
    }

    @Test
    fun `duplicate nomination throws`() {
        val (model, _) = fixture()
        assertFailsWith<IllegalArgumentException> {
            model.nominate { input("Server.numServers"); input("Server.numServers") }
        }
    }

    @Test
    fun `an instance from a different model is rejected`() {
        val (modelA, _) = fixture("Server")
        val (modelB, _) = fixture("OtherServer")
        val foreignControl = modelB.controls().control("OtherServer.numServers")
        assertNotNull(foreignControl)
        assertFailsWith<IllegalArgumentException> {
            modelA.nominate { input(foreignControl) }   // "OtherServer.numServers" not in modelA
        }
    }

    // ── tryNominate (non-throwing) ───────────────────────────────────────────

    @Test
    fun `tryNominate applies valid nominations and collects problems`() {
        val (model, _) = fixture()
        val result = model.tryNominate {
            output("System Time")
            output("Nope")
            input("Server.numServers")
            input("bad.key")
        }
        assertFalse(result.isValid)
        assertEquals(2, result.problems.size)
        val cat = model.modelCatalog!!
        assertEquals(listOf("Server.numServers"), cat.nominatedInputs.map { it.key })
        assertEquals(listOf("System Time"), cat.nominatedOutputs.map { it.name })
    }

    // ── Descriptor integration & serialization ───────────────────────────────

    @Test
    fun `a model that never nominates yields a null catalog`() {
        val (model, _) = fixture()
        assertNull(model.modelCatalog)
        assertNull(model.modelDescriptor().catalog)
    }

    @Test
    fun `descriptor carries the catalog and round-trips through JSON`() {
        val (model, server) = fixture()
        model.nominate {
            input(server, CatalogServer::numServers) { unit = "servers" }
            rvParameter(server.serviceRV, "mean") { unit = "min" }
            output(server.systemTime) { displayName = "Avg Time in System"; unit = "min" }
        }
        val descriptor = model.modelDescriptor()
        assertNotNull(descriptor.catalog)
        assertFalse(descriptor.catalog!!.isEmpty)

        val json = Json { allowSpecialFloatingPointValues = true; encodeDefaults = true }
        val restored = json.decodeFromString(ModelDescriptor.serializer(), descriptor.toJson())
        assertEquals(descriptor.catalog, restored.catalog)
    }
}
