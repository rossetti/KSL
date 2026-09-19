package ksl.modeling.fleet.policies

import ksl.modeling.fleet.FleetVehicle

/** What a vehicle does with itself when the dispatcher has no work for it. */
sealed class Disposition {

    /** Stay exactly where it stopped. Cheap, and a hazard: a vehicle parked on the guide path
     *  denies that space to everyone else. */
    data object ParkInPlace : Disposition()

    /** Go to the vehicle's declared home base, if it has one. */
    data object ReturnToHomeBase : Disposition()

    /** Go somewhere named. */
    data class MoveTo(val locationName: String) : Disposition()

    /**
     * Go to a charger and stay there until the battery is full.
     *
     * Distinct from [MoveTo] the same place, because arriving is not the point: the vehicle waits
     * there for as long as charging takes. It remains assignable throughout -- a vehicle charging
     * is a vehicle the dispatcher may still want -- but the charging delay is not interruptible, so
     * work arriving mid-charge is taken up when the battery is full rather than at once.
     */
    data class GoCharge(val locationName: String) : Disposition()
}

/**
 * Decides what an idle vehicle does with itself.
 *
 * Consulted only after the dispatcher has been given the chance to assign and has declined, so no
 * disposition policy can cause a vehicle to idle while work waits. That guarantee is structural --
 * the branch that consults this interface is unreachable until the dispatcher has passed -- rather
 * than a rule an implementer could break.
 */
fun interface DispositionPolicyIfc {
    fun disposition(vehicle: FleetVehicle): Disposition
}

/**
 * Send the vehicle home. The default, and the safe one.
 *
 * The passive subsystem learned this the hard way and the lesson transfers unchanged: with vehicles
 * left where they stop, the first delivery parks on a station, and if that station is the only way
 * off a spur then every later delivery stops at the mouth for the rest of the run. Nothing raises.
 * The model simply stops moving.
 */
class ReturnToHomeBaseDisposition : DispositionPolicyIfc {
    override fun disposition(vehicle: FleetVehicle): Disposition = Disposition.ReturnToHomeBase
    override fun toString(): String = "ReturnToHomeBaseDisposition"
}

/** Leave the vehicle where it stopped. See the warning on [ReturnToHomeBaseDisposition]. */
class ParkInPlaceDisposition : DispositionPolicyIfc {
    override fun disposition(vehicle: FleetVehicle): Disposition = Disposition.ParkInPlace
    override fun toString(): String = "ParkInPlaceDisposition"
}

/**
 * Send the vehicle to a named staging point.
 *
 * Between [ReturnToHomeBaseDisposition] and [ParkInPlaceDisposition] in what it assumes: a home base
 * is per vehicle and a staging area is shared, so this is the rule for a fleet that should gather
 * somewhere central rather than disperse to its own corners. Whether that is better depends entirely
 * on where the work comes from, which is why it is a policy and not a default.
 *
 * The staging point must have room for every vehicle that may go there. A zone holds one vehicle, so
 * a staging *intersection* stages exactly one and the rest queue on the approach -- which is usually
 * not what was wanted and is exactly the configuration that quietly strangles a model. Stage on a
 * spur per vehicle, or accept that this is a rule about one parking space.
 */
class MoveToStagingDisposition(val locationName: String) : DispositionPolicyIfc {
    override fun disposition(vehicle: FleetVehicle): Disposition = Disposition.MoveTo(locationName)
    override fun toString(): String = "MoveToStagingDisposition($locationName)"
}

/**
 * Send the vehicle to charge when it is low, and otherwise do whatever another policy says.
 *
 * A decorator rather than a rule of its own, because "charge when low" is orthogonal to what a
 * vehicle does with itself the rest of the time: a fleet that gathers at a staging point and a
 * fleet that returns to its own home base both need it, and neither should have to reimplement the
 * other's parking rule to get it.
 *
 * **This is not the guard against running flat.** It is consulted only when the dispatcher has no
 * work for the vehicle, so a fleet that is busy enough never reaches it -- and a busy fleet is
 * exactly the one that empties its batteries. Pair it with [ChargeReservePolicy], which is what
 * stops a vehicle accepting work it cannot finish.
 *
 * @param threshold the fraction of capacity at or below which the vehicle goes to charge
 * @param otherwise what it does when it is not low, or when no charger is reachable
 */
class ChargeWhenLowDisposition @JvmOverloads constructor(
    val threshold: Double = 0.25,
    val otherwise: DispositionPolicyIfc = ReturnToHomeBaseDisposition()
) : DispositionPolicyIfc {

    init {
        require(threshold in 0.0..1.0) {
            "A charging threshold is a fraction of capacity and must be in 0.0..1.0, but was $threshold."
        }
    }

    override fun disposition(vehicle: FleetVehicle): Disposition {
        if (vehicle.battery == null) return otherwise.disposition(vehicle)
        if (vehicle.fractionCharged > threshold) return otherwise.disposition(vehicle)
        // No charger it can reach is not an error here. The vehicle is low, not stopped, and it may
        // still be able to work; refusing to park would strand it for a reason that has nothing to
        // do with parking.
        val charger = vehicle.system.nearestCharger(vehicle.currentLocationName)
            ?: return otherwise.disposition(vehicle)
        return Disposition.GoCharge(charger)
    }

    override fun toString(): String = "ChargeWhenLowDisposition(threshold=$threshold, otherwise=$otherwise)"
}
