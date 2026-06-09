package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TimeBasedShippingMultiEchelonNetworkTest {

    @Test
    fun `each IHP gets its own TimeBasedDemandCarrier`() {
        val sc = SupplyChainModel(Model("TBS.PerIHP"))
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        net.addItemType("A", ConstantRV(1.0))
        val p = net.addInventoryHoldingPoint("P")
        val c = net.addInventoryHoldingPoint("C")
        val pCarrier = p.demandCarrier
        val cCarrier = c.demandCarrier
        assertTrue(pCarrier is TimeBasedDemandCarrier)
        assertTrue(cCarrier is TimeBasedDemandCarrier)
        assertFalse(pCarrier === cCarrier, "each IHP must have its own carrier")
    }

    @Test
    fun `attachIHPToSupplier with non-null transport time configures sender`() {
        val sc = SupplyChainModel(Model("TBS.Attach"))
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        net.addItemType("A", ConstantRV(1.0))
        val p = net.addInventoryHoldingPoint("P")
        val c = net.addInventoryHoldingPoint("C")
        net.attachIHPToExternalSupplier(p, ConstantRV(0.5))
        net.attachIHPToSupplier(p, c, ConstantRV(0.25))
        assertSame(p, c.demandFiller)
        assertTrue(net.isCustomer(p, c))
    }

    @Test
    fun `allowExternalDemandGenerators flips flags on every IHP carrier`() {
        val sc = SupplyChainModel(Model("TBS.AllowExt"))
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        net.addItemType("A", ConstantRV(1.0))
        val p = net.addInventoryHoldingPoint("P")
        net.attachIHPToExternalSupplier(p, ConstantRV(0.5))
        // initial: false
        assertFalse((p.demandCarrier as TimeBasedDemandCarrier).immediateTransportFlag)
        net.allowExternalDemandGenerators()
        assertTrue((p.demandCarrier as TimeBasedDemandCarrier).immediateTransportFlag)
        // IHPs added after the call also inherit the flag.
        val c = net.addInventoryHoldingPoint("C")
        assertTrue((c.demandCarrier as TimeBasedDemandCarrier).immediateTransportFlag)
    }

    @Test
    fun `duplicate external-supplier attach rejected`() {
        val sc = SupplyChainModel(Model("TBS.Dup"))
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        net.addItemType("A", ConstantRV(1.0))
        val p = net.addInventoryHoldingPoint("P")
        net.attachIHPToExternalSupplier(p, ConstantRV(0.5))
        assertThrows<IllegalArgumentException> {
            net.attachIHPToExternalSupplier(p, ConstantRV(0.5))
        }
    }
}
