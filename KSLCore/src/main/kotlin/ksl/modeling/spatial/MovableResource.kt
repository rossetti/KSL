package ksl.modeling.spatial

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.modeling.entity.*
import ksl.modeling.variable.RandomSourceCIfc
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.GetValueIfc
import ksl.utilities.observers.ObservableComponent
import ksl.utilities.observers.ObserverIfc
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.toDouble

interface MovableResourceIfc : SpatialElementIfc, VelocityIfc

interface MoveableResourceCIfc : ResourceCIfc {
    val velocityRV: RandomSourceCIfc
    val initialHomeBase: LocationIfc?
    val homeBase: LocationIfc?
    val hasHomeBase: Boolean
    val fracTimeMoving: TWResponseCIfc
    val fracTimeTransporting: TWResponseCIfc
    val fracTimeMovingEmpty: TWResponseCIfc
}

/**
 * A movable resource is a single unit capacity resource that resides within a spatial model and thus can be moved.
 * @param parent the parent model element
 * @param initLocation the initial starting location of the resource within the spatial model
 * @param defaultVelocity the default velocity for movement within the spatial model
 * @param name the name of the resource
 */
open class MovableResource(
    parent: ModelElement,
    initLocation: LocationIfc,
    defaultVelocity: RandomIfc,
    name: String? = null,
) : Resource(parent, name, 1), MovableResourceIfc, MoveableResourceCIfc {

    /**
     *  The pools that currently contain the resource. Called
     *  from MovableResourcePool.addResource() to indicate to the movable resource
     *  which pools it is within.
     */
    internal val myMovableResourcePools = mutableSetOf<MovableResourcePool>()

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

    protected val myVelocity = RandomVariable(this, defaultVelocity, name = "${this.name}:VelocityRV")
    override val velocityRV: RandomSourceCIfc
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
        internal set(value) {
            field = value
            myFracTimeTransporting.value = field.toDouble()
        }

    var isMovingEmpty: Boolean = false
        internal set(value) {
            field = value
            myFracTimeMovingEmpty.value = field.toDouble()
        }

    protected val myFracTimeMoving =
        TWResponse(this, name = "${this.name}:FracTimeMoving", initialValue = mySpatialElement.isMoving.toDouble())
    override val fracTimeMoving: TWResponseCIfc
        get() = myFracTimeMoving
    protected val myFracTimeTransporting = TWResponse(this, name = "${this.name}:FracTimeTransporting")
    override val fracTimeTransporting: TWResponseCIfc
        get() = myFracTimeTransporting
    protected val myFracTimeMovingEmpty = TWResponse(this, name = "${this.name}:FracTimeMovingEmpty")
    override val fracTimeMovingEmpty: TWResponseCIfc
        get() = myFracTimeMovingEmpty

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

    override var initialHomeBase: LocationIfc? = null
    override var homeBase: LocationIfc? = null

    override val hasHomeBase: Boolean
        get() = homeBase != null

    protected val homeBaseDriver = HomeBaseDriver()

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
    fun sendToHomeBase() {
        if (hasHomeBase && !homeBaseDriver.returningHome) {
            homeBaseDriver.sendToHomeBase()
        }
    }

    protected val myHomeQ = RequestQ(this, "${this.name}:HomeBaseQ")

    init {
        myHomeQ.waitTimeStatOption = false
        myHomeQ.defaultReportingOption = false
    }

    /**
     *  @param option  If true the queue holding requests for moving to the home base
     *  will report statistics
     */
    fun homeQStatistics(option: Boolean) {
        myHomeQ.waitTimeStatOption = option
        myHomeQ.defaultReportingOption = option
    }

    /**
     *  True indicates that the movable resource is in the process of returning
     *  to its home base.
     */
    val isReturningHome: Boolean
        get() = homeBaseDriver.returningHome

    protected inner class HomeBaseDriver() : ProcessModel(
        this@MovableResource, "${this@MovableResource.name}:Driver"
    ) {

        var returningHome = false
            private set

        fun sendToHomeBase() {
            if ((homeBase != null) && !returningHome) {
                val driver = Driver()
                returningHome = true
                activate(driver.returnToHomeProcess, priority = KSLEvent.VERY_HIGH_PRIORITY)
            }
        }

        inner class Driver() : Entity() {
            val returnToHomeProcess = process {
                require(homeBase != null) { "There is no home based defined for ${this@MovableResource.name}" }
                val a = seize(
                    this@MovableResource,
                    queue = myHomeQ, seizePriority = KSLEvent.VERY_HIGH_PRIORITY
                )
                move(this@MovableResource, homeBase!!, movePriority = KSLEvent.VERY_HIGH_PRIORITY)
                release(a)
                returningHome = false
            }
        }
    }

    companion object {
        /**
         *  Creates the required number of movable resources that have no queue.
         * @param parent the containing model element
         * @param numToCreate the number of resources to create, must be 1 or more
         * @param initLocation the initial starting location of the resource within the spatial model
         * @param defaultVelocity the default velocity for movement within the spatial model
         */
        fun createMovableResources(
            parent: ModelElement,
            numToCreate: Int,
            initLocation: LocationIfc,
            defaultVelocity: RandomIfc,
            baseName: String? = parent.name
        ): List<MovableResource> {
            require(numToCreate >= 1) { "The initial numToCreate must be >= 1" }
            val list = mutableListOf<MovableResource>()
            for (i in 1..numToCreate) {
                list.add(MovableResource(parent, initLocation, defaultVelocity, name = "${baseName}:R${i}"))
            }
            return list
        }
    }


}