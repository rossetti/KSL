package ksl.examples.book.chapter7

import ksl.modeling.elements.REmpiricalList
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.RequestQ
import ksl.modeling.entity.Resource
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.queue.Queue
import ksl.modeling.variable.Counter
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.*

class WalkInHealthClinic(parent: ModelElement, name: String? = null) : ProcessModel(parent, name) {

    private val myTBArrivals: RVariableIfc = ExponentialRV(6.0, 1)

    private val triageRV = RandomVariable(this, UniformRV(2.0, 3.0, 2))
    private val highRV = RandomVariable(this, LognormalRV(38.0, 8.0 * 8.0, 3))
    private val mediumRV = RandomVariable(this, TriangularRV(16.0, 22.0, 28.0, 4))
    private val lowRV = RandomVariable(this, LognormalRV(12.0, 2.0 * 2.0, 5))

    private val renegeTimeRV = RandomVariable(this, UniformRV(10.0, 20.0, 6))

    private val doctorQ: RequestQ = RequestQ(this, "DoctorQ", discipline = Queue.Discipline.RANKED)
    private val doctorR: ResourceWithQ = ResourceWithQ(this, capacity = 5, queue = doctorQ, name = "Doctors")
//    init {
//        doctorR.stateReportingOption = true
//    }
    private val triageNurseR: ResourceWithQ = ResourceWithQ(this, capacity = 1, name = "TriageNurse")

    var balkCriteria = 10
        set(value) {
            require(value > 0) { "The balk criteria must be > 0" }
            field = value
        }

    // set up the service distribution and the random selection
    private val distributions = listOf(highRV, mediumRV, lowRV)
    private val distributionCDF = doubleArrayOf(0.25, 0.85, 1.0)
    private val serviceRV = REmpiricalList(this, distributions, distributionCDF)
    private val typeMap = mapOf(highRV to 1, mediumRV to 2, lowRV to 3)

    private val myArrivalGenerator = EntityGenerator(::Patient, myTBArrivals, myTBArrivals)

    private val timeInSystem: Response = Response(this, "${this.name}:TimeInSystem")
    val systemTime: ResponseCIfc
        get() = timeInSystem

    private val timeInSystemHigh: Response = Response(this, "${this.name}:TimeInSystemHigh")
    private val timeInSystemMedium: Response = Response(this, "${this.name}:TimeInSystemMedium")
    private val timeInSystemLow: Response = Response(this, "${this.name}:TimeInSystemLow")
    private val sysTimeMap = mapOf( 1 to timeInSystemHigh, 2 to timeInSystemMedium, 3 to timeInSystemLow)

    private val balkingProb: Response = Response(this, "${this.name}:ProbBalking")
    val probBalking: ResponseCIfc
        get() = balkingProb

    private val renegingProb: Response = Response(this, "${this.name}:ProbReneging")
    val probReneging: ResponseCIfc
        get() = renegingProb

    private val numServed = Counter(this,"${this.name}:NumServed" )
    private val numBalked = Counter(this,"${this.name}:NumBalked" )
    private val numReneged = Counter(this,"${this.name}:NumReneged" )

    // define the process
    private inner class Patient : Entity() {
        private val service = serviceRV.randomElement

        init {
            priority = typeMap[service]!!
        }

        val clinicProcess = process {
            if ((priority == 3) && (doctorQ.size >= balkCriteria)) {
                // record balking
                balkingProb.value = 1.0
                numBalked.increment()
                return@process
            }
            if (priority == 3){
                balkingProb.value = 0.0
            }
            use(triageNurseR, delayDuration = triageRV)
            // only low priority renege
            if (priority == 3) {
                schedule(this@Patient::renegeAction, renegeTimeRV, message = this@Patient)
            }
            val a = seize(doctorR)
            delay(service)
            release(a)
            if (priority == 3){
                renegingProb.value = 0.0
            }
            val st = time - this@Patient.createTime
            timeInSystem.value = st
            sysTimeMap[priority]?.value = st
            numServed.increment()
        }

        init {
            initialProcess = clinicProcess
        }

        private fun renegeAction(event: KSLEvent<Patient>) {
            val request: ProcessModel.Entity.Request? = doctorQ.find { it.entity == event.message }
            if (request != null) {
                doctorQ.removeAndTerminate(request)
                // reneging statistics
                renegingProb.value = 1.0
                numReneged.increment()
            }
        }

    }
}