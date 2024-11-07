package ksl.examples.general.models

import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.entity.ResourceWithQCIfc
import ksl.modeling.station.*
import ksl.modeling.variable.*
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.io.dbutil.KSLDatabaseObserver
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.UniformRV

fun main() {
    val model = Model("TA2V3", autoCSVReports = true)
    model.numberOfReplications = 10
    model.lengthOfReplication = 200000.0
    model.lengthOfReplicationWarmUp = 50000.0
    val dts = DumpTruckSystemProcessView(model, name = "DumpTruckSystem")
    model.experimentName = "SlowLoader"
    dts.loaders.initialCapacity = 2
    dts.loadingTimeRV.initialRandomSource = UniformRV(12.0, 24.0, 3)
    model.simulate()
    model.print()
}

class DumpTruckSystemProcessView(
    parent: ModelElement,
    numLoaders: Int = 2,
    numTrucks: Int = 8,
    numScales: Int = 1,
    weighingTime: RandomIfc = UniformRV(1.0, 9.0, 1),
    loadingTime: RandomIfc = UniformRV(12.0, 24.0, 3),
    traveledTime: RandomIfc = ExponentialRV(85.0, 2),
    name: String? = null
) : ProcessModel(parent, name = name) {

    init {
        require(numLoaders >= 1) { "The number of loaders must be >= 1." }
        require(numTrucks >= 1) { "The number of trucks must be >= 1." }
        require(numScales >= 1) { "The number of scales must be >= 1." }
    }

    var numTrucks = numTrucks
        set(value) {
            require(value > 0)
            require(!model.isRunning) { "Cannot change the number of trucks while the model is running!" }
            field = value
        }

    private var myWeighingTimeRV: RandomVariable = RandomVariable(this, weighingTime)
    val weighingTimeRV: RandomSourceCIfc
        get() = myWeighingTimeRV

    private var myTravelTimeRV: RandomVariable = RandomVariable(this, traveledTime)
    val travelTimeRV: RandomSourceCIfc
        get() = myTravelTimeRV

    private var myLoadingTimeRV: RandomVariable = RandomVariable(this, loadingTime)
    val loadingTimeRV: RandomSourceCIfc
        get() = myLoadingTimeRV

    private val myLoaders: ResourceWithQ = ResourceWithQ(this, "Loaders", numLoaders)
    val loaders: ResourceWithQCIfc
        get() = myLoaders

    private val myScales: ResourceWithQ = ResourceWithQ(this, "Scales", numScales)
    val scales: ResourceWithQCIfc
        get() = myScales

    private val myResponseTime: Response = Response(this, "${this.name}:ResponseTime")
    val responseTime: ResponseCIfc
        get() = myResponseTime

    override fun initialize() {
        for (i in 1..numTrucks) {
            val truck = Truck2("Truck $i")
            activate(truck.miningProcess)
        }
    }

    private inner class Truck(name: String? = null) : Entity(name) {
        val miningProcess: KSLProcess = process ("Truck Process") {
            while (true) {
                timeStamp = time
                val loader = seize(myLoaders)
                delay(myLoadingTimeRV)
                release(loader)
                val scale = seize(myScales)
                delay(myWeighingTimeRV)
                release(scale)
                myResponseTime.value = time - timeStamp
                delay(myTravelTimeRV)
            }



        }
    }

    private inner class Truck2(name: String? = null) : Entity(name) {
        val miningProcess: KSLProcess = process("Truck2 Process") {
            timeStamp = time
            val loader = seize(myLoaders)
            delay(myLoadingTimeRV)
            release(loader)
            val scale = seize(myScales)
            delay(myWeighingTimeRV)
            release(scale)
            myResponseTime.value = time - timeStamp
//            delay(myTravelTimeRV)
            //TODO this causes an error because processes (the process coroutine) are essentially one-shot, once completed, they cannot be reused.
            // each entity created get a new attributes assigned and thus they have new processes that can be run
            // we cannot reuse processes under this paradigm.

                   schedule(this@DumpTruckSystemProcessView::restartProcess, myTravelTimeRV, message = this@Truck2)
            //     activate(entity.miningProcess, myTravelTimeRV)
            //      delay(myTravelTimeRV)
        }

//        override fun afterRunningProcess(completedProcess: KSLProcess) {
//            //TODO this causes an error because hasCurrentProcess is true, because myCurrentProcess is not null
//            activate(completedProcess)
//        }
    }

    private fun restartProcess(event: KSLEvent<Truck2>){
        val truck = event.message!!
        activate(truck.miningProcess)
    }

}