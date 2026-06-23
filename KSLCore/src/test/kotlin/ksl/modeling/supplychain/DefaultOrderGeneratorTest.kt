package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DefaultOrderGeneratorTest {

    @Test
    fun `addItemTypeDistribution delegates to built-in creator`() {
        val m = Model("DefaultOrderGenAdd")
        val sc = SupplyChainModel(m)
        val a = ItemType(sc, name = "A")
        val g = DefaultOrderGenerator(
            supplyChainModel = sc,
            timeUntilFirstRV = ConstantRV(1.0),
            timeBtwEventsRV = ConstantRV(1.0),
        )
        g.addItemTypeDistribution(a, amountDistribution = ConstantRV(2.0))
        assertTrue(g.randomOrderCreator.createsType(a))
        // Creator built into the generator becomes the order creator
        assertSame(g.randomOrderCreator, g.orderCreator)
    }

    @Test
    fun `end-to-end simulation with built-in creator delivers orders`() {
        val m = Model("DefaultOrderGenE2E")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "SKU-1")
        val filler = LeadTimeOrderFiller(sc)
        filler.addLeadTime(item, ConstantRV(1.0))
        val delivered = mutableListOf<SupplyChainModel.Order>()
        val g = object : DefaultOrderGenerator(
            supplyChainModel = sc,
            timeUntilFirstRV = ConstantRV(2.0),
            timeBtwEventsRV = ConstantRV(2.0),
            name = "DefaultOrderGen",
        ) {
            init { orderFiller = filler }
            override fun orderDelivered(order: SupplyChainModel.Order) {
                delivered += order
            }
        }
        g.addItemTypeDistribution(item, amountDistribution = ConstantRV(3.0))
        m.numberOfReplications = 1
        m.lengthOfReplication = 12.0
        m.simulate()
        // Events at t=2, 4, 6, 8, 10. Each order delivers at t+1.
        assertEquals(5, delivered.size)
        assertTrue(delivered.all { it.orderState === sc.orderDelivered })
    }
}
