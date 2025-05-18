package ksl.examples.general.lectures.week9

import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.entity.ResourceWithQCIfc
import ksl.modeling.variable.*
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.BernoulliRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.TriangularRV

fun main(){
    val m = Model()
    val dtp = Airport(m, name = "Airport")
    m.numberOfReplications = 30
    m.lengthOfReplication = 600.0
    m.simulate()
    m.print()
}

class Airport(
    parent: ModelElement,
    inspectors: Int = 1,
    name: String? = null
) : ProcessModel(parent, name = name) {

    var numInspectors = inspectors
        set(value) {
            require(value > 0)
            require(!model.isRunning) { "Cannot change the number of inspectors while the model is running!" }
            field = value
        }

    private var myInspectionTimeRV: RandomVariable = RandomVariable(this,
        TriangularRV(0.75, 1.5, 3.0, 2))
    val inspectionRV: RandomVariableCIfc
        get() = myInspectionTimeRV
    private var myArrivalRV: RandomVariable = RandomVariable(this , ExponentialRV(2.0, 1))
    val arrivalRV: RandomVariableCIfc
        get() = myArrivalRV

    private var myPassRV : RandomVariable = RandomVariable(this, BernoulliRV(0.93, 3))

    private val myInspectors: ResourceWithQ = ResourceWithQ(this, "Security Inspectors", inspectors)

    val inspectors: ResourceWithQCIfc
        get() = myInspectors

    private val myNS: TWResponse = TWResponse(this, "Num in System")
    val numInSystem: TWResponseCIfc
        get() = myNS
    private val mySysTime: Response = Response(this, "System Time")
    val systemTime: ResponseCIfc
        get() = mySysTime

    private val myNumCleared: Counter = Counter(this, "Num Cleared")
    val numPassengersCleared: CounterCIfc
        get() = myNumCleared

    private val myNumDenied: Counter = Counter(this, "Num Denied")
    val numPassengersDenied: CounterCIfc
        get() = myNumDenied

    private val myWaitTime: Response = Response(this, "Wait Time")
    val waitTime: ResponseCIfc
        get() = myWaitTime
    private val myWTGT5: IndicatorResponse = IndicatorResponse({ x -> x >= 5.0 },
        myWaitTime, "WaitTime >= 5 minutes")

    private val myWTGT5V2: IndicatorResponse = IndicatorResponse({ x -> x >= 5.0 },
        myInspectors.waitingQ.timeInQ as Response, "WT >= 5 minutes")
    val probWaitTimeGT5Minutes: ResponseCIfc
        get() = myWTGT5

    override fun initialize() {
        schedule(this::arrival, myArrivalRV)
    }

    private fun arrival(event: KSLEvent<Nothing>) {
        val passenger = Passenger()
        activate(passenger.securityProcess, 0.0)
        schedule(this::arrival, myArrivalRV)
    }

    private inner class Passenger : Entity() {
        val securityProcess: KSLProcess = process() {
            myNS.increment()
           val a = seize(myInspectors)
            myWaitTime.value = time - createTime
            delay(myInspectionTimeRV)
            release(a)
            if (myPassRV.value == 1.0){
                myNumCleared.increment()
            } else {
                myNumDenied.increment()
            }
            mySysTime.value = time - createTime
            myNS.decrement()
        }
    }
}