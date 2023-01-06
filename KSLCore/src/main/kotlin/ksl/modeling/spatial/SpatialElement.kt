package ksl.modeling.spatial

//interface SpatialElementIfc {
//    var location: LocationIfc
//}

open class SpatialElement(initialSpatialModel: SpatialModel, initialLocation: LocationIfc) {
    init {
        require(initialSpatialModel.isValid(initialLocation)){"The location ${initialLocation.name} is not valid for spatial model ${initialSpatialModel.name}"}
    }

    /**
     * Represents the initial spatial model for the element. Since an element can move
     * between spatial models during a simulation, there must be a way to
     * remember which spatial model the element started with prior to re-running
     * a simulation. This property holds the initial spatial model. This only has an
     * effect when the element is initialized. Changing this property during a
     * simulation has no effect until the element is initialized. The current
     * spatial model is not changed by this property. When the element is
     * initialized, its current spatial model is set to this property.
     */
    var initialModel: SpatialModel = initialSpatialModel
        protected set

    var currentModel: SpatialModel = initialSpatialModel
        protected set

    var location: LocationIfc = initialLocation
        set(value) {
            require(currentModel.isValid(value)){"The location ${value.name} is not valid for spatial model ${currentModel.name}"}
            field = value
            field.model.updatingElement = this
        }

}
