package ksl.examples.general.models

import ksl.modeling.elements.EventGeneratorIfc
import ksl.modeling.elements.REmpiricalList
import ksl.simulation.ModelElement
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.RequestQ
import ksl.modeling.entity.Resource
import ksl.modeling.entity.ResourcePool
import ksl.modeling.nhpp.NHPPEventGenerator
import ksl.modeling.nhpp.NHPPTimeBtwEventRV
import ksl.modeling.nhpp.PiecewiseConstantRateFunction
import ksl.modeling.queue.Queue
import ksl.modeling.variable.IndicatorResponse
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.utilities.Interval
import ksl.utilities.collections.HashBasedTable
import ksl.utilities.divideConstant
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.simulation.Model

fun main() {
    val model = Model("SMTravelProject")
    model.numberOfReplications = 20
    model.lengthOfReplication = 12.0*60.0
    val smTravelSystem = SMTravelProposedSystem(model, "SMTravelProposedSystem") //TODO has an error

    smTravelSystem.numTrunkLines = 30
    model.simulate()
    model.print()
}


class SMTravelProposedSystem(
    parent: ModelElement,
    name: String? = null
) : ProcessModel(parent, name) {

    var timeToEstimateWait: Double = 8.0 / 60.0
        set(value) {
            require(model.isNotRunning) { "Cannot change the time to estimate the wait when the model is running" }
            require(value > 0.0) { "The time to estimate the waiting must be positive" }
            field = value
        }

    var goldWaitCriteria: Double = 1.0
        set(value) {
            require(model.isNotRunning) { "Cannot change the gold waiting criteria when the model is running" }
            require(value > 0.0) { "The time criteria for gold waiting must be positive" }
            field = value
        }

    var silverWaitCriteria: Double = 2.0
        set(value) {
            require(model.isNotRunning) { "Cannot change the silver waiting criteria when the model is running" }
            require(value > 0.0) { "The time criteria for silver waiting must be positive" }
            field = value
        }

    var regularWaitCriteria: Double = 5.0
        set(value) {
            require(model.isNotRunning) { "Cannot change the regular waiting criteria when the model is running" }
            require(value > 0.0) { "The time criteria for regular waiting must be positive" }
            field = value
        }

    var numReservedLines: Int = 5
        set(value) {
            require(model.isNotRunning) { "Cannot change the number of reserved lines when the model is running" }
            require(value >= 0) { "The number of reserved lines must be >= 0" }
            require(value < numTrunkLines) { "The number of reserved lines must be < $numTrunkLines" }
            field = value
        }

    var numTrunkLines: Int = 30
        set(value) {
            require(model.isNotRunning) { "Cannot change the number of trunk lines when the model is running" }
            require(value >= 1) { "The number of trunk lines must be >= 1" }
            field = value
        }

    // rates per minute
    private var myRegularArrivalRates: DoubleArray = doubleArrayOf(
        87.0, 165.0, 236.0, 323.0, 277.0, 440.0, 269.0, 342.0, 175.0, 273.0, 115.0, 56.0
    ).divideConstant(60.0)

    private var myCardHolderArrivalRates: DoubleArray = doubleArrayOf(
        89.0, 243.0, 221.0, 180.0, 301.0, 490.0, 394.0, 347.0, 240.0, 269.0, 145.0, 69.0
    ).divideConstant(60.0)

    // all 60 minute durations
    private val myDurations = DoubleArray(myRegularArrivalRates.size) { 60.0 }

    private val myRegularArrivalRateFunction = PiecewiseConstantRateFunction(myDurations, myRegularArrivalRates)
    private val myTBRegularArrivals = NHPPTimeBtwEventRV(this, myRegularArrivalRateFunction, streamNum = 1)

    private val myCardHolderArrivalRateFunction = PiecewiseConstantRateFunction(myDurations, myCardHolderArrivalRates)
    private val myTBCardHolderArrivals = NHPPTimeBtwEventRV(this, myCardHolderArrivalRateFunction, streamNum = 2)

    private val myCustomerTypeList = listOf("gold", "silver", "regular")
    private val myGoldSilverTypeList = listOf("gold", "silver")
    private val myCallTypeList = listOf("information", "reservation", "change")
    private val myCustomerTypeCDF = doubleArrayOf(0.68, 1.0)
    private val myCallTypeCDF = doubleArrayOf(0.16, 0.92, 1.0)
    private val myCustomerTypeGoldOrSilver = REmpiricalList(this, myGoldSilverTypeList, myCustomerTypeCDF)
    private val myCallTypeRE = REmpiricalList(this, myCallTypeList, myCallTypeCDF)
    private val myCallTimeTable = HashBasedTable<String, String, RVariableIfc>()
    private val myAfterCallTimeTable = HashBasedTable<String, String, RVariableIfc>()
    private val myServiceMeans = HashBasedTable<String, String, Double>()

    init {
        myCallTimeTable.put("information", "gold", TriangularRV(1.056, 1.804, 3.3))
        myCallTimeTable.put("information", "silver", TriangularRV(1.14, 1.9475, 3.5625))
        myCallTimeTable.put("information", "regular", TriangularRV(1.2, 2.05, 3.75))
        myCallTimeTable.put("reservation", "gold", TriangularRV(1.98, 2.596, 7.568))
        myCallTimeTable.put("reservation", "silver", TriangularRV(2.1375, 2.8025, 8.17))
        myCallTimeTable.put("reservation", "regular", TriangularRV(2.25, 2.95, 8.6))
        myCallTimeTable.put("change", "gold", TriangularRV(1.056, 1.672, 5.104))
        myCallTimeTable.put("change", "silver", TriangularRV(1.14, 1.805, 5.51))
        myCallTimeTable.put("change", "regular", TriangularRV(1.2, 1.9, 5.8))

        myAfterCallTimeTable.put("information", "gold", UniformRV(0.044, 0.088))
        myAfterCallTimeTable.put("information", "silver", UniformRV(0.0475, 0.095))
        myAfterCallTimeTable.put("information", "regular", UniformRV(0.05, 0.1))
        myAfterCallTimeTable.put("reservation", "gold", UniformRV(0.44, 0.704))
        myAfterCallTimeTable.put("reservation", "silver", UniformRV(0.475, 0.76))
        myAfterCallTimeTable.put("reservation", "regular", UniformRV(0.5, 0.8))
        myAfterCallTimeTable.put("change", "gold", UniformRV(0.352, 0.528))
        myAfterCallTimeTable.put("change", "silver", UniformRV(0.38, 0.57))
        myAfterCallTimeTable.put("change", "regular", UniformRV(0.4, 0.6))

        // could have declared Triangular distributions, created random variables and computed means
        myServiceMeans.put("information", "gold", 2.0533)
        myServiceMeans.put("information", "silver", 2.217)
        myServiceMeans.put("information", "regular", 2.333)
        myServiceMeans.put("reservation", "gold", 4.048)
        myServiceMeans.put("reservation", "silver", 4.37)
        myServiceMeans.put("reservation", "regular", 4.6)
        myServiceMeans.put("change", "gold", 2.6107)
        myServiceMeans.put("change", "silver", 2.8183)
        myServiceMeans.put("change", "regular", 2.967)
    }

    private val myCardHolderEnterNumTimeRV = RandomVariable(this, UniformRV(7.0 / 60.0, 16.0 / 60.0))
    private val myRegularWaitTolTimeRV = RandomVariable(this, UniformRV(12.0, 30.0))
    private val myCardHolderTolTimeRV = RandomVariable(this, UniformRV(8.0, 17.0))

    private val myNumCallsInSystem = TWResponse(this, "NumCallsInSystem")
    private val myNumBusyTrunkLines = TWResponse(this, "NumBusyTrunkLines")
    private val myProbOfBusyLine = Response(this, "ProbOfBusyLine")
    private val myProbCardHolderBusy = Response(this, "ProbCardHolderBusyLine")
    private val myProbOfBusyByType = mapOf(
        "gold" to Response(this, "Gold:ProbOfBusyLine"),
        "silver" to Response(this, "Silver:ProbOfBusyLine"),
        "regular" to Response(this, "Regular:ProbOfBusyLine"),
    )

    private val myWaitingTimeByType = mapOf(
        "gold" to Response(this, "Gold:WaitingTime"),
        "silver" to Response(this, "Silver:WaitingTime"),
        "regular" to Response(this, "Regular:WaitingTime"),
    )

    private val myGoldProbWait: IndicatorResponse = IndicatorResponse(
        this::goldIndicator,
        myWaitingTimeByType["gold"]!!, "Gold Wait <= Wait Criteria"
    )

    private fun goldIndicator(time: Double): Boolean {
        return time <= goldWaitCriteria
    }

    private val mySilverProbWait: IndicatorResponse = IndicatorResponse(
        this::sliverIndicator,
        myWaitingTimeByType["silver"]!!, "Silver Wait <= Wait Criteria"
    )

    private fun sliverIndicator(time: Double): Boolean {
        return time <= silverWaitCriteria
    }

    private val myRegularProbWait: IndicatorResponse = IndicatorResponse(
        this::regularIndicator,
        myWaitingTimeByType["regular"]!!, "Regular Wait <= Wait Criteria"
    )

    private fun regularIndicator(time: Double): Boolean {
        return time <= regularWaitCriteria
    }

    private val myWorkInQ = TWResponse(this, "WorkInQ", allowedDomain = Interval(-0.0001, Double.POSITIVE_INFINITY))
    private val myProbOfAbandonment = Response(this, "ProbOfAbandonment")

    private val myCardHolderGenerator = NHPPEventGenerator(
        this,
        this::createCardHolders, myTBCardHolderArrivals
    )

    private val myRegularCustomerGenerator = NHPPEventGenerator(
        this,
        this::createRegularCustomers, myTBRegularArrivals
    )

//    private val myCallOperators = ResourceWithQ(this, name = "CallOperators", capacity = 20)
//    val callOperators: ResourceWithQCIfc
//        get() = myCallOperators

    private val myGoldOperators = Resource(this, name = "GoldOperators", capacity = 5)
    private val mySilverOperators = Resource(this, name = "SilverOperators", capacity = 5)
    private val myRegularOperators = Resource(this, name = "RegularOperators", capacity = 5)

    private val myGoldResources = listOf(myGoldOperators, mySilverOperators, myRegularOperators)
    private val mySilverResources = listOf(mySilverOperators, myRegularOperators)
    private val myGoldPool = ResourcePool(this, poolResources = myGoldResources, name = "GoldPool")
    private val mySilverPool = ResourcePool(this, poolResources = mySilverResources, name = "SilverPool")
    private val myRegularPool = ResourcePool(this, poolResources = listOf(myRegularOperators), name = "RegularPool")
    private val myCustomerQ = RequestQ(this, name = "CustomerQ", discipline = Queue.Discipline.RANKED)

    private val myPoolMap = mapOf(
        "gold" to myGoldPool,
        "silver" to mySilverPool,
        "regular" to myRegularPool
    )

    private val myPriorityMap = mapOf(
        "gold" to 1,
        "silver" to 2,
        "regular" to 3
    )

    override fun initialize() {
        //  TODO("Not implemented yet!")
    }

    private fun createCardHolders(generator: EventGeneratorIfc) {
        // create the cardholder customer
        val customerType = myCustomerTypeGoldOrSilver.randomElement
        if (myNumBusyTrunkLines.value >= numTrunkLines) {
            collectBusyStatistics(customerType, 1.0)
            return
        }
        collectBusyStatistics(customerType, 0.0)
        val arrivingCustomer = CustomerCall(customerType)
        activate(arrivingCustomer.currentCallProcess)
    }

    private fun createRegularCustomers(generator: EventGeneratorIfc) {
        // create the regular customer
        val customerType = "regular"
        if (myNumBusyTrunkLines.value >= numTrunkLines - numReservedLines) {
            collectBusyStatistics(customerType, 1.0)
            return
        }
        collectBusyStatistics(customerType, 0.0)
        val arrivingCustomer = CustomerCall(customerType)
        activate(arrivingCustomer.currentCallProcess)
    }

    private fun collectBusyStatistics(customerType: String, busy: Double) {
        myProbOfBusyLine.value = busy
        myProbOfBusyByType[customerType]!!.value = busy
        if (customerType != "regular") {
            myProbCardHolderBusy.value = busy
        }
    }

    private fun estimateWaitingTime(): Double {
        // this does not account for arriving to an empty queue, but all servers are busy
        // you would have to wait for a server to complete and does not account for wait by customer type
        // it only accounts for the work in the queue ahead of an arriving customer that has to wait
        return myWorkInQ.value
    }

    private inner class CustomerCall(val customerType: String) : Entity() {
        val callType = myCallTypeRE.randomElement
        val waitingTolerance = if (customerType == "regular") {
            myRegularWaitTolTimeRV.value
        } else {
            myCardHolderTolTimeRV.value
        }
        val pool = myPoolMap[customerType]!!
        val expectedServiceTime = myServiceMeans.get(callType, customerType)!!
        val serviceTime = myCallTimeTable.get(callType, customerType)!!.value
        val afterCallTime = myAfterCallTimeTable.get(callType, customerType)!!.value
        var waitingTime = 0.0
        init{
            //this is the queuing priority
            priority = myPriorityMap[customerType]!!
        }

        val currentCallProcess: KSLProcess = process {
            myNumBusyTrunkLines.increment()
            myNumCallsInSystem.increment()
            if (customerType != "regular") {
                delay(myCardHolderEnterNumTimeRV)
            }
            if (!pool.hasAvailableUnits) {
                // customer will have to wait because no operators are available
                delay(timeToEstimateWait)
                val estimatedWaitingTime = estimateWaitingTime()
                if (estimatedWaitingTime > waitingTolerance) {
                    myNumBusyTrunkLines.decrement()
                    myProbOfAbandonment.value = 1.0
                    myNumCallsInSystem.decrement()
                    return@process
                }
            }
            // do call processing
            myProbOfAbandonment.value = 0.0
            myWorkInQ.increment(expectedServiceTime)
            val timeEnteredQ = time
            val a = seize(resourcePool = pool, queue = myCustomerQ )
            waitingTime = time - timeEnteredQ
            myWorkInQ.decrement(expectedServiceTime)
            delay(serviceTime)
            myNumBusyTrunkLines.decrement()
            myWaitingTimeByType[customerType]!!.value = waitingTime
            delay(afterCallTime)
            release(a)
            myNumCallsInSystem.decrement()
        }

    }

}