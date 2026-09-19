package ksl.modeling.spatial

import ksl.animation.KSLAnimatedEntity
import ksl.animation.KSLAnimatedProcess
import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.modeling.entity.*
import ksl.modeling.variable.RandomVariableCIfc
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.GetValueIfc
import ksl.utilities.observers.ObservableComponent
import ksl.utilities.observers.ObserverIfc
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.random.rvariable.toDouble

interface MovableResourceIfc : SpatialElementIfc, VelocityIfc

interface MoveableResourceCIfc : ResourceCIfc {
    val velocityRV: RandomVariableCIfc
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
 * It also implements [VehicleMovementIfc], the movement seam a fleet is written against, so that
 * tours, dispatching and a manifest can be driven over a free path by exactly the code that drives
 * them over a guide path or a continuous projection. **Nothing about the existing `move`,
 * `moveWith` and `transportWith` verbs changes**: those remain one delay to the destination and
 * are what a modeller uses to move a resource directly. The seam is a second way in, used by a
 * fleet, and it is the one that can be interrupted and redirected part way. See "The movement seam"
 * below.
 *
 * @param name the name of the resource
 * @param stepSize how far apart the seam's decision points are, in the spatial model's own distance
 *   units. It fixes how quickly a redirection or a halt is observed -- at most `stepSize/velocity`
 *   later -- and is ignored entirely where the spatial model cannot interpolate, since there a
 *   journey is one step and has always been
 */
open class MovableResource @JvmOverloads constructor(
    parent: ModelElement,
    initLocation: LocationIfc,
    defaultVelocity: RVariableIfc,
    name: String? = null,
    stepSize: Double = 1.0,
) : Resource(parent, name, 1), MovableResourceIfc, MoveableResourceCIfc, VehicleMovementIfc {

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
    override var initialCapacity: Int
        get() = super.initialCapacity
        set(value) {
            require((value == 0) || (value == 1)) { "The initial capacity of a movable resource must be 0 or 1" }
            super.initialCapacity = value
        }

    override var capacity: Int
        get() = super.capacity
        set(value) {
            require((value == 0) || (value == 1)) { "The capacity of a movable resource must be 0 or 1" }
            super.capacity = value
        }

    protected val mySpatialElement: SpatialElement = SpatialElement(this, initLocation, name)

    protected val myVelocity: RandomVariable = RandomVariable(this, defaultVelocity, name = "${this.name}:VelocityRV")
    override val velocityRV: RandomVariableCIfc
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

    protected val myFracTimeMoving: TWResponse =
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
        myLivePosition = mySpatialElement.currentLocation
        towVelocity = null
        myShift.initialize()
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

    protected val myHomeQ: RequestQ = RequestQ(this, "${this.name}:HomeBaseQ")

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

        var returningHome : Boolean = false
            private set

        fun sendToHomeBase() {
            if ((homeBase != null) && !returningHome) {
                val driver = Driver()
                returningHome = true
                activate(driver.returnToHomeProcess, priority = KSLEvent.VERY_HIGH_PRIORITY)
            }
        }

        // The home-base return is internal plumbing, not a domain entity/process — keep it out of the
        // animation inventory's discovered types/processes (10.8/C6).
        @KSLAnimatedEntity(include = false)
        inner class Driver() : Entity() {
            @KSLAnimatedProcess(include = false)
            val returnToHomeProcess: KSLProcess = process {
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

    // ---- the movement seam ---------------------------------------------------------------------
    //
    // `VehicleMovementIfc` is what a fleet needs from whatever moves its vehicles. A guide path
    // discretises a journey at zone boundaries and a continuous projection at interpolation steps;
    // a free path has always made one a single delay, with the resource at the place it set out
    // from until the instant it arrived. That is fine for a modeller who commands the move and
    // waits for it, and not fine for a dispatcher deciding which vehicle is nearest -- which reads
    // a position mid-journey, and would read every vehicle's position as of wherever it last
    // stopped.
    //
    // So the seam moves through `InterpolatedMovement`, the clockwork this package now owns, over
    // the geometry below. Where the spatial model can say where between two places is, the journey
    // is stepped and the position is live. Where it cannot -- a table of pairwise distances, a
    // network of named junctions -- the journey is one step, exactly as `move` has always been, and
    // `positionNow` honestly reports the place it set out from until it gets there.

    /**
     *  Where the resource is between one arrival and the next.
     *
     *  Kept apart from `currentLocation` deliberately. `currentLocation` is written once per
     *  journey, at the end, which is what every existing verb does and what everything observing a
     *  spatial element -- tracking, animation, the spatial model's own update hook -- has always
     *  seen. Stepping a journey writes here instead, so that a seam journey costs one
     *  `currentLocation` update, exactly as a `move` does, however finely it was discretised.
     */
    private var myLivePosition: LocationIfc = initLocation

    /** This resource's spatial model, as a geometry the shared clockwork can move through. */
    private inner class FreePath : MovePathIfc {

        override val positionNow: LocationIfc
            get() = myLivePosition

        override fun distanceBetween(from: LocationIfc, to: LocationIfc): Double =
            spatialModel.distance(from, to)

        override fun isReachable(destination: LocationIfc): Boolean =
            spatialModel.isValid(destination)

        override fun positionAlong(from: LocationIfc, to: LocationIfc, fraction: Double): LocationIfc? =
            spatialModel.interpolate(from, to, fraction)

        override fun placeAt(location: LocationIfc) {
            myLivePosition = location
        }
    }

    /**
     *  How fast somebody else is pushing it, or null when it is moving under its own power.
     *
     *  A tow is a journey like any other through the same geometry; the only thing that differs is
     *  who chose the speed. Kept here rather than passed per journey because the seam commands a
     *  destination and a purpose, never a velocity -- a fleet knows what a journey is *for* and not
     *  how fast this particular vehicle should make it.
     */
    internal var towVelocity: Double? = null

    private val myMovement: InterpolatedMovement = InterpolatedMovement(
        this, FreePath(), stepSize, { towVelocity ?: myVelocity.value }, { endOfSeamJourney() }
    )

    /** Where a waiter suspends while this resource is under way, for whoever needs to find it. */
    internal val travelQueue: ksl.modeling.entity.HoldQueue
        get() = myMovement.travelQ

    /** The veto asked at every step of a journey. See [InterpolatedMovement.continuationGate]. */
    internal var continuationGate: (() -> Boolean)?
        get() = myMovement.continuationGate
        set(value) {
            myMovement.continuationGate = value
        }

    /**
     *  How far apart the seam's decision points are, in the spatial model's own distance units.
     *
     *  Smaller means a redirection or a halt is seen sooner and more events are scheduled. Ignored
     *  where the spatial model cannot interpolate.
     */
    var stepSize: Double
        get() = myMovement.stepSize
        set(value) {
            myMovement.stepSize = value
        }

    /**
     *  Where the resource is **at this instant**, which is not the same thing as [currentLocation].
     *
     *  `currentLocation` is where it last arrived, and it is what every existing verb sets and
     *  reads; this is where it is now. The two agree except during a journey made through the seam
     *  over a spatial model that can interpolate, and there this one is the live answer. **Nothing
     *  that existed before reads this**, and it does not replace anything: a distance-based
     *  dispatching rule should read it, and a model that asks where a resource *is* in the ordinary
     *  sense should keep asking `currentLocation`.
     */
    override val positionNow: LocationIfc
        get() = if (myMovement.isTravelling) myLivePosition else mySpatialElement.currentLocation

    /** How far [destination] is by this spatial model's own metric, measured from [positionNow]. */
    override fun pathDistanceTo(destination: LocationIfc): Double =
        spatialModel.distance(positionNow, destination)

    /** True for any location of this resource's own spatial model. */
    override fun isReachable(destination: LocationIfc): Boolean =
        spatialModel.isValid(destination)

    /**
     *  Sends the resource to [destination] and hands back the queue to wait in.
     *
     *  A second call while a journey is under way is a **redirection**: the target changes and the
     *  running journey re-plans at its next step. Over a spatial model that cannot interpolate
     *  there is no next step until arrival, so a redirection there takes effect when the leg ends —
     *  which is the honest consequence of a geometry with nothing between two places.
     *
     *  @return the queue to suspend [waiter] in, or null when the resource was already there
     */
    override fun beginTravelTo(
        destination: LocationIfc,
        purpose: MovePurpose,
        waiter: ProcessModel.Entity
    ): HoldQueue? {
        if (!myMovement.isTravelling) myLivePosition = mySpatialElement.currentLocation
        val q = myMovement.beginTravelTo(destination, waiter) ?: return null
        isMoving = true
        // Loaded-ness is read from what the resource is doing rather than from what the caller
        // says it is for: `transportWith` is what puts something on it, and the purpose says why
        // the journey was ordered.
        isMovingEmpty = !isTransporting
        return q
    }

    /** True while the resource is stopped short of where it was going, with nothing scheduled. */
    override val isHalted: Boolean
        get() = myMovement.isHalted

    /**
     *  Stops the resource where it stands and wakes whoever was waiting for it.
     *
     *  What a free-path move never had: somewhere for a breakdown, a low battery, or a controller's
     *  recall to take effect other than at the destination. Whoever called this owns starting it
     *  again, through [resumeHalted] or a fresh [beginTravelTo].
     */
    fun halt() = myMovement.halt()

    override fun resumeHalted() = myMovement.resumeHalted()

    /** How far this resource has travelled this replication, however it was moved. Never decreases. */
    override val distanceTravelled: Double
        get() = myMovement.distanceTravelled

    /** How long it has spent moving this replication, however it was moved. Never decreases. */
    override val operatingTime: Double
        get() = myMovement.operatingTime

    /**
     *  Books a journey this resource made by some other means.
     *
     *  Called by the `move` verbs, which are a single delay and do not go through the seam's
     *  clockwork. Without it a resource's odometers would be about *which verb moved it* rather
     *  than about the resource, which is the kind of statistic that is worse than none.
     */
    internal fun recordMove(distance: Double, duration: Double) {
        myMovement.recordExternalMove(distance, duration)
    }

    /**
     *  A seam journey has stopped, by arrival or by a halt.
     *
     *  The one `currentLocation` write of the journey happens here, which is what keeps a stepped
     *  journey indistinguishable from a single-delay one to everything that observes a spatial
     *  element.
     */
    private fun endOfSeamJourney() {
        mySpatialElement.currentLocation = myLivePosition
        isMoving = false
        isMovingEmpty = false
    }


    // ---- shifts ---------------------------------------------------------------------------------

    private val myShift: ShiftControl = ShiftControl(this)

    /** True while this vehicle has been taken off shift. */
    val isOffShift: Boolean
        get() = myShift.isOffShift

    /**
     *  Takes this vehicle off shift: it cannot be seized again until [goOnShift], and requests for
     *  it wait. Work already allocated finishes under the resource's own capacity change rule.
     */
    fun goOffShift() = myShift.goOffShift()

    /** Puts this vehicle back on shift. */
    fun goOnShift() = myShift.goOnShift()

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
            defaultVelocity: RVariableIfc,
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