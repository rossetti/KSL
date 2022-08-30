package ksl.modeling.entity

import ksl.modeling.queue.QObject
import ksl.simulation.ModelElement

open class EntityType(parent: ModelElement, name: String?): ModelElement(parent, name) {
    //TODO need to implement


    inner class Entity(aName: String? = null) : QObject(time, aName) {
        val entityType = EntityType@this

    }
}