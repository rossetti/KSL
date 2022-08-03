package ksl.simulation

class Model internal constructor (name: String): ModelElement(name) {
//TODO why does a Model need to be a sub-class of ModelElement???
    /**
     * A Map that holds all the model elements in the order in which they are
     * created
     */
    private val myModelElementMap: Map<String, ModelElement> = LinkedHashMap()

    init {
        myModel = this
        myParentModelElement = null
        //TODO a whole lot to do
    }
}