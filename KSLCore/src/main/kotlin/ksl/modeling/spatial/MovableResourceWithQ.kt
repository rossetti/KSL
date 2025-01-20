package ksl.modeling.spatial

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.RequestQ
import ksl.modeling.entity.ResourceWithQ
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

class MovableResourceWithQ(
    parent: ModelElement,
    initLocation: LocationIfc,
    defaultVelocity: RandomIfc,
    initialCapacity: Int = 1,
    name: String? = null,
    queue: RequestQ? = null,
) : ResourceWithQ(parent, name, initialCapacity, queue), SpatialElementIfc, VelocityIfc {
    init {
        require((initialCapacity == 0) || (initialCapacity == 1))
        { "The initial capacity of a movable resource must be 0 or 1" }
    }

    @set:KSLControl(
        controlType = ControlType.INTEGER,
        lowerBound = 0.0,
        upperBound = 1.0
    )
    override var initialCapacity
        get() = super.initialCapacity
        set(value) {
            require((value == 0) || (value == 1)) { "The initial capacity of a movable resource must be 0 or 1" }
            super.initialCapacity = value
        }

    override var capacity
        get() = super.capacity
        set(value) {
            require((value == 0) || (value == 1)) { "The capacity of a movable resource must be 0 or 1" }
            super.capacity = value
        }

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

    val hasHomeBase: Boolean
        get() = homeBase != null

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

    /**
     *  If the movable resource has a defined home base, and it is not
     *  already returning to home, this function causes the
     *  movable resource to be requested and sent to its home base.
     *  If there are pending requests, this request will compete with them,
     *  possibly waiting until finally causing the resource to return
     *  to its home base.
     */
    fun sendToHomeBase(){
        if (hasHomeBase  && !homeBaseDriver.returningHome){
            homeBaseDriver.sendToHomeBase()
        }
    }

    private val myHomeQ = RequestQ(this, "${this.name}:HomeBaseQ")

    init {
        myHomeQ.waitTimeStatOption = false
        myHomeQ.defaultReportingOption = false
    }

    /**
     *  @param option  If true the queue holding requests for moving to the home base
     *  will report statistics
     */
    fun homeQStatistics(option: Boolean){
        myHomeQ.waitTimeStatOption = option
        myHomeQ.defaultReportingOption = option
    }

    /**
     *  True indicates that the movable resource is in the process of returning
     *  to its home base.
     */
    val isReturningHome: Boolean
        get() = homeBaseDriver.returningHome

    private inner class HomeBaseDriver() : ProcessModel(
        this@MovableResourceWithQ, "${this@MovableResourceWithQ.name}:Driver"
    ) {

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
                require(homeBase != null) {"There is no home based defined for ${this@MovableResourceWithQ.name}"}
                val a = seize(this@MovableResourceWithQ,
                    queue = myHomeQ, seizePriority = KSLEvent.VERY_HIGH_PRIORITY)
                move(this@MovableResourceWithQ, homeBase!!, movePriority = KSLEvent.VERY_HIGH_PRIORITY)
                release(a)
                returningHome = false
            }
        }
    }

}