package ksl.examples.general.models

import ksl.modeling.elements.EventGenerator
import ksl.modeling.station.*
import ksl.modeling.variable.RandomSourceCIfc
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.robj.BernoulliPicker
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.UniformRV

fun main(){
    val sim = Model("Exercise 5_6")
//    sim.numberOfReplications = 30
//    sim.lengthOfReplication = 30000.0
    sim.numberOfReplications = 1
    sim.lengthOfReplication = 100.0
    val tq = YBoxInspectionSystem(sim, name = "InspectionSystem")
    sim.simulate()
    sim.print()
}

class YBoxInspectionSystem(
    parent: ModelElement,
    timeBtwArrivals: RandomIfc = ExponentialRV(6.0, 1),
    inspectionTime: RandomIfc = ExponentialRV(10.0, 2),
    adjustmentTime: RandomIfc = UniformRV(7.0, 14.0, 3),
    name: String? = null
): ModelElement(parent, name = name) {

    private var myArrivalRV: RandomVariable = RandomVariable(parent, timeBtwArrivals)
    val arrivalRV: RandomSourceCIfc
        get() = myArrivalRV

    private val myArrivalGenerator: EventGenerator = EventGenerator(this,
        this::arrivalEvent, myArrivalRV, myArrivalRV)

    private val myInspectionStation: SingleQStation = SingleQStation(
        this, inspectionTime,
        SResource(this, capacity = 2, "${this.name}:Inspectors"),
        name= "${this.name}:Inspection")
    val inspectionStation: SingleQStationCIfc
        get() = myInspectionStation

    private val myAdjustmentStation: AdjustmentStation = AdjustmentStation(
        this, adjustmentTime,
        name= "${this.name}:Adjustments")
    val adjustmentStation: SingleQStationCIfc
        get() = myAdjustmentStation

    private val mySysTime: Response = Response(this, "${this.name}:TotalSystemTime")
    val totalSystemTime: ResponseCIfc
        get() = mySysTime

    private val myNumAdjustments: Response = Response(this, "${this.name}:NumAdjustments")
    val numAdjustments: ResponseCIfc
        get() = myNumAdjustments

    private val myExit = ExitSystem()

    private val myInspectDecide = TwoWayByChanceSender(
        BernoulliPicker(0.82, myExit, myAdjustmentStation, streamNum = 4)
    )

    init {
        myInspectionStation.nextReceiver(myInspectDecide)
        myAdjustmentStation.nextReceiver(myInspectionStation)
        myAdjustmentStation.exitAction {
            (it as YBox).numAdjustments++
            println("incremented number of adjustments")
        }
    }

    private inner class YBox() : QObject() {
        var numAdjustments = 0
    }

    private fun arrivalEvent(generator: EventGenerator){
        val customer = YBox()
        myInspectionStation.receive(customer)
    }

    private inner class ExitSystem : QObjectReceiverIfc {
        override fun receive(arrivingQObject: QObject) {
            mySysTime.value = time - arrivingQObject.createTime
            val yBox = arrivingQObject as YBox
            myNumAdjustments.value = yBox.numAdjustments.toDouble()
        }
    }

    private inner class AdjustmentStation(
        parent: ModelElement,
        activityTime: RandomIfc,
        resource: SResource? = null,
        nextReceiver: QObjectReceiverIfc = NotImplementedReceiver,
        name: String? = null
    ): SingleQStation(parent, activityTime, resource, nextReceiver, name){

        override fun onExit(completedQObject: QObject) {
            super.onExit(completedQObject)
            val yBox = completedQObject as YBox
            yBox.numAdjustments++
        }
    }
}