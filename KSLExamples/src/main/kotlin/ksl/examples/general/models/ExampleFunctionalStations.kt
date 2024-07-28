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
import kotlin.reflect.KFunction

fun main() {
    val sim = Model("FunctionalExample")
    sim.numberOfReplications = 30
    sim.lengthOfReplication = 30000.0
//    sim.numberOfReplications = 1
//    sim.lengthOfReplication = 100.0
    val tq = InspectionSystem(sim, name = "InspectionSystem")
    sim.simulate()
    sim.print()
}

class InspectionSystem(
    parent: ModelElement,
    timeBtwArrivals: RandomIfc = ExponentialRV(6.0, 1),
    inspectionTime: RandomIfc = ExponentialRV(10.0, 2),
    adjustmentTime: RandomIfc = UniformRV(7.0, 14.0, 3),
    name: String? = null
) : ModelElement(parent, name = name) {

    private var myArrivalRV: RandomVariable = RandomVariable(parent, timeBtwArrivals)
    val arrivalRV: RandomSourceCIfc
        get() = myArrivalRV

    private val myArrivalGenerator: EventGenerator = EventGenerator(
        this,
        timeUntilFirstRV = myArrivalRV, timeBtwEventsRV = myArrivalRV
    )

    private val myInspectionStation: SingleQStation = SingleQStation(
        this, inspectionTime,
        SResource(this, capacity = 2, "${this.name}:Inspectors"),
        name = "${this.name}:Inspection"
    )
    val inspectionStation: SingleQStationCIfc
        get() = myInspectionStation

    private val myAdjustmentStation: SingleQStation = SingleQStation(
        this, adjustmentTime,
        name = "${this.name}:Adjustments"
    )
    val adjustmentStation: SingleQStationCIfc
        get() = myAdjustmentStation

    private val mySysTime: Response = Response(this, "${this.name}:TotalSystemTime")
    val totalSystemTime: ResponseCIfc
        get() = mySysTime

    private val myNumAdjustments: Response = Response(this, "${this.name}:NumAdjustments")
    val numAdjustments: ResponseCIfc
        get() = myNumAdjustments

    private val myExit = QObjectReceiverIfc { arrivingQObject ->
        mySysTime.value = time - arrivingQObject.createTime
        val yBox = arrivingQObject as YBox
        myNumAdjustments.value = yBox.numAdjustments.toDouble()
    }

    private val myInspectDecide = TwoWayByChanceSender(
        BernoulliPicker(0.82, myExit, myAdjustmentStation, streamNum = 4)
    )

    init {
        myArrivalGenerator.generatorAction { myInspectionStation.receive(YBox()) }
        myAdjustmentStation.exitAction { (it as YBox).numAdjustments++ }
        myInspectionStation.nextReceiver(myInspectDecide)
        myAdjustmentStation.nextReceiver(myInspectionStation)
    }

    private inner class YBox() : QObject() {
        var numAdjustments = 0
    }
}