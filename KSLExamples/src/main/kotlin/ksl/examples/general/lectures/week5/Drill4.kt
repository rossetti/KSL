package ksl.examples.general.lectures.week5

import ksl.modeling.queue.Queue
import ksl.modeling.variable.*
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.BernoulliRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.TriangularRV

fun main(){
    val model = Model()
    model.numberOfReplications = 20
    model.lengthOfReplication = 600.0
    val airportSecurity = AirportSecurity(model, numInspectors = 1, name = "CheckPoint1")
//    val airportSecurity2 = AirportSecurity(model, numInspectors = 1, name = "CheckPoint2")
    model.simulate()
    model.print()
}

class AirportSecurity(
    parent: ModelElement,
    numInspectors: Int = 1,
    name: String?
): ModelElement(parent, name){

    // parameter initialization code
    private val myNumInspectors = numInspectors

    // random variable definitions
    private var myInspectionTimeRV: RandomVariable
        = RandomVariable(this, TriangularRV(0.75, 1.5, 3.0, 2))

    private var myArrivalRV: RandomVariable
            = RandomVariable(this, ExponentialRV(2.0, 1))

    private var myPassRV: RandomVariable
            = RandomVariable(this, BernoulliRV(0.93, 3))

    // KSL constructs

    private val myInspectionQueue: Queue<QObject> = Queue(this, "${this.name} Inspection Queue")

    private val myNumBusyInspectors: TWResponse = TWResponse(this, "${this.name} Number of Busy Inspectors")
    private val myUtil: TWResponseFunction = TWResponseFunction({ x -> x / (myNumInspectors) }, myNumBusyInspectors, "${this.name}:InspectorUtil")

    private val mySysTime = Response(this, "${this.name} System Time")

    private val myCountDenied = Counter(this, "${this.name} Denied count" )
    private val myCountPassed = Counter(this, "${this.name} Passed count" )

    // event logic
    private val myArrivalAction: EventAction<Nothing> = Arrival()
    private val myDepartureAction: EventAction<QObject> = Departure()

    override fun initialize() {
        super.initialize()
        schedule(myArrivalAction, myArrivalRV)
    }

    private inner class Arrival: EventAction<Nothing>(){
        override fun action(event: KSLEvent<Nothing>) {
           // println("$time : Arrival")
            val arrivingPassenger = QObject() // create the arriving customer
            myInspectionQueue.enqueue(arrivingPassenger) // passenger enters the queue
            if(myNumBusyInspectors.value < myNumInspectors) {
                // if server is available
                myNumBusyInspectors.increment()
                val nextPassenger = myInspectionQueue.removeNext()
                schedule(myDepartureAction, myInspectionTimeRV, message = nextPassenger)
            }
            schedule(myArrivalAction, myArrivalRV)
        }
    }

    private inner class Departure: EventAction<QObject>(){
        override fun action(event: KSLEvent<QObject>) {
            //println("$time : Departure")
            myNumBusyInspectors.decrement()
            if (myInspectionQueue.isNotEmpty){
                myNumBusyInspectors.increment()
                val nextPassenger = myInspectionQueue.removeNext()
                schedule(myDepartureAction, myInspectionTimeRV, message = nextPassenger)
            }
            val departingPassenger = event.message!!
            //mySysTime.value = time - departingPassenger.createTime
            if (myPassRV.value == 1.0){
                mySysTime.value = time - departingPassenger.createTime
                myCountPassed.increment()
            } else {
                myCountDenied.increment()
            }
           /// TODO("Not yet implemented")
        }
    }
}