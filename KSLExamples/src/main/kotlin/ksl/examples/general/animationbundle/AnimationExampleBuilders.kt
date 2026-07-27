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
 * The animation examples that SHIP. ModelBuilderIfc wrappers, one per model, so `kslpkg assemble`
 * (the animationExamplesBundleJar task) can package them into the discoverable
 * `edu.uark.ksl.animation-examples` bundle, dropped into the user's KSLWork/bundles folder.
 *
 * This is a curated teaching set, not a test matrix. Every model here earns its place by being the
 * clearest example of one thing, and the set is ordered so a reader can start at the simplest process
 * view and build up: one queue, then movement, then distances, conveyors, transporters, storages, and
 * finally the agent models. Where two models covered the same ground, the simpler one stayed as the
 * introduction and the richer one as the payoff -- Example 08's conveyor line before Example 18's loop,
 * Example 09's named locations before Example 13's transporters over the same kind of distance model.
 *
 * Models that existed only to find out whether a paradigm COULD be animated are not here. The
 * station-network pair and the rumor-on-a-graph model were answers to that question, not examples worth
 * shipping; they remain in KSLExamples as code, without a builder.
 *
 * Layouts are not bundled -- a bundle is about the model. The polished layout for each of these ships
 * separately, in the install's layouts folder, and is offered by the animation app's layout picker.
 * Each example's buildLayout() is the DSL-expressible part of that layout and a teaching example in its
 * own right; the shipped one can go further, because the DSL cannot express label overrides or an
 * authored conveyor route.
 *
 * ADDING ONE: the bundle jar is assembled from path patterns in KSLExamples/build.gradle.kts, not from
 * the call graph, so a builder whose model class lives somewhere not on that include list ships as an
 * entry that cannot be built -- and nothing at compile time says so. Add the include, and
 * AnimationBundleClosureTest will confirm it.
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

/**
 * Bundle [ModelBuilderIfc] for the Example 14 annotated-clinic animation model — the one that teaches how
 * to make *your own* model animate well, by declaring its entity types and annotating its processes;
 * delegates to its `buildModel()` for packaging into the animation-examples bundle.
 */
class Example14AnnotatedClinicBuilder : ModelBuilderIfc {
    override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model =
        Example14AnnotatedClinic.buildModel()
}

/**
 * Bundle [ModelBuilderIfc] for the Example 15 drone-delivery animation model; delegates to its
 * `buildModel()` for packaging into the animation-examples bundle.
 */
class Example15DroneDeliveryBuilder : ModelBuilderIfc {
    override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model =
        Example15DroneDelivery.buildModel()
}

/**
 * Bundle [ModelBuilderIfc] for the Example 17 tandem-queue-with-blocking animation model; delegates to its
 * `buildModel()` for packaging into the animation-examples bundle.
 */
class Example17TandemBlockingBuilder : ModelBuilderIfc {
    override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model =
        Example17TandemBlocking.buildModel()
}

/**
 * Bundle [ModelBuilderIfc] for the Example 18 loop-conveyor test-and-repair animation model; delegates to
 * its `buildModel()` for packaging into the animation-examples bundle.
 */
class Example18ConveyorTestRepairBuilder : ModelBuilderIfc {
    override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model =
        Example18ConveyorTestRepair.buildModel()
}
