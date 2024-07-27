package ksl.examples.general.models

import ksl.modeling.elements.EventGenerator
import ksl.modeling.elements.GeneratorActionIfc
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.TWResponse
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.DEmpiricalRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.UniformRV
import kotlin.math.max

class LKInventoryModel(
    parent: ModelElement,
    name: String? = null
) : ModelElement(parent, name) {

    private val myOrderUpToLevel = 40
    private val myReorderPoint = 20
    private val myHoldingCost = 1.0
    private val myCostPerItem = 3.0
    private val myBackLogCost = 5.0
    private val mySetupCost = 32.0
    private var myInitialInventoryLevel = 60.0

    private val myLeadTime: RandomVariable = RandomVariable(this, UniformRV(0.5, 1.0))
    private val myDemandAmount: RandomVariable = RandomVariable(
        this,
        DEmpiricalRV(
            values = doubleArrayOf(1.0, 2.0, 3.0, 4.0),
            cdf = doubleArrayOf(1.0 / 6.0, 3.0 / 6.0, 5.0 / 6.0, 1.0)
        )
    )

    private val myInvLevel: TWResponse = TWResponse(this, "InventoryLevel")
    private val myPosInv: TWResponse = TWResponse(this, "On Hand Level")
    private val myNegInv: TWResponse = TWResponse(this, "Backorder Level")
    private val myAvgTotalCost: TWResponse = TWResponse(this, "Total Cost")
    private val myAvgHoldingCost: TWResponse = TWResponse(this, "Holding Cost")
    private val myAvgSetupCost: TWResponse = TWResponse(this, "Setup Cost")
    private val myAvgShortageCost: TWResponse = TWResponse(this, "Shortage Cost")

    private val myDemandGenerator: EventGenerator = EventGenerator(this, DemandArrival(),
        ExponentialRV(0.1), ExponentialRV(0.1), name = "Demand Generator")

    private val myInventoryCheckGenerator: EventGenerator = EventGenerator(this,
        InventoryCheck(), ConstantRV.ZERO, ConstantRV.ONE, name = "Inventory Check")

    private val myOrderArrivalEvent: OrderArrival = OrderArrival()

    private inner class DemandArrival : GeneratorActionIfc {
        override fun generate(generator: EventGenerator) {
            myInvLevel.decrement(myDemandAmount.value)
            myPosInv.value = Math.max(0.0, myInvLevel.value)
            myNegInv.value = -Math.min(0.0, myInvLevel.value)
            myAvgHoldingCost.value = myHoldingCost * myPosInv.value
            myAvgShortageCost.value = myBackLogCost * myNegInv.value
            val cost: Double = myAvgSetupCost.value + myAvgHoldingCost.value + myAvgShortageCost.value
            myAvgTotalCost.value = cost
        }
    }

    fun setInitialInventoryLevel(level: Double) {
        myInitialInventoryLevel = level
        myInvLevel.initialValue = myInitialInventoryLevel
        myPosInv.initialValue = Math.max(0.0 , myInvLevel.initialValue)
        myNegInv.initialValue = -Math.min(0.0, myInvLevel.initialValue)
        myAvgHoldingCost.initialValue = myHoldingCost * myPosInv.initialValue
        myAvgShortageCost.initialValue = myBackLogCost * myNegInv.initialValue
        myAvgSetupCost.initialValue = 0.0
        val cost: Double =
            myAvgSetupCost.initialValue + myAvgHoldingCost.initialValue + myAvgShortageCost.initialValue
        myAvgTotalCost.initialValue = cost
    }

    override fun initialize() {
        super.initialize()
        setInitialInventoryLevel(myInitialInventoryLevel)
    }

    private fun scheduleReplenishment(orderSize: Double) {
        val t: Double = myLeadTime.value
        schedule(myOrderArrivalEvent, t, orderSize)
    }

    private inner class InventoryCheck : GeneratorActionIfc {
        override fun generate(generator: EventGenerator) {
            if (myInvLevel.value < myReorderPoint) {
                val orderSize: Double = myOrderUpToLevel - myInvLevel.value
                scheduleReplenishment(orderSize)
                myAvgSetupCost.value = mySetupCost + myCostPerItem * orderSize
            } else {
                myAvgSetupCost.value = 0.0
            }
            val cost: Double = myAvgSetupCost.value + myAvgHoldingCost.value + myAvgShortageCost.value
            myAvgTotalCost.value = cost
        }
    }
    private inner class OrderArrival : EventActionIfc<Double> {
        override fun action(event: KSLEvent<Double>) {
            val orderSize: Double = event.message!!
            myInvLevel.increment(orderSize)
            myPosInv.value = max(0.0, myInvLevel.value)
            myNegInv.value = -Math.min(0.0, myInvLevel.value)
            myAvgHoldingCost.value = myHoldingCost * myPosInv.value
            myAvgShortageCost.value = myBackLogCost * myNegInv.value
            val cost: Double = myAvgSetupCost.value + myAvgHoldingCost.value + myAvgShortageCost.value
            myAvgTotalCost.value = cost
        }
    }
}