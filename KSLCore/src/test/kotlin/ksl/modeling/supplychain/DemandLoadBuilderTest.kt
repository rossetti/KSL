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
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DemandLoadBuilderTest {

    private class TestSender(override val name: String) : DemandSenderIfc {
        override val id: Int = 0
        override var label: String? = null
        override fun mightRequest(type: ItemType): Boolean = true
        override var demandFillerFinder: DemandFillerFinderIfc? = null
        override var demandFiller: DemandFillerIfc? = null
    }

    private fun makeDemand(
        sc: SupplyChainModel,
        item: ItemType,
        sender: DemandSenderIfc,
        amount: Int = 1,
    ): SupplyChainModel.Demand {
        val d = sc.createDemand(item, amount)
        d.setDemandSender(sender)
        val filler = NoOpDemandFiller()
        d.setFiller(filler)
        d.sent()
        d.receive(filler)
        d.process(filler)
        d.fill(amount)
        return d
    }

    @Test
    fun `default option ALWAYS forms a single-demand load on receive`() {
        val m = Model("DLB.Always")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A", weight = 2.0, cube = 3.0)
        val sender = TestSender("S1")
        val builder = DemandLoadBuilder(sc, name = "DLB")
        var fireCount = 0
        builder.loadFormedListener = DemandLoadFormedListenerIfc { fireCount++ }

        val d = makeDemand(sc, item, sender)
        builder.receiveDemand(d)

        assertEquals(1, builder.loadQueue.size)
        assertEquals(0, builder.demandQueue.size)
        assertEquals(1, fireCount)
    }

    @Test
    fun `NONE option holds demands without forming a load`() {
        val m = Model("DLB.None")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val sender = TestSender("S1")
        val builder = DemandLoadBuilder(sc, name = "DLB")
        builder.loadFormingOption = DemandLoadBuilder.LoadFormingOption.NONE

        val d = makeDemand(sc, item, sender)
        builder.receiveDemand(d)

        assertEquals(0, builder.loadQueue.size)
        assertEquals(1, builder.demandQueue.size)
    }

    @Test
    fun `COUNT option forms a load at the threshold`() {
        val m = Model("DLB.Count")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val sender = TestSender("S1")
        val builder = DemandLoadBuilder(sc, name = "DLB").apply {
            loadFormingOption = DemandLoadBuilder.LoadFormingOption.COUNT
            countLimit = 3
        }
        // Two demands: below threshold
        builder.receiveDemand(makeDemand(sc, item, sender))
        builder.receiveDemand(makeDemand(sc, item, sender))
        assertEquals(0, builder.loadQueue.size)
        assertEquals(2, builder.demandQueue.size)
        // Third demand triggers formation
        builder.receiveDemand(makeDemand(sc, item, sender))
        assertEquals(1, builder.loadQueue.size)
        assertEquals(0, builder.demandQueue.size)
    }

    @Test
    fun `WEIGHT option respects min and max thresholds`() {
        val m = Model("DLB.Weight")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A", weight = 2.0)
        val sender = TestSender("S1")
        val builder = DemandLoadBuilder(sc, name = "DLB").apply {
            loadFormingOption = DemandLoadBuilder.LoadFormingOption.WEIGHT
            setWeightFormingLimits(minWeight = 5.0, maxWeight = 10.0)
        }
        // Each filled demand weighs 2.0; below min until 3rd
        builder.receiveDemand(makeDemand(sc, item, sender, amount = 1))
        builder.receiveDemand(makeDemand(sc, item, sender, amount = 1))
        assertEquals(0, builder.loadQueue.size)
        builder.receiveDemand(makeDemand(sc, item, sender, amount = 1))
        assertEquals(1, builder.loadQueue.size)
        val load = builder.loadQueue.peekAt(0)!!
        assertTrue(load.weight in 5.0..10.0)
    }

    @Test
    fun `CUBE option respects min and max thresholds`() {
        val m = Model("DLB.Cube")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A", cube = 3.0)
        val sender = TestSender("S1")
        val builder = DemandLoadBuilder(sc, name = "DLB").apply {
            loadFormingOption = DemandLoadBuilder.LoadFormingOption.CUBE
            setCubeFormingLimits(minCube = 6.0, maxCube = 10.0)
        }
        builder.receiveDemand(makeDemand(sc, item, sender))
        assertEquals(0, builder.loadQueue.size)
        builder.receiveDemand(makeDemand(sc, item, sender))
        assertEquals(1, builder.loadQueue.size)
        val load = builder.loadQueue.peekAt(0)!!
        assertTrue(load.cube in 6.0..10.0)
    }

    @Test
    fun `setting a load forming rule switches option to RULE`() {
        val m = Model("DLB.Rule")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "A")
        val sender = TestSender("S1")
        val builder = DemandLoadBuilder(sc, name = "DLB")
        // Rule: form a load only after at least 2 demands queued
        builder.loadFormingRule = DemandLoadFormingRuleIfc {
            it.formLoadByCount(2)
        }
        assertSame(
            DemandLoadBuilder.LoadFormingOption.RULE,
            builder.loadFormingOption,
        )
        builder.receiveDemand(makeDemand(sc, item, sender))
        assertEquals(0, builder.loadQueue.size)
        builder.receiveDemand(makeDemand(sc, item, sender))
        assertEquals(1, builder.loadQueue.size)
    }

    @Test
    fun `setting load forming rule to null reverts to NONE`() {
        val sc = SupplyChainModel(Model("DLB.RuleNull"))
        val builder = DemandLoadBuilder(sc, name = "DLB")
        builder.loadFormingRule = DemandLoadFormingRuleIfc { true }
        builder.loadFormingRule = null
        assertSame(
            DemandLoadBuilder.LoadFormingOption.NONE,
            builder.loadFormingOption,
        )
    }

    @Test
    fun `formLoadByCount requires a positive count`() {
        val sc = SupplyChainModel(Model("DLB.CountInvalid"))
        val builder = DemandLoadBuilder(sc, name = "DLB")
        assertThrows<IllegalArgumentException> { builder.formLoadByCount(0) }
    }

    @Test
    fun `weight and cube setters reject invalid bounds`() {
        val sc = SupplyChainModel(Model("DLB.Invalid"))
        val builder = DemandLoadBuilder(sc, name = "DLB")
        assertThrows<IllegalArgumentException> {
            builder.setWeightFormingLimits(0.0, 1.0)
        }
        assertThrows<IllegalArgumentException> {
            builder.setWeightFormingLimits(5.0, 1.0)
        }
        assertThrows<IllegalArgumentException> {
            builder.setCubeFormingLimits(-1.0, 1.0)
        }
        assertFalse(false)
    }

    // -- per-item on-hand tracking (design-doc §10 item #3) -------------

    @Test
    fun `unitsOnHandResponse returns null without itemTypes opt-in`() {
        val sc = SupplyChainModel(Model("DLB.NoItems"))
        val item = ItemType(sc, name = "A")
        val builder = DemandLoadBuilder(sc, name = "DLB")
        // Not constructed with itemTypes → null lookup, empty tracked set.
        assertEquals(null, builder.unitsOnHandResponse(item))
        assertTrue(builder.trackedItemTypes.isEmpty())
    }

    @Test
    fun `unitsOnHandResponse exposes a TWResponse for tracked items`() {
        val sc = SupplyChainModel(Model("DLB.OptIn"))
        val itemA = ItemType(sc, name = "A")
        val itemB = ItemType(sc, name = "B")
        val builder = DemandLoadBuilder(
            sc, name = "DLB", itemTypes = listOf(itemA, itemB),
        )
        assertEquals(setOf(itemA, itemB), builder.trackedItemTypes)
        // Both items present, distinct TWResponses.
        val rA = builder.unitsOnHandResponse(itemA)
        val rB = builder.unitsOnHandResponse(itemB)
        assertTrue(rA != null && rB != null && rA !== rB)
    }

    @Test
    fun `receiveDemand increments and formLoadAlways decrements the per-item TWResponse`() {
        // Use an end-to-end simulation so TWResponse pre/post-arrival
        // observations land in the within-replication statistic.
        val m = Model("DLB.AlwaysSym")
        val sc = SupplyChainModel(m, name = "SC")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        val item = net.addItemType("A", ConstantRV(0.25))
        val supplier = net.addInventoryHoldingPoint("P", enableShipmentFormation = true)
        supplier.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 1, reorderQty = 5, initialOnHand = 20,
        )
        net.attachToExternalSupplier(supplier, ConstantRV(0.25))
        // ALWAYS formation: one load per demand → builder TW avg should
        // stay near 0 because each demand exits immediately on arrival.
        net.attachDemandGenerator(
            supplier, item, ConstantRV(1.0), name = "DG",
            transportTime = ConstantRV.ZERO,
            shipmentFormation = ShipmentFormation(
                DemandLoadBuilder.LoadFormingOption.ALWAYS,
            ),
        )

        m.numberOfReplications = 1
        m.lengthOfReplication = 10.0
        m.simulate()

        // Net: every increment is paired with an immediate decrement.
        // Confirm by exhibit: TWResponse current value is 0 at sim end
        // (would be > 0 if any decrement were missing).
        val carrier = supplier.demandCarrier as TimeBasedLoadCarrier
        val builder = carrier.allLoadBuilders().single()
        val r = builder.unitsOnHandResponse(item)
            ?: error("expected per-item TWResponse for item A")
        assertEquals(0.0, r.value, 1e-9)
    }

    @Test
    fun `receiveDemand increments and formLoadByCount decrements symmetrically`() {
        // Same symmetry exhibit, COUNT path.
        val m = Model("DLB.CountSym")
        val sc = SupplyChainModel(m, name = "SC")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        val item = net.addItemType("A", ConstantRV(0.25))
        val supplier = net.addInventoryHoldingPoint("P", enableShipmentFormation = true)
        supplier.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 1, reorderQty = 5, initialOnHand = 20,
        )
        net.attachToExternalSupplier(supplier, ConstantRV(0.25))
        net.attachDemandGenerator(
            supplier, item, ConstantRV(1.0), name = "DG",
            transportTime = ConstantRV.ZERO,
            shipmentFormation = ShipmentFormation(
                DemandLoadBuilder.LoadFormingOption.COUNT, countLimit = 4,
            ),
        )

        m.numberOfReplications = 1
        m.lengthOfReplication = 16.0
        m.simulate()

        // 16 demands at countLimit = 4 → 4 full loads form; 0 left over.
        // Increments and decrements must balance, so current value is 0.
        val carrier = supplier.demandCarrier as TimeBasedLoadCarrier
        val builder = carrier.allLoadBuilders().single()
        val r = builder.unitsOnHandResponse(item)
            ?: error("expected per-item TWResponse for item A")
        assertEquals(0.0, r.value, 1e-9)
    }
}
