package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MultiEchelonNetworkTest {

    @Test
    fun `default carrier is NoDelayDemandCarrier`() {
        val sc = SupplyChainModel(Model("ME.Default"))
        val net = MultiEchelonNetwork(sc, name = "Net")
        assertSame(NoDelayDemandCarrier, net.demandCarrier)
    }

    @Test
    fun `addItemType registers a lead time on the external supplier`() {
        val sc = SupplyChainModel(Model("ME.Items"))
        val net = MultiEchelonNetwork(sc, name = "Net")
        val a = net.addItemType("A", ConstantRV(1.0))
        assertSame(a, net.getItemType(a.name))
    }

    @Test
    fun `attachIHPToExternalSupplier rejects duplicates and customers`() {
        val sc = SupplyChainModel(Model("ME.Attach"))
        val net = MultiEchelonNetwork(sc, name = "Net")
        val a = net.addItemType("A", ConstantRV(1.0))
        val parent = net.addInventoryHoldingPoint("Parent")
        val child = net.addInventoryHoldingPoint("Child")
        net.attachIHPToExternalSupplier(parent)
        assertTrue(net.isAttachedToExternalSupplier(parent))
        assertEquals(1, parent.level)
        assertThrows<IllegalArgumentException> {
            net.attachIHPToExternalSupplier(parent)
        }
        net.attachIHPToSupplier(parent, child)
        assertTrue(net.isAttachedAsCustomer(child))
        assertEquals(2, child.level)
        assertThrows<IllegalArgumentException> {
            net.attachIHPToExternalSupplier(child)
        }
    }

    @Test
    fun `isCustomer reports the supplier-customer link`() {
        val sc = SupplyChainModel(Model("ME.Customer"))
        val net = MultiEchelonNetwork(sc, name = "Net")
        net.addItemType("A", ConstantRV(1.0))
        val p = net.addInventoryHoldingPoint("P")
        val c = net.addInventoryHoldingPoint("C")
        net.attachIHPToExternalSupplier(p)
        assertFalse(net.isCustomer(p, c))
        net.attachIHPToSupplier(p, c)
        assertTrue(net.isCustomer(p, c))
    }

    @Test
    fun `level-aggregate responses are created lazily and reachable`() {
        val sc = SupplyChainModel(Model("ME.Levels"))
        val net = MultiEchelonNetwork(
            sc, name = "Net", levelResponses = true,
        )
        net.addItemType("A", ConstantRV(1.0))
        assertNull(net.getAggregateInventoryResponse(1))
        val p = net.addInventoryHoldingPoint("P")
        net.attachIHPToExternalSupplier(p)
        assertNotNull(net.getAggregateInventoryResponse(1))
        assertTrue(1 in net.levelSet)
    }

    @Test
    fun `level-aggregate map stays null without the flag`() {
        val sc = SupplyChainModel(Model("ME.NoLevels"))
        val net = MultiEchelonNetwork(sc, name = "Net")
        net.addItemType("A", ConstantRV(1.0))
        val p = net.addInventoryHoldingPoint("P")
        net.attachIHPToExternalSupplier(p)
        assertNull(net.getAggregateInventoryResponse(1))
        assertTrue(net.levelSet.isEmpty())
    }

    @Test
    fun `SharedCarrier rejects transportTime on attach methods`() {
        val sc = SupplyChainModel(Model("ME.Reject"))
        val net = MultiEchelonNetwork(sc, name = "Net")
        val a = net.addItemType("A", ConstantRV(1.0))
        val p = net.addInventoryHoldingPoint("P")
        val c = net.addInventoryHoldingPoint("C")
        assertThrows<IllegalArgumentException> {
            net.attachIHPToExternalSupplier(p, ConstantRV(1.0))
        }
        net.attachIHPToExternalSupplier(p)
        assertThrows<IllegalArgumentException> {
            net.attachIHPToSupplier(p, c, ConstantRV(1.0))
        }
        net.attachIHPToSupplier(p, c)
        assertThrows<IllegalArgumentException> {
            net.attachDemandGeneratorToIHP(p, a, ConstantRV(1.0), "DG", ConstantRV(0.5))
        }
    }

    @Test
    fun `strategy-specific methods are guarded by the matching strategy`() {
        val sc = SupplyChainModel(Model("ME.Guards"))
        val shared = MultiEchelonNetwork(sc, name = "S")
        assertThrows<IllegalStateException> { shared.allowExternalDemandGenerators() }

        val carrier = TimeBasedNetworkDemandCarrier(sc, name = "S.Carrier")
        val networkTb = MultiEchelonNetwork(
            sc, name = "N",
            transportStrategy = TransportStrategy.NetworkTimeBased(carrier),
        )
        assertThrows<IllegalStateException> { networkTb.allowExternalDemandGenerators() }
        assertThrows<IllegalStateException> { shared.attachExternalDemandSender(
            object : DemandSenderIfc {
                override val id: Int = 0
                override val name: String = "X"
                override var label: String? = null
                override fun mightRequest(type: ItemType): Boolean = true
                override var demandFillerFinder: DemandFillerFinderIfc? = null
                override var demandFiller: DemandFillerIfc? = null
            },
        ) }
    }

    @Test
    fun `demandCarrier slot is only accessible under SharedCarrier`() {
        val sc = SupplyChainModel(Model("ME.Slot"))
        val perIhp = MultiEchelonNetwork(
            sc, name = "P",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        assertThrows<IllegalStateException> { perIhp.demandCarrier }
        assertThrows<IllegalStateException> { perIhp.demandCarrier = NoDelayDemandCarrier }
    }

    @Test
    fun `attachDemandGeneratorToIHP rejects duplicates`() {
        val sc = SupplyChainModel(Model("ME.Gen"))
        val net = MultiEchelonNetwork(sc, name = "Net")
        val a = net.addItemType("A", ConstantRV(1.0))
        val p = net.addInventoryHoldingPoint("P")
        net.attachIHPToExternalSupplier(p)
        val dg = net.attachDemandGeneratorToIHP(p, a, ConstantRV(1.0), "DG")
        assertEquals(1, net.getDemandGenerators(p).size)
        assertThrows<IllegalArgumentException> {
            net.attachDemandGeneratorToIHP(p, dg)
        }
    }
}
