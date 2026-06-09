package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TimeBasedNetworkShippingMultiEchelonNetworkTest {

    @Test
    fun `all IHPs share the network carrier`() {
        val sc = SupplyChainModel(Model("TBN.Share"))
        val carrier = TimeBasedNetworkDemandCarrier(sc, name = "Net.Carrier")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.NetworkTimeBased(carrier),
        )
        net.addItemType("A", ConstantRV(1.0))
        val p = net.addInventoryHoldingPoint("P")
        val c = net.addInventoryHoldingPoint("C")
        assertSame(carrier, p.demandCarrier)
        assertSame(carrier, c.demandCarrier)
    }

    @Test
    fun `attachIHPToSupplier registers a transport time on the shared carrier`() {
        val sc = SupplyChainModel(Model("TBN.Attach"))
        val carrier = TimeBasedNetworkDemandCarrier(sc, name = "Net.Carrier")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.NetworkTimeBased(carrier),
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
    fun `attachExternalDemandSender registers the sender on every known filler`() {
        val sc = SupplyChainModel(Model("TBN.Ext"))
        val carrier = TimeBasedNetworkDemandCarrier(sc, name = "Net.Carrier")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.NetworkTimeBased(carrier),
        )
        val item = net.addItemType("A", ConstantRV(1.0))
        // Build a 2-level tree so the carrier knows two fillers: ES and P.
        val p = net.addInventoryHoldingPoint("P")
        val c = net.addInventoryHoldingPoint("C")
        net.attachIHPToExternalSupplier(p, ConstantRV(0.25))
        net.attachIHPToSupplier(p, c, ConstantRV(0.25))

        val sender = object : DemandSenderIfc {
            override val id: Int = 0
            override val name: String = "Ext"
            override var label: String? = null
            override fun mightRequest(type: ItemType): Boolean = true
            override var demandFillerFinder: DemandFillerFinderIfc? = null
            override var demandFiller: DemandFillerIfc? = null
        }
        net.attachExternalDemandSender(sender)
        // The carrier permits shipping P → sender now that the sender
        // is registered as a no-delay destination on every filler.
        val d = sc.createDemand(item, 1)
        d.setDemandSender(sender)
        d.setFiller(p)
        assertTrue(carrier.canShip(d))
    }

    @Test
    fun `duplicate generator attach rejected`() {
        val sc = SupplyChainModel(Model("TBN.Dup"))
        val carrier = TimeBasedNetworkDemandCarrier(sc, name = "Net.Carrier")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.NetworkTimeBased(carrier),
        )
        val a = net.addItemType("A", ConstantRV(1.0))
        val p = net.addInventoryHoldingPoint("P")
        net.attachIHPToExternalSupplier(p)
        val dg = net.attachDemandGeneratorToIHP(p, a, ConstantRV(1.0), "DG")
        assertEquals(p, dg.demandFiller)
        assertThrows<IllegalArgumentException> {
            net.attachDemandGeneratorToIHP(p, dg)
        }
    }
}
