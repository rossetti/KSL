package ksl.examples.general.models

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.modeling.elements.EventGenerator
import ksl.modeling.elements.EventGeneratorIfc
import ksl.modeling.elements.EventGeneratorRVCIfc
import ksl.modeling.elements.GeneratorActionIfc
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.RandomVariableCIfc
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.Interval
import ksl.utilities.random.rvariable.ConstantRV
import kotlin.math.max

class LKInventoryModel(
    parent: ModelElement,
    name: String? = null
) : ModelElement(parent, name) {


    @set:KSLControl(
        controlType = ControlType.INTEGER,
        lowerBound = 1.0
    )
    var orderQuantity = 20
        set(value) {
            require(value > 0) { "Order quantity must be greater than zero" }
            require(model.isNotRunning) { "The model must not be running when setting the order quantity" }
            field = value
        }

    val orderUpToLevel : Int
        get() = reorderPoint + orderQuantity

    @set:KSLControl(
        controlType = ControlType.INTEGER,
        lowerBound = 1.0
    )
    var reorderPoint = 20
        set(value) {
            require(value > 0) { "Reorder point must be greater than zero" }
            require(model.isNotRunning) { "The model must not be running when setting the reorder point" }
            field = value
        }

    @set:KSLControl(
        controlType = ControlType.INTEGER,
        lowerBound = 1.0
    )
    var initialInventoryLevel = 60.0
        set(value) {
            require(value > 0) { "Initial inventory level must be greater than zero" }
            require(model.isNotRunning) { "The model must not be running when setting the initial inventory level" }
            field = value
            initializeInventoryLevels(value)
        }

    @set:KSLControl(
        controlType = ControlType.DOUBLE,
        lowerBound = 0.0
    )
    var holdingCost = 1.0
        set(value) {
            require(value > 0) { "The holding cost must be greater than zero" }
            require(model.isNotRunning) { "The model must not be running when setting the holding cost" }
            field = value
        }

    @set:KSLControl(
        controlType = ControlType.DOUBLE,
        lowerBound = 0.0
    )
    var costPerItem = 3.0
        set(value) {
            require(value > 0) { "The cost per item must be greater than zero" }
            require(model.isNotRunning) { "The model must not be running when setting the cost per item" }
            field = value
        }

    @set:KSLControl(
        controlType = ControlType.DOUBLE,
        lowerBound = 0.0
    )
    var backLogCost = 5.0
        set(value) {
            require(value > 0) { "The backlog cost must be greater than zero" }
            require(model.isNotRunning) { "The model must not be running when setting the backlog cost" }
            field = value
        }

    @set:KSLControl(
        controlType = ControlType.DOUBLE,
        lowerBound = 0.0
    )
    var setupCost = 32.0
        set(value) {
            require(value > 0) { "The setup cost must be greater than zero" }
            require(model.isNotRunning) { "The model must not be running when setting the setup cost" }
            field = value
        }

    private val myLeadTime: RandomVariable = RandomVariable(this,
        UniformRV(0.5, 1.0, streamNum = 2))
    val leadTime: RandomVariableCIfc
        get() = myLeadTime

    private val myDemandAmount: RandomVariable = RandomVariable(
        this,
        DEmpiricalRV(
            values = doubleArrayOf(1.0, 2.0, 3.0, 4.0),
            cdf = doubleArrayOf(1.0 / 6.0, 3.0 / 6.0, 5.0 / 6.0, 1.0), streamNum = 3
        )
    )
    val demandAmount: RandomVariableCIfc
        get() = myDemandAmount

    private val myInvLevel: TWResponse = TWResponse(
        this, "InventoryLevel",
        allowedDomain = Interval()
    )
    val inventoryLevel: TWResponseCIfc
        get() = myInvLevel

    private val myPosInv: TWResponse = TWResponse(this, "OnHandLevel")
    val posInventoryLevel: TWResponseCIfc
        get() = myPosInv

    private val myNegInv: TWResponse = TWResponse(this, "BackorderLevel")
    val negInventoryLevel: TWResponseCIfc
        get() = myNegInv

    private val myAvgTotalCost: TWResponse = TWResponse(this, "TotalCost")
    val avgTotalCost: TWResponseCIfc
        get() = myAvgTotalCost
    private val myAvgHoldingCost: TWResponse = TWResponse(this, "HoldingCost")
    val avgHoldingCost: TWResponseCIfc
        get() = myAvgHoldingCost
    private val myAvgSetupCost: TWResponse = TWResponse(this, "SetupCost")
    val avgSetupCost: TWResponseCIfc
        get() = myAvgSetupCost
    private val myAvgShortageCost: TWResponse = TWResponse(this, "ShortageCost")
    val avgShortageCost: TWResponseCIfc
        get() = myAvgShortageCost

    init {
        initializeInventoryLevels(initialInventoryLevel)
    }

    /**
     *  Sets up the initial values for the inventory based on the initial inventory level.
     */
    private fun initializeInventoryLevels(level: Double) {
        myInvLevel.initialValue = level
        myPosInv.initialValue = Math.max(0.0, myInvLevel.initialValue)
        myNegInv.initialValue = -Math.min(0.0, myInvLevel.initialValue)
        myAvgHoldingCost.initialValue = holdingCost * myPosInv.initialValue
        myAvgShortageCost.initialValue = backLogCost * myNegInv.initialValue
        myAvgSetupCost.initialValue = 0.0
        val cost: Double =
            myAvgSetupCost.initialValue + myAvgHoldingCost.initialValue + myAvgShortageCost.initialValue
        myAvgTotalCost.initialValue = cost
    }

    private val timeBetweenArrivals = ExponentialRV(0.1, 1)
    private val myDemandGenerator: EventGenerator = EventGenerator(
        this, DemandArrival(), arrivalsRV = timeBetweenArrivals, name = "Demand Generator"
    )
    val demandGenerator: EventGeneratorRVCIfc
        get() = myDemandGenerator

    private val myInventoryCheckGenerator: EventGenerator = EventGenerator(
        this,
        InventoryCheck(), ConstantRV.ZERO, ConstantRV.ONE, name = "Inventory Check"
    )
    val inventoryCheckGenerator: EventGeneratorRVCIfc
        get() = myInventoryCheckGenerator

    private val myOrderArrivalEvent: OrderArrival = OrderArrival()

    private inner class DemandArrival : GeneratorActionIfc {
        override fun generate(generator: EventGeneratorIfc) {
            myInvLevel.decrement(myDemandAmount.value)
            myPosInv.value = Math.max(0.0, myInvLevel.value)
            myNegInv.value = -Math.min(0.0, myInvLevel.value)
            myAvgHoldingCost.value = holdingCost * myPosInv.value
            myAvgShortageCost.value = backLogCost * myNegInv.value
            val cost: Double = myAvgSetupCost.value + myAvgHoldingCost.value + myAvgShortageCost.value
            myAvgTotalCost.value = cost
        }
    }

    private fun scheduleReplenishment(orderSize: Double) {
        val t: Double = myLeadTime.value
        schedule(myOrderArrivalEvent, t, orderSize)
    }

    private inner class InventoryCheck : GeneratorActionIfc {
        override fun generate(generator: EventGeneratorIfc) {
            if (myInvLevel.value < reorderPoint) {
                val orderSize: Double = orderUpToLevel - myInvLevel.value
                scheduleReplenishment(orderSize)
                myAvgSetupCost.value = setupCost + costPerItem * orderSize
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
            myAvgHoldingCost.value = holdingCost * myPosInv.value
            myAvgShortageCost.value = backLogCost * myNegInv.value
            val cost: Double = myAvgSetupCost.value + myAvgHoldingCost.value + myAvgShortageCost.value
            myAvgTotalCost.value = cost
        }
    }
}