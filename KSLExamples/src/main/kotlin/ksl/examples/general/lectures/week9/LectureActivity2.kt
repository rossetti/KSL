package ksl.examples.general.lectures.week9

import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.Counter
import ksl.modeling.variable.IndicatorResponse
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.*

/*
Activity 1 Basic Process Simulation
*/
fun main() {
    val m = Model()
    ProductionSystemActivity(m, name = "ProductionSystem")
    m.numberOfReplications = 100
    m.lengthOfReplication = 30.0*24.0*60.0
    m.simulate()
    m.print()
}

/*
A small manufacturing system produces parts.  The parts arrive from an upstream Poisson process with a rate of arrival
 of 1 part every 5 minutes (stream 1).  All parts that enter the system must go through a preparation station
 where there are 2 preparation workers.  A part requires only 1 of the 2 workers during preparation.  The preparation
 time is exponentially distributed with means of 8 minutes (stream 2). If all the workers at the preparation station
 are busy, then the part waits for the next available preparation worker.

After preparation, the parts are processed on two different production lines.  There is a 30% chance that the parts
 are built on line 1 and a 70% chance that they go to line 2 (stream 3).  Line 1 has a build station staffed
  by 2 workers.  Line 2 has a build station staffed by 3 workers.  The time to build a part on line 1 is
  triangularly distributed with a (min = 2, mode = 6, max = 8) minutes (stream 4).  The time to build a part on
  line 2 is triangularly distributed with a (min = 3, mode = 6, max = 7) minutes (stream 5).  The build operation
  on the part only requires 1 of the workers available at the station. If all the workers at the station are busy,
  then the part waits for the next available worker.

After the parts are built, they go to a packaging station.  The packaging station is staffed by 2 workers.
Only 1 of the 2 workers is needed during packaging.  If all the workers at the packaging station are busy,
then the part waits for the next available packaging worker. The time to individually wrap a part is
exponential with a mean of 2 minutes (stream 6).  After each individual part is wrapped, the worker
fills a box with packing peanuts and places the part into a box for shipping. The time to fill the box with peanuts
and seal the box is uniformly distribution between 1 and 2 minutes (stream 7).
The manufacturing system believes that a batch run of production over 30 days of operation is the best way to
produce these parts.  Thus, at the beginning of a month there are currently no parts in production.

Simulate the system for 100 replications of one month (30 days) of operation.
The base time unit for the simulation should be in MINUTES.
Where is the bottleneck in the system? Why?
*/

class ProductionSystemActivity(
    parent: ModelElement,
    name: String? = null
) : ProcessModel(parent, name) {

    private val timeBetweenArrivalRV: RandomVariable = RandomVariable(parent, ExponentialRV(5.0, 1))
    private val prepTimeRV: RandomVariable = RandomVariable(parent, ExponentialRV(8.0, 2))
    private val build1ChoiceRV: RandomVariable = RandomVariable(parent, BernoulliRV(0.3, 3))

    private val build1TimeRV: RandomVariable = RandomVariable(parent, TriangularRV(2.0, 6.0, 8.0, 4))
    private val build2TimeRV: RandomVariable = RandomVariable(parent, TriangularRV(3.0, 6.0, 7.0, 5))
    private val packagingTimeRV: RandomVariable = RandomVariable(parent, ExponentialRV(2.0, 6))
    private val fillingTimeRV: RandomVariable = RandomVariable(parent, UniformRV(1.0, 2.0, 7))

    private val prepWorkers: ResourceWithQ = ResourceWithQ(this, capacity = 2, name = "${this.name}:PrepWorkers")
    private val build1Workers: ResourceWithQ = ResourceWithQ(this, capacity = 2, name = "${this.name}:Build1Workers")
    private val build2Workers: ResourceWithQ = ResourceWithQ(this, capacity = 3, name = "${this.name}:Build2Workers")
    private val packagingWorkers: ResourceWithQ = ResourceWithQ(this, capacity = 2, name = "${this.name}:PackagingWorkers")

    private val systemTime = Response(this, "${this.name}:SysTime")
    private val partsCompleted = Counter(this, "${this.name}:PartsCompleted")
    private val sysTimeLT60: IndicatorResponse = IndicatorResponse({ x -> x <= 60.0 }, systemTime, "${this.name}:SysTime <= 60")

    override fun initialize() {
        schedule(this::arrival, timeBetweenArrivalRV)
    }

    private fun arrival(event: KSLEvent<Nothing>) {
        val p = Part()
        activate(p.mfgProcess)
        schedule(this::arrival, timeBetweenArrivalRV)
    }

    private inner class Part : Entity() {
        val mfgProcess: KSLProcess = process() {
            use(prepWorkers, delayDuration = prepTimeRV)
            if (build1ChoiceRV.value == 1.0){
                use(build1Workers, delayDuration = build1TimeRV)
            } else {
                use(build2Workers, delayDuration = build2TimeRV)
            }
//            val w = seize(packagingWorkers)
//            delay(packagingTimeRV)
//            delay(fillingTimeRV)
//            release(w)
            use(packagingWorkers, delayDuration = packagingTimeRV.value + fillingTimeRV.value)

            systemTime.value = time - createTime
            partsCompleted.increment()
        }
    }

}