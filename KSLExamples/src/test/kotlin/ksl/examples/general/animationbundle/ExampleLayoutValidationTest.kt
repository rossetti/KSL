package ksl.examples.general.animationbundle

import ksl.animation.AnimationLayout
import ksl.animation.validateAgainst
import ksl.simulation.Model
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Sweeps every runnable example (8K.2): builds its model + layout and checks that every
 * queue/resource/station/response binding in the layout actually exists in the model. Catches missing
 * or mistyped element names — which silently render nothing — across all demos in one place. Pure
 * name-checking (no simulate / no trace), so it is fast.
 */
class ExampleLayoutValidationTest {

    private data class Example(val name: String, val model: () -> Model, val layout: (Model) -> AnimationLayout)

    private val examples = listOf(
        Example("01 DriveThroughPharmacy", Example01DriveThroughPharmacy::buildModel, Example01DriveThroughPharmacy::buildLayout),
        Example("02 MovingParts", Example02MovingParts::buildModel, Example02MovingParts::buildLayout),
        Example("03 GridEpidemic", Example03GridEpidemic::buildModel, Example03GridEpidemic::buildLayout),
        Example("04 BuildingEvacuation", Example04BuildingEvacuation::buildModel, Example04BuildingEvacuation::buildLayout),
        Example("05 PedestrianCrowd", Example05PedestrianCrowd::buildModel, Example05PedestrianCrowd::buildLayout),
        Example("06 WarehouseAGV", Example06WarehouseAGV::buildModel, Example06WarehouseAGV::buildLayout),
        Example("07 StationTandem", Example07StationTandem::buildModel, Example07StationTandem::buildLayout),
        Example("08 ConveyorTandem", Example08ConveyorTandem::buildModel, Example08ConveyorTandem::buildLayout),
        Example("09 DistancesTandem", Example09DistancesTandem::buildModel, Example09DistancesTandem::buildLayout),
        Example("10 MultiClassStation", Example10MultiClassStation::buildModel, Example10MultiClassStation::buildLayout),
        Example("11 Flocking", Example11Flocking::buildModel, Example11Flocking::buildLayout),
        Example("12 StemFairStorage", Example12StemFairStorage::buildModel, Example12StemFairStorage::buildLayout),
        Example("13 MovableResources", Example13MovableResources::buildModel, Example13MovableResources::buildLayout),
        Example("15 DroneDelivery", Example15DroneDelivery::buildModel, Example15DroneDelivery::buildLayout),
        Example("16 NetworkRumor", Example16NetworkRumor::buildModel, Example16NetworkRumor::buildLayout),
        Example("17 TandemBlocking", Example17TandemBlocking::buildModel, Example17TandemBlocking::buildLayout),
        Example("18 ConveyorTestRepair", Example18ConveyorTestRepair::buildModel, Example18ConveyorTestRepair::buildLayout),
    )

    @Test
    fun `every example layout binds cleanly to its model (8K2)`() {
        val problems = StringBuilder()
        for (ex in examples) {
            val model = ex.model()
            val report = ex.layout(model).validateAgainst(model)
            if (!report.isValid) problems.append("Example ${ex.name}:\n").append(report).append('\n')
        }
        assertTrue(problems.isEmpty(), "Example layouts with unbound names:\n$problems")
    }
}
