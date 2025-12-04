package ksl.examples.general.models


import ksl.modeling.elements.EventGeneratorIfc
import ksl.modeling.entity.CapacityChangeRule
import ksl.modeling.entity.CapacitySchedule
import ksl.modeling.entity.Resource
import ksl.modeling.nhpp.PiecewiseConstantRateFunction
import ksl.utilities.divideConstant
import ksl.modeling.nhpp.NHPPPiecewiseRateFunctionEventGenerator
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.variable.Counter
import ksl.modeling.variable.IndicatorResponse
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseSchedule
import ksl.modeling.variable.TWResponse
import ksl.utilities.distributions.Triangular
import ksl.utilities.distributions.Uniform
import ksl.modeling.entity.ResourcePoolAllocation
import ksl.modeling.entity.ResourcePoolWithQ


fun main() {
    val m = Model()
    val mixer = ProposedModelClass2(m, "Proposed Model")
    //mixer.operatorResource.initialCapacity = Int.MAX_VALUE
//  mixer.warningTime = 30.0
    m.lengthOfReplication = 12.0*60.0
    m.numberOfReplications = 30
    m.simulate()
    m.print()

}

class ProposedModelClass2(
    parent: ModelElement,
    name: String? = null
) : ProcessModel(parent, name) {

    //random variables

    //probability of silver CH
    private val myProbSilverCHRV = RandomVariable(
        this,
        BernoulliRV(0.68))

    //service time and after call time distributions
    private val myInfoDistReg = Triangular(1.2, 2.05, 3.75)
    private val myResDistReg = Triangular(2.25, 2.95, 8.6)
    private val myChangeDistReg = Triangular(1.2, 1.9, 5.8)

    private val myInfoDistSilver = Triangular(1.2*.95, 2.05*.95, 3.75*.95)
    private val myResDistSilver = Triangular(2.25*.95, 2.95*.95, 8.6*.95)
    private val myChangeDistSilver = Triangular(1.2*.95, 1.9*.95, 5.8*.95)

    private val myInfoDistGold = Triangular(1.2*.88, 2.05*.88, 3.75*.88)
    private val myResDistGold = Triangular(2.25*.88, 2.95*.88, 8.6*.88)
    private val myChangeDistGold = Triangular(1.2*.88, 1.9*.88, 5.8*.88)

    private val myInfoACWDist = Uniform(0.05, 0.1)
    private val myResACWDist = Uniform(0.5, 0.8)
    private val myChangeACWDist = Uniform(0.4, 0.6)




    //service time RVs (times all in minutes)


    private val myInfoServiceRVReg = RandomVariable(this, TriangularRV(1.2, 2.05, 3.75))
    private val myResServiceRVReg = RandomVariable(this, TriangularRV(2.25, 2.95, 8.6))
    private val myChangeServiceRVReg = RandomVariable(this, TriangularRV(1.2, 1.9, 5.8))

    private val myInfoServiceRVSilver = RandomVariable(this, TriangularRV(1.2*.95, 2.05*.95, 3.75*.95))
    private val myResServiceRVSilver = RandomVariable(this, TriangularRV(2.25*.95, 2.95*.95, 8.6*.95))
    private val myChangeServiceRVSilver = RandomVariable(this, TriangularRV(1.2*.95, 1.9*.95, 5.8*.95))

    private val myInfoServiceRVGold = RandomVariable(this, TriangularRV(1.2*.88, 2.05*.88, 3.75*.88))
    private val myResServiceRVGold = RandomVariable(this, TriangularRV(2.25*.88, 2.95*.88, 8.6*.88))
    private val myChangeServiceRVGold = RandomVariable(this, TriangularRV(1.2*.88, 1.9*.88, 5.8*.88))

    //after call work RVs (times all in minutes)


    private val myInfoACWRV = RandomVariable(this, UniformRV(0.05, 0.1))
    private val myResACWRV = RandomVariable(this, UniformRV(0.5, 0.8))
    private val myChangeACWRV = RandomVariable(this, UniformRV(0.4, 0.6))




    //call type RV
    //info = 1.0, reservations = 2.0, changes = 3.0
    private val callTypeValues = doubleArrayOf(1.0, 2.0, 3.0)
    private val callTypeCDF = doubleArrayOf(0.11, 0.845, 1.0)
    private val myCallTypeRV = DEmpiricalRV(callTypeValues, callTypeCDF)

    // wait time tolerance RVs
    private val myCardholderWaitToleranceRV = RandomVariable(
        this,
        GeneralizedBetaRV(0.7544289788797895, 0.6436735903751223, 7.8802040816326535, 17.049795918367348)
    )
    private val myRegularWaitToleranceRV = RandomVariable(
        this,
        UniformRV(11.649183673469388, 30.050816326530615))



    //delay for entering cardholder number RV (time in minutes

    private val myEnterCardholderNumberRV = RandomVariable(this, UniformRV(7.0 / 60.0, 16.0 / 60.0))

    //operator resource


   // private val operatorQueue = RequestQ(this, "Operator Queue", Queue.Discipline.RANKED)

    private val regularOperators: Resource = Resource(this, "Regular Operators", capacity = 20)
    private val silverOperators: Resource = Resource(this, "Silver Operators", capacity = 20)
    private val goldOperators: Resource = Resource(this, "Gold Operators", capacity = 20)

    private val regularList: List<Resource> = listOf(regularOperators)
    private val silverList: List<Resource> = listOf(silverOperators, regularOperators)
    private val goldList: List<Resource> = listOf(goldOperators,silverOperators, regularOperators)

    private val regularOperatorPool = ResourcePoolWithQ(this, regularList, name = "Regular Operator Pool")
    private val silverOperatorPool = ResourcePoolWithQ(this, silverList, name = "Silver Operator Pool")
    private val goldOperatorPool = ResourcePoolWithQ(this, goldList, name = "Gold Operator Pool")

    //resource response for trunklines

    private val myTrunkLines: TWResponse = TWResponse(parent = this, name = "Trunk Lines")
    private var numLines = 55
    private var numReserved = 3


    // counter for cardholder busy signals

    private val myCountOfCardHolderBusySignals: Counter = Counter(parent = this, name = "Count of Cardholder Busy Signals")

    // counter for regular busy signals

    private val myCountOfRegularBusySignals: Counter = Counter(parent = this, name = "Count of Regular Busy Signals")

    // counters for calls abandoned because of wait time

    private val myCountOfAbandonedCalls: Counter = Counter(parent = this, name = "Count of Abandoned Calls from Waiting")



    //count of people that enter the system and get a trunk line
    private val myNumSysTotalAfterTrunkLine = Counter(parent = this, name = "Total Number in System After Trunk Line")


    //count of people categorized by caller type that call in
    private val myNumSysRegular = Counter(parent = this, name = "Number of Regular Customers in System")
    private val myNumSysCardholder = Counter(parent = this, name = "Number of Cardholder Customers in System")


    //performance measure percentages
    private val percentBusySignalsCardholder = Response(parent = this, name = "% Cardholder Busy Signals")
    private val percentBusySignalsRegular = Response(parent = this, name = "% Regular Busy Signals")
    private val percentAbandonedCalls = Response(parent = this, name = "% Abandoned Calls for All Customers")

    //tracking wait time by customer type
    private val myQTimeRegular = Response(parent = this, name = "Regular Customers Time in Queue")
    private val myQTimeSilver = Response(parent = this, name = "Silver Customers Time in Queue")
    private val myQTimeGold = Response(parent = this, name = "Gold Customers Time in Queue")

    private val myProbRegWait: IndicatorResponse = IndicatorResponse(
        {x -> x <= 5.0},
        myQTimeRegular,
        name= "Probability Regular Q Time <= 5 min") //should be 85% or more

    private val myProbSilverWait: IndicatorResponse = IndicatorResponse(
        {x -> x <= 2.0},
        myQTimeSilver,
        name= "Probability Silver Q Time <= 2 min") //should be 95% or more

    private val myProbGoldWait: IndicatorResponse = IndicatorResponse(
        {x -> x <= 1.0},
        myQTimeGold,
        name= "Probability Gold Q Time <= 1 min") //should be 98% or more

    private val myRegCounter = TWResponse(this, "Reg Counter")
    private val mySilverCounter = TWResponse(this, "Silver Counter")
    private val myGoldCounter = TWResponse(this, "Gold Counter")



    //arrival rate functions for regular and cardholder customers (times all in minutes)
    private val rateFunctionCardHolders: PiecewiseConstantRateFunction
    private val rateFunctionRegular: PiecewiseConstantRateFunction


    private val myRegularOperatorSchedule: CapacitySchedule = CapacitySchedule(this, 0.0)
    private val hourlyRegularResponseSchedule = ResponseSchedule(this, 0.0, name = "Hourly Regular")
    private val mySilverOperatorSchedule: CapacitySchedule = CapacitySchedule(this, 0.0)
    private val hourlySilverResponseSchedule = ResponseSchedule(this, 0.0, name = "Hourly Silver")
    private val myGoldOperatorSchedule: CapacitySchedule = CapacitySchedule(this, 0.0)
    private val hourlyGoldResponseSchedule = ResponseSchedule(this, 0.0, name = "Hourly Gold")

    init {
        // set up the generator
        val durations = doubleArrayOf(
            60.0, 60.0, 60.0, 60.0, 60.0, 60.0,
            60.0, 60.0, 60.0, 60.0, 60.0, 60.0
        )
        val hourlyRatesCardholder = doubleArrayOf(
            87.0, 244.6, 218.5, 181.5, 299.3, 493.6,
            389.9, 342.2, 244.9, 271.3, 147.0, 68.3
        )
        val hourlyRatesRegular = doubleArrayOf(
            89.0, 170.1, 232.9, 325.1, 280.0, 443.1,
            268.4, 345.6, 175.3, 272.8, 116.8, 52.0
        )

        val ratesPerMinuteCardholder = hourlyRatesCardholder.divideConstant(60.0)
        val ratesPerMinuteRegular = hourlyRatesRegular.divideConstant(60.0)

        rateFunctionCardHolders = PiecewiseConstantRateFunction(durations, ratesPerMinuteCardholder)
        rateFunctionRegular = PiecewiseConstantRateFunction(durations, ratesPerMinuteRegular)


        myRegularOperatorSchedule.addItem(7, duration = 60.0) // 7
        myRegularOperatorSchedule.addItem(13, duration = 60.0) //8
        myRegularOperatorSchedule.addItem(18, duration = 60.0)//9
        myRegularOperatorSchedule.addItem(25, duration = 60.0) // 10
        myRegularOperatorSchedule.addItem(33, duration = 60.0) //11
        myRegularOperatorSchedule.addItem(33, duration = 60.0)//12
        myRegularOperatorSchedule.addItem(33, duration = 60.0) // 1
        myRegularOperatorSchedule.addItem(33, duration = 60.0) //2
        myRegularOperatorSchedule.addItem(26, duration = 60.0)//3
        myRegularOperatorSchedule.addItem(20, duration = 60.0) // 4
        myRegularOperatorSchedule.addItem(15, duration = 60.0) //5
        myRegularOperatorSchedule.addItem(8, duration = 60.0)//6
        regularOperators.useSchedule(myRegularOperatorSchedule, CapacityChangeRule.IGNORE)

        mySilverOperatorSchedule.addItem(5, duration = 60.0) // 7
        mySilverOperatorSchedule.addItem(13, duration = 60.0) // 8
        mySilverOperatorSchedule.addItem(13, duration = 60.0) // 9
        mySilverOperatorSchedule.addItem(16, duration = 60.0) // 10
        mySilverOperatorSchedule.addItem(27, duration = 60.0) // 11
        mySilverOperatorSchedule.addItem(27, duration = 60.0) // 12
        mySilverOperatorSchedule.addItem(27, duration = 60.0) // 1
        mySilverOperatorSchedule.addItem(27, duration = 60.0) // 2
        mySilverOperatorSchedule.addItem(22, duration = 60.0) // 3
        mySilverOperatorSchedule.addItem(14, duration = 60.0) // 4
        mySilverOperatorSchedule.addItem(14, duration = 60.0) // 5
        mySilverOperatorSchedule.addItem(11, duration = 60.0) // 6
        silverOperators.useSchedule(mySilverOperatorSchedule, CapacityChangeRule.IGNORE)

        myGoldOperatorSchedule.addItem(2, duration = 60.0) // 7
        myGoldOperatorSchedule.addItem(6, duration = 60.0) // 8
        myGoldOperatorSchedule.addItem(6, duration = 60.0) // 9
        myGoldOperatorSchedule.addItem(7, duration = 60.0) // 10
        myGoldOperatorSchedule.addItem(12, duration = 60.0) // 11
        myGoldOperatorSchedule.addItem(12, duration = 60.0) // 12
        myGoldOperatorSchedule.addItem(12, duration = 60.0) // 1
        myGoldOperatorSchedule.addItem(12, duration = 60.0) // 2
        myGoldOperatorSchedule.addItem(10, duration = 60.0) // 3
        myGoldOperatorSchedule.addItem(6, duration = 60.0) // 4
        myGoldOperatorSchedule.addItem(6, duration = 60.0) // 5
        myGoldOperatorSchedule.addItem(5, duration = 60.0) // 6
        goldOperators.useSchedule(myGoldOperatorSchedule, CapacityChangeRule.IGNORE)


    }

    init {

        //not sure if i'm doing these right
        hourlyRegularResponseSchedule.scheduleRepeatFlag = false
        hourlyRegularResponseSchedule.addIntervals(0.0, 12, 60.0)
        hourlyRegularResponseSchedule.addResponseToAllIntervals(regularOperators.numBusyUnits)
        hourlyRegularResponseSchedule.addResponseToAllIntervals(regularOperators.timeAvgInstantaneousUtil)
        //  hourlyRegularResponseSchedule.addResponseToAllIntervals(regularOperators.waitingQ.timeInQ)

        hourlySilverResponseSchedule.scheduleRepeatFlag = false
        hourlySilverResponseSchedule.addIntervals(0.0, 12, 60.0)
        hourlySilverResponseSchedule.addResponseToAllIntervals(silverOperators.numBusyUnits)
        hourlySilverResponseSchedule.addResponseToAllIntervals(silverOperators.timeAvgInstantaneousUtil)
        //   hourlySilverResponseSchedule.addResponseToAllIntervals(silverOperators.waitingQ.timeInQ)

        hourlyGoldResponseSchedule.scheduleRepeatFlag = false
        hourlyGoldResponseSchedule.addIntervals(0.0, 12, 60.0)
        hourlyGoldResponseSchedule.addResponseToAllIntervals(goldOperators.numBusyUnits)
        hourlyGoldResponseSchedule.addResponseToAllIntervals(goldOperators.timeAvgInstantaneousUtil)
        //    hourlyGoldResponseSchedule.addResponseToAllIntervals(goldOperators.waitingQ.timeInQ)
    }

    override fun replicationEnded() {
        percentAbandonedCalls.value = myCountOfAbandonedCalls.value/myNumSysTotalAfterTrunkLine.value //no more than 5%
        percentBusySignalsRegular.value = myCountOfRegularBusySignals.value/myNumSysRegular.value //no more than 10%
        percentBusySignalsCardholder.value = myCountOfCardHolderBusySignals.value/myNumSysCardholder.value //no more than 1%


    }

    //arrival event generators
    private val generatorCardholder = NHPPPiecewiseRateFunctionEventGenerator(
        this, this::createCardholderCall,
        rateFunctionCardHolders
    )
    private val generatorRegular = NHPPPiecewiseRateFunctionEventGenerator(
        this, this::createRegularCall,
        rateFunctionRegular
    )

    //arrivals for customers
    private fun createRegularCall(generator: EventGeneratorIfc) {
        val call = Call(type = "R") //creating regular
        call.priority = 3
        activate(call.callProcess)
    }
    private fun createCardholderCall(generator: EventGeneratorIfc) {
        if (myProbSilverCHRV.value == 1.0) {
            val call = Call(type = "S") //creating silver
            call.priority = 2
            activate(call.callProcess)
        } else {
            val call = Call(type = "G") //creating gold
            call.priority = 1
            activate(call.callProcess)
        }

    }



    //entity and process
    private inner class Call(val type: String) : Entity() {
        val callType = myCallTypeRV.value
        val waitTolerance = if (type == "R") myRegularWaitToleranceRV.value else myCardholderWaitToleranceRV.value
        //unsure about this too


        val callProcess: KSLProcess = process() {

            //getting service time and after call time based on cal type
            val serviceTime: Double
            val afterCallTime: Double
            if (callType == 1.0) {
                afterCallTime = myInfoACWRV.value
                if (type =="R") {
                    serviceTime = myInfoServiceRVReg.value
                }
                else if (type =="S") {
                    serviceTime = myInfoServiceRVSilver.value
                }
                else {
                    serviceTime = myInfoServiceRVGold.value
                }

            }
            else if (callType == 2.0) {
                afterCallTime = myResACWRV.value
                if (type =="R") {
                    serviceTime = myResServiceRVReg.value
                }
                else if (type =="S") {
                    serviceTime = myResServiceRVSilver.value
                }
                else {
                    serviceTime = myResServiceRVGold.value
                }
            }
            else {
                afterCallTime = myChangeACWRV.value
                if (type =="R") {
                    serviceTime = myChangeServiceRVReg.value
                }
                else if (type =="S") {
                    serviceTime = myChangeServiceRVSilver.value
                }
                else {
                    serviceTime = myChangeServiceRVGold.value
                }
            }


            // increment customer numbers based on type for busy signal calculation
            if (type == "R") {
                myNumSysRegular.increment()
            } else {
                myNumSysCardholder.increment()
            }

            //trunk lines are busy
            if (myTrunkLines.value >= (numLines - numReserved) && type == "R") {

                myCountOfRegularBusySignals.increment()}

            else if (myTrunkLines.value >= numLines ){
                myCountOfCardHolderBusySignals.increment()

                // trunk lines are not busy
            } else {
                myTrunkLines.increment()
                myNumSysTotalAfterTrunkLine.increment() //for percent abandoned call calculation

                if (type == "R") {
                    myRegCounter.increment()
                }
                else if (type == "S") {
                    mySilverCounter.increment()
                }
                else {
                    myGoldCounter.increment()
                }


                //if cardholder enter number
                if (type != "R") {
                    delay(myEnterCardholderNumberRV)
                }

                // wait time estimate
                var sumOfExpectedWait = 0.0

                if (type == "R"){
                    sumOfExpectedWait = (myRegCounter.value*(myInfoDistReg.mode * .11 + myResDistReg.mode * .735 + myChangeDistReg.mode * .155)) + //number of each customer times their average service time
                            (mySilverCounter.value*(myInfoDistSilver.mode*.11 + myResDistSilver.mode*.735 + myChangeDistSilver.mode*.155)) + //percent weights come from PMF
                            (myGoldCounter.value*(myInfoDistGold.mode*.11 + myResDistGold.mode*.735 + myChangeDistGold.mode*.155)) //regular wait behind regular, silver, and gold
                }
                else if(type=="S"){
                    sumOfExpectedWait = (mySilverCounter.value*(myInfoDistSilver.mode*.11 + myResDistSilver.mode*.735 + myChangeDistSilver.mode*.155)) +
                            (myGoldCounter.value*(myInfoDistGold.mode*.11 + myResDistGold.mode*.735 + myChangeDistGold.mode*.155)) //silver waits behimd silver and gold
                }
                else{
                    sumOfExpectedWait =  (myGoldCounter.value*(myInfoDistGold.mode*.11 + myResDistGold.mode*.735 + myChangeDistGold.mode*.155)) //gold waits behing gold
                }



                var x = 0.0
                val waitTimeEst: Double

                if(type=="R"){
                    if (!regularOperatorPool.hasAvailableUnits) {
                        delay(8.0 / 60)
                        waitTimeEst = sumOfExpectedWait/regularOperatorPool.numBusyUnits.value
                        if (waitTimeEst > waitTolerance) {
                            x = 1.0

                        }
                    }
                }
                else if(type=="S"){
                    if (!silverOperatorPool.hasAvailableUnits) {
                        delay(8.0 / 60)
                        waitTimeEst = sumOfExpectedWait/silverOperatorPool.numBusyUnits.value
                        if (waitTimeEst > waitTolerance) {
                            x = 1.0

                        }
                    }

                }
                else{
                    if (!goldOperatorPool.hasAvailableUnits) {
                        delay(8.0 / 60)
                        waitTimeEst = sumOfExpectedWait/goldOperatorPool.numBusyUnits.value
                        if (waitTimeEst > waitTolerance) {
                            x = 1.0

                        }
                    }
                }


                if (x == 0.0) { //if there is no wait or they are willing to wait
                    val timeStartQueue = time

                    val a: ResourcePoolAllocation

                    //tracking queue time by customer type
                    if(type=="R"){
                        a = seize(regularOperatorPool)
                        myQTimeRegular.value = time - timeStartQueue
                    }
                    else if(type=="S"){
                        a = seize(silverOperatorPool)
                        myQTimeSilver.value = time -timeStartQueue
                    }
                    else{
                        a = seize(goldOperatorPool)
                        myQTimeGold.value = time - timeStartQueue
                    }

                    //service time

                    delay(serviceTime)

                    myTrunkLines.decrement()//release trunkline

                    if (type == "R") {
                        myRegCounter.decrement()
                    }
                    else if (type == "S") {
                        mySilverCounter.decrement()
                    }
                    else {
                        myGoldCounter.decrement()
                    }

                    //after call work
                    delay(afterCallTime)

                    //release resource
                    release(a)

                } else {
                    myCountOfAbandonedCalls.increment() //if they are not willing to wait they abandon call

                    if (type == "R") {
                        myRegCounter.decrement()
                    }
                    else if (type == "S") {
                        mySilverCounter.decrement()
                    }
                    else {
                        myGoldCounter.decrement()
                    }

                }

            }

        }

    }
}