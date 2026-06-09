package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Regression guard for the Tier A2 capability-marker refactor: if
 * someone removes one of the capability interfaces from a canonical
 * implementer the runtime-type checks in the cloner and the network
 * carrier will silently change behavior. The compiler's
 * "always true" inference on the `is` checks here IS the guard —
 * the checks are tautological exactly because the markers are
 * declared on the concrete types.
 */
@Suppress("USELESS_IS_CHECK")
class CapabilityMarkersTest {

    @Test
    fun `DemandGenerator implements ExternalDemandConsumer`() {
        val sc = SupplyChainModel(Model("Cap.DG"))
        val item = ItemType(sc, name = "A")
        val dg = DemandGenerator(
            supplyChainModel = sc,
            itemType = item,
            timeUntilFirstRV = ConstantRV(1.0),
            timeBtwEventsRV = ConstantRV(1.0),
            name = "DG",
        )
        assertTrue(dg is ExternalDemandConsumer)
    }

    @Test
    fun `LeadTimeDemandFiller implements ExternalDemandSupplier`() {
        val sc = SupplyChainModel(Model("Cap.LT"))
        val ltf = LeadTimeDemandFiller(sc, name = "LT")
        assertTrue(ltf is ExternalDemandSupplier)
    }
}
