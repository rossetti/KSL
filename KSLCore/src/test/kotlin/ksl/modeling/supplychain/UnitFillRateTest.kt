package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.DemandGenerator
import ksl.modeling.supplychain.inventory.Inventory
import ksl.modeling.supplychain.network.MultiEchelonNetwork
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rng.RNStreamProvider
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  The first fill rate scores each demand 1 or 0. Under lot demand that is not the unit fill rate
 *  (units filled from stock on arrival over units demanded), which is the measure multi-echelon
 *  results usually report.
 */
class UnitFillRateTest {

    private class Setup(val model: Model, val inventory: Inventory)

    private fun network(
        initialOnHand: Int,
        leadTime: Double,
        demandTimes: ksl.utilities.random.rvariable.RVariableIfc,
        maxDemands: Long,
        lot: Int
    ): Setup {
        val model = Model("unit-fill-rate", autoCSVReports = false)
        val sc = SupplyChainModel(model, name = "SC")
        val net = MultiEchelonNetwork(sc, "Net")
        val item = net.addItemType("Item", ConstantRV(leadTime))
        val ihp = net.addInventoryHoldingPoint("Loc")
        val inventory = ihp.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 2, reorderQty = 5, initialOnHand = initialOnHand, name = "Inv"
        )
        net.attachIHPToExternalSupplier(ihp)
        val customers = DemandGenerator(
            sc, item, demandTimes, demandTimes, maxNumberOfEvents = maxDemands, name = "Cust"
        )
        customers.setAmountDistribution(ConstantRV(lot.toDouble()))
        net.attachDemandGenerator(ihp, customers)
        return Setup(model, inventory)
    }

    @Test
    fun aPartiallyFilledLotIsCreditedForTheUnitsStockCovered() {
        val setup = network(initialOnHand = 6, leadTime = 1000.0, demandTimes = ConstantRV(1.0), maxDemands = 1, lot = 10)
        setup.model.numberOfReplications = 1
        setup.model.lengthOfReplication = 2.0
        setup.model.simulate()
        assertEquals(0.0, setup.inventory.firstFillRateResponse.acrossReplicationStatistic.average, 1e-12)
        assertEquals(0.6, setup.inventory.unitFillRateResponse.acrossReplicationStatistic.average, 1e-12)
    }

    @Test
    fun withUnitDemandTheTwoRatesAgree() {
        val times = ExponentialRV(1.0, streamNum = 3, streamProvider = RNStreamProvider())
        val setup = network(initialOnHand = 7, leadTime = 4.0, demandTimes = times, maxDemands = Long.MAX_VALUE, lot = 1)
        setup.model.numberOfReplications = 5
        setup.model.lengthOfReplication = 500.0
        setup.model.simulate()
        val first = setup.inventory.firstFillRateResponse.acrossReplicationStatistic.average
        val unit = setup.inventory.unitFillRateResponse.acrossReplicationStatistic.average
        assertEquals(first, unit, 1e-9)
    }
}
