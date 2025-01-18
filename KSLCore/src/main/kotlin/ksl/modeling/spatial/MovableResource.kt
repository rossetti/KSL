package ksl.modeling.spatial

import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.RequestQ
import ksl.modeling.entity.Resource
import ksl.modeling.queue.Queue
import ksl.modeling.variable.RandomSourceCIfc
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.TWResponse
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.GetValueIfc
import ksl.utilities.observers.ObservableComponent
import ksl.utilities.observers.ObserverIfc
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.toDouble


/**
 * A movable resource is a resource that resides within a spatial model and thus can be moved.
 * @param parent the parent model element
 * @param initLocation the initial starting location of the resource within the spatial model
 * @param defaultVelocity the default velocity for movement within the spatial model
 * @param name the name of the resource
 */
class MovableResource(
    parent: ModelElement,
    initLocation: LocationIfc,
    defaultVelocity: RandomIfc,
    name: String? = null,
) : Resource(parent, name, 1), SpatialElementIfc, VelocityIfc {
    protected val mySpatialElement = SpatialElement(this, initLocation, name)
    protected val myVelocity = RandomVariable(this, defaultVelocity)
    val velocityRV: RandomSourceCIfc
        get() = myVelocity
    override val velocity: GetValueIfc
        get() = myVelocity

    override var isMoving: Boolean
        get() = mySpatialElement.isMoving
        set(value) {
            mySpatialElement.isMoving = value
            myFracTimeMoving.value = value.toDouble()
        }
    var isTransporting: Boolean = false
        set(value) {
            field = value
            myFracTimeTransporting.value = field.toDouble()
        }
    var isMovingEmpty: Boolean = false
        set(value) {
            field = value
            myFracTimeMovingEmpty.value = field.toDouble()
        }
    private val myFracTimeMoving =
        TWResponse(this, name = "${this.name}:FracTimeMoving", initialValue = mySpatialElement.isMoving.toDouble())
    private val myFracTimeTransporting = TWResponse(this, name = "${this.name}:FracTimeTransporting")
    private val myFracTimeMovingEmpty = TWResponse(this, name = "${this.name}:FracTimeMovingEmpty")
    override val isTracked: Boolean
        get() = mySpatialElement.isTracked
    override val spatialID: Int
        get() = mySpatialElement.spatialID
    override val spatialName: String
        get() = mySpatialElement.spatialName
    override val status: SpatialModel.Status
        get() = mySpatialElement.status
    override var initialLocation: LocationIfc
        get() = mySpatialElement.initialLocation
        set(value) {
            mySpatialElement.initialLocation = value
        }
    override var currentLocation: LocationIfc
        get() = mySpatialElement.currentLocation
        set(value) {
            mySpatialElement.currentLocation = value
        }
    override val previousLocation: LocationIfc
        get() = mySpatialElement.previousLocation
    override val modelElement: ModelElement
        get() = mySpatialElement.modelElement
    override val observableComponent: ObservableComponent<SpatialElementIfc>
        get() = mySpatialElement.observableComponent

    var initialHomeBase: LocationIfc? = null
    var homeBase:LocationIfc? = null

    private val homeBaseDriver = HomeBaseDriver()

    override fun initialize() {
        super.initialize()
        homeBase = initialHomeBase
        initializeSpatialElement()
    }

    override fun initializeSpatialElement() {
        mySpatialElement.initializeSpatialElement()
    }

    override fun attachObserver(observer: ObserverIfc<SpatialElementIfc>) {
        mySpatialElement.attachObserver(observer)
    }

    override fun detachObserver(observer: ObserverIfc<SpatialElementIfc>) {
        mySpatialElement.detachObserver(observer)
    }

    override fun isAttached(observer: ObserverIfc<SpatialElementIfc>): Boolean {
        return mySpatialElement.isAttached(observer)
    }

    override fun detachAllObservers() {
        mySpatialElement.detachAllObservers()
    }

    override fun countObservers(): Int {
        return mySpatialElement.countObservers()
    }

    internal fun activateHomeBaseDriver(){
        if ((homeBase != null) && !homeBaseDriver.returningHome){
            homeBaseDriver.sendToHomeBase()
        }
    }

    private inner class HomeBaseDriver() : ProcessModel(
        this@MovableResource, "${this@MovableResource.name}:Driver"
    ) {
        val homeQ = RequestQ(this, "${this.name}:HomeBaseQ")
        init {
            homeQ.waitTimeStatOption = false
            homeQ.defaultReportingOption = false
        }

        var returningHome = false
            private set

        fun sendToHomeBase() {
            if ((homeBase != null) && !returningHome){
                val driver = Driver()
                returningHome = true
                activate(driver.returnToHomeProcess, priority = KSLEvent.VERY_HIGH_PRIORITY)
            }
        }

        private inner class Driver() : Entity() {
            val returnToHomeProcess = process {
                require(homeBase != null) {"There is no home based defined for ${this@MovableResource.name}"}
                val a = seize(this@MovableResource,
                    queue = homeQ, seizePriority = KSLEvent.VERY_HIGH_PRIORITY)
                move(this@MovableResource, homeBase!!, movePriority = KSLEvent.VERY_HIGH_PRIORITY)
                release(a)
                returningHome = false
            }
        }
    }

}