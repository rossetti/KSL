/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.examples.general.animationbundle

import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc

/*
 * ModelBuilderIfc wrappers for the 16 animation gallery models, so `kslpkg assemble`
 * (the animationExamplesBundleJar task) can package them into the discoverable
 * `edu.uark.ksl.animation-examples` bundle. Each delegates to its example's buildModel().
 * Layouts are NOT bundled: the user authors a layout in the animation app's Layout tab and
 * saves it to their workspace. (Each example's buildLayout() is a standalone teaching example
 * of the layout DSL, not consumed at runtime.) The released bundle is dropped into the user's
 * KSLWork/bundles folder and discovered at runtime.
 */

/**
 * Bundle [ModelBuilderIfc] for the Example 1 drive-through-pharmacy animation model; delegates to its
 * `buildModel()` so the animation-examples bundle can package and discover it.
 */
class Example01DriveThroughPharmacyBuilder : ModelBuilderIfc {
    override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model =
        Example01DriveThroughPharmacy.buildModel()
}

/**
 * Bundle [ModelBuilderIfc] for the Example 2 moving-parts animation model (parts moving through space);
 * delegates to its `buildModel()` for packaging into the animation-examples bundle.
 */
class Example02MovingPartsBuilder : ModelBuilderIfc {
    override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model =
        Example02MovingParts.buildModel()
}

/**
 * Bundle [ModelBuilderIfc] for the Example 3 grid-epidemic animation model (infection spreading across a
 * spatial grid); delegates to its `buildModel()` for packaging into the animation-examples bundle.
 */
class Example03GridEpidemicBuilder : ModelBuilderIfc {
    override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model =
        Example03GridEpidemic.buildModel()
}

/**
 * Bundle [ModelBuilderIfc] for the Example 4 building-evacuation animation model; delegates to its
 * `buildModel()` for packaging into the animation-examples bundle.
 */
class Example04BuildingEvacuationBuilder : ModelBuilderIfc {
    override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model =
        Example04BuildingEvacuation.buildModel()
}

/**
 * Bundle [ModelBuilderIfc] for the Example 5 pedestrian-crowd animation model; delegates to its
 * `buildModel()` for packaging into the animation-examples bundle.
 */
class Example05PedestrianCrowdBuilder : ModelBuilderIfc {
    override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model =
        Example05PedestrianCrowd.buildModel()
}

/**
 * Bundle [ModelBuilderIfc] for the Example 6 warehouse AGV animation model (automated guided vehicles in
 * a warehouse); delegates to its `buildModel()` for packaging into the animation-examples bundle.
 */
class Example06WarehouseAGVBuilder : ModelBuilderIfc {
    override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model =
        Example06WarehouseAGV.buildModel()
}

/**
 * Bundle [ModelBuilderIfc] for the Example 7 station-based tandem-queue animation model; delegates to its
 * `buildModel()` for packaging into the animation-examples bundle.
 */
class Example07StationTandemBuilder : ModelBuilderIfc {
    override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model =
        Example07StationTandem.buildModel()
}

/**
 * Bundle [ModelBuilderIfc] for the Example 8 conveyor-based tandem-queue animation model; delegates to
 * its `buildModel()` for packaging into the animation-examples bundle.
 */
class Example08ConveyorTandemBuilder : ModelBuilderIfc {
    override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model =
        Example08ConveyorTandem.buildModel()
}

/**
 * Bundle [ModelBuilderIfc] for the Example 9 distances-based tandem-queue animation model (movement over
 * a distances model); delegates to its `buildModel()` for packaging into the animation-examples bundle.
 */
class Example09DistancesTandemBuilder : ModelBuilderIfc {
    override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model =
        Example09DistancesTandem.buildModel()
}

//class Example10MultiClassStationBuilder : ModelBuilderIfc {
//    override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model =
//        Example10MultiClassStation.buildModel()
//}

/**
 * Bundle [ModelBuilderIfc] for the Example 11 flocking (boids) animation model; delegates to its
 * `buildModel()` for packaging into the animation-examples bundle.
 */
class Example11FlockingBuilder : ModelBuilderIfc {
    override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model =
        Example11Flocking.buildModel()
}

/**
 * Bundle [ModelBuilderIfc] for the Example 12 STEM-fair-with-storage animation model; delegates to its
 * `buildModel()` for packaging into the animation-examples bundle.
 */
class Example12StemFairStorageBuilder : ModelBuilderIfc {
    override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model =
        Example12StemFairStorage.buildModel()
}

/**
 * Bundle [ModelBuilderIfc] for the Example 13 movable-resources animation model (transporters carrying
 * entities); delegates to its `buildModel()` for packaging into the animation-examples bundle.
 */
class Example13MovableResourcesBuilder : ModelBuilderIfc {
    override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model =
        Example13MovableResources.buildModel()
}

//class Example14AnnotatedClinicBuilder : ModelBuilderIfc {
//    override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model =
//        Example14AnnotatedClinic.buildModel()
//}

/**
 * Bundle [ModelBuilderIfc] for the Example 15 drone-delivery animation model; delegates to its
 * `buildModel()` for packaging into the animation-examples bundle.
 */
class Example15DroneDeliveryBuilder : ModelBuilderIfc {
    override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model =
        Example15DroneDelivery.buildModel()
}

//class Example16NetworkRumorBuilder : ModelBuilderIfc {
//    override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model =
//        Example16NetworkRumor.buildModel()
//}
