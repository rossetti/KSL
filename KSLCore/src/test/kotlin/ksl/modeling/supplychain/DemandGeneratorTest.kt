package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DemandGeneratorTest {

    @Test
    fun `mightRequest returns true for the configured itemType`() {
        val m = Model("DemandGeneratorMightRequest")
        val sc = SupplyChainModel(m)
        val a = ItemType(sc, name = "A")
        val b = ItemType(sc, name = "B")
        val g = DemandGenerator(sc, a, ConstantRV(1.0), ConstantRV(1.0))
        assertTrue(g.mightRequest(a))
        assertFalse(g.mightRequest(b))
    }

    @Test
    fun `end-to-end simulation drives a stream of demands to delivered`() {
        val m = Model("DemandGeneratorE2E")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "SKU-1")
        val filler = LeadTimeDemandFiller(sc)
        filler.addLeadTime(item, ConstantRV(0.5))
        val delivered = mutableListOf<SupplyChainModel.Demand>()
        val g = object : DemandGenerator(
            sc, item, ConstantRV(1.0), ConstantRV(1.0),
            name = "DemandGen",
        ) {
            init { demandFiller = filler }
            override fun demandDelivered(demand: SupplyChainModel.Demand) {
                delivered += demand
            }
        }
        m.numberOfReplications = 1
        // Events fire at t=1..10; each delivers at t+0.5. Extend the
        // replication past the last delivery (10.5) so the 10th demand
        // completes before the sim ends.
        m.lengthOfReplication = 11.0
        m.simulate()
        assertEquals(10, delivered.size)
        assertTrue(delivered.all { it.demandState === sc.stored })
        assertTrue(delivered.all { it.amountFilled == 1 })
    }

    @Test
    fun `setAmountDistribution drives demand quantities`() {
        val m = Model("DemandGeneratorAmount")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "SKU-1")
        val filler = LeadTimeDemandFiller(sc)
        filler.addLeadTime(item, ConstantRV(0.1))
        val captured = mutableListOf<SupplyChainModel.Demand>()
        val g = object : DemandGenerator(
            sc, item, ConstantRV(1.0), ConstantRV(1.0),
            name = "DemandGenAmount",
        ) {
            init { demandFiller = filler }
            override fun demandDelivered(demand: SupplyChainModel.Demand) {
                captured += demand
            }
        }
        g.setAmountDistribution(ConstantRV(4.0))
        m.numberOfReplications = 1
        m.lengthOfReplication = 4.0 // events at 1, 2, 3; all deliver by 3.1
        m.simulate()
        assertEquals(3, captured.size)
        assertTrue(captured.all { it.originalAmountDemanded == 4 })
    }

    @Test
    fun `unitDemandOnly splits a multi-unit amount into separate demands`() {
        val m = Model("DemandGeneratorUnitOnly")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "SKU-1")
        val filler = LeadTimeDemandFiller(sc)
        filler.addLeadTime(item, ConstantRV(0.1))
        val captured = mutableListOf<SupplyChainModel.Demand>()
        val g = object : DemandGenerator(
            sc, item, ConstantRV(1.0), ConstantRV(1.0),
            name = "DemandGenUnit",
        ) {
            init {
                demandFiller = filler
                unitDemandOnly = true
            }
            override fun demandDelivered(demand: SupplyChainModel.Demand) {
                captured += demand
            }
        }
        g.setAmountDistribution(ConstantRV(3.0))
        m.numberOfReplications = 1
        m.lengthOfReplication = 2.0 // one event at t=1, splits into 3 unit demands
        m.simulate()
        assertEquals(3, captured.size)
        assertTrue(captured.all { it.originalAmountDemanded == 1 })
    }
}
