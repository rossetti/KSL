/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.controls

//import java.util.*
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.maps.KSLMaps
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KProperty1
import kotlin.reflect.full.cast
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.internal.impl.resolve.calls.inference.CapturedType

class Controls(aModel: Model) {

    private val myControls = mutableMapOf<String, Control<out Any>>()
    val model = aModel

    init {
        extractControls(model)
    }

    /**
     * Extracts all controls from every model element of the model
     * that has a control annotation.
     *
     * @param model the model for extraction
     */
    private fun extractControls(model: Model) {
        val elements = model.getModelElements()
        for (me in elements) {
            extractControls(me)
        }
    }

    /**
     * extract Controls for a modelElement
     *
     * @param modelElement the model element to extract from
     */
    private fun extractControls(modelElement: ModelElement) {
        val cls: KClass<out ModelElement> = modelElement::class
        val properties: Collection<KProperty1<out ModelElement, *>> = cls.memberProperties
        Control.logger.info{"Extracting controls for model element: ${modelElement.name}"}
        for (property in properties) {
            Control.logger.info{"Reviewing member property: ${property.name}"}
            if (property is KMutableProperty<*>) {
                Control.logger.info{"Member property, ${property.name}, is mutable property"}
                if (Control.hasControlAnnotation(property.setter)) {
                    Control.logger.info{"Member property, ${property.name}, setter has control annotations"}
                    val kslControl: KSLControl = Control.controlAnnotation(property.setter)!!
                    Control.logger.info{"Extracted annotation: $kslControl"}
                    if (kslControl.include) {
                        Control.logger.info{"Controls should include annotated setter: ${property.setter.name}"}
                        val clazz = kslControl.controlType.asClass()
                        Control.logger.info{"Making control of type ${clazz.simpleName} for property, ${property.setter.name} of model element ${modelElement.name}"}
                        val value: Any? = property.getter.call()
                        if (value != null){
                           val v: Any = value
                            val control: Control<out Any> = Control(clazz, v, modelElement, property.setter)
                            Control.logger.info{"Constructed control: $control"}
                            store(control)
                            Control.logger.info(
                                "Control {} from method {} was extracted and added to controls for model: {}",
                                control.key, property.setter.name, model.name
                            )
                        }

                    } else {
                        Control.logger.info(
                            "Control {} from method {} was excluded during extraction for model: {}",
                            kslControl.name, property.setter.name, model.name
                        )
                    }
                } else{
                    Control.logger.info{"Member property, ${property.name}, has has no control annotations"}
                }
            } else{
                Control.logger.info{"Member property, ${property.name}, reported as not a mutable property"}
            }
        }
    }

    /**
     * Store a new control
     *
     * @param control the control to add
     */
    private fun store(control: Control<out Any>): Control<out Any> {
        return myControls.put(control.key, control)!!
    }

    /**
     * @return the control keys as an unmodifiable set of strings
     */
    fun controlKeys(): Set<String> {
        return myControls.keys
    }

    /**
     *
     * @param name the control name to check
     * @return true if the named control is in the controls
     */
    fun hasControl(name: String): Boolean {
        return myControls.containsKey(name)
    }

    /**
     * The class type should be associated with a valid control type. For example,
     * List&lt;Control&lt;Double&gt;&gt; list = getControls(Control&lt;Double&gt;.class)
     *
     * @param clazz the type of control wanted, must not be null.
     * @return a list of the controls associated with the supplied type, may be empty
     */
    fun <T : Any> getControls(clazz: KClass<Control<T>>): List<Control<T>> {
        if (!ControlType.classTypesToValidTypesMap.containsKey(clazz)) {
            return ArrayList()
        }
        val type = ControlType.classTypesToValidTypesMap[clazz]
        val list: MutableList<Control<T>> = ArrayList()
        for ((_, v) in myControls) {
            if (v.annotationType === type) {
                try {
                    list.add(clazz.cast(v))
                } catch (ignored: ClassCastException) {
                }
            }
        }
        return list
    }

    /**
     * Gets a control of the name with the specific class type. For example,
     * Control&lt;Double&gt; getControl(name, Control&lt;Double&gt;.class);
     *
     * @param controlKey the key for the control, must not be null
     * @param clazz      the class type for the control
     * @return the control or null if the key does not exist as a control or if
     * the control with the name cannot be cast to T
     */
    fun <T : Any> getControl(controlKey: String, clazz: Class<Control<T>>): Control<T>? {
        try {
            val v = myControls[controlKey]
            return if (v == null) {
                null
            } else {
                clazz.cast(v)
            }
        } catch (ignored: ClassCastException) {
        }
        return null
    }

    /**
     * @return the set of possible control types held
     */
    fun getControlTypes(): Set<ControlType> {
        val set: MutableSet<ControlType> = HashSet()
        for ((_, value) in myControls) {
            set.add(value.annotationType)
        }
        return set
    }

    /**
     * Generate a "flat" map (String, Double) for communication
     * outside this class. The key is the control key and the
     * number is the last double value assigned to the control.
     * Any controls that cannot be translated to Double are ignored.
     *
     * @return the map
     */
    fun getControlsAsDoubles(): Map<String, Double> {
        val map: MutableMap<String, Double> = LinkedHashMap()
        for ((key, c) in myControls) {
            if (c.isDoubleCompatible()) {
                map[key] = c.lastValueAsDouble()
            }
        }
        return map
    }

    /**
     * Sets all the contained control values using the supplied flat map
     *
     * @param controlMap a flat map of control keys and values, must not be null
     * @return the number of control (key, value) pairs that were successfully set
     */
    fun setControlsAsDoubles(controlMap: Map<String, Double>): Int {
        var j = 0
        for ((k, v) in controlMap.entries) {
            if (myControls.containsKey(k)) {
                val c = myControls[k]
                c!!.setValue(v)
                j++
            } else {
                Control.logger.warn{"The key $k was not found when trying to set control values for supplied flat map"}
            }
        }
        return j
    }

    /**
     *
     * @param json a valid json string representing a Map&lt;String, Double&gt;
     * that contains the control keys and double values for the controls
     * @return the number of control (key, value) pairs that were successfully set
     */
    fun setControlsAsDoubles(json: String): Int {
        return setControlsAsDoubles(KSLMaps.stringDoubleMapFromJson(json))
    }

    /**
     * Return an ArrayList of ControlDetailsRecords providing
     * additional detail on Controls (but without giving
     * direct access to the control)
     *
     * @return an ArrayList of ControlRecords
     */
    fun controlRecords(): ArrayList<ControlRecord> {
        val list = ArrayList<ControlRecord>()
        for ((_, value) in myControls) {
            list.add(value.getControlRecord())
        }
        return list
    }

    /**
     * @return the array list of getControlRecords() as a string
     */
    fun controlRecordsAsString(): String {
        val str = StringBuilder()
        val list = controlRecords()
        if (list.size == 0) str.append("{empty}")
        for (cdr in list) {
            str.append(cdr)
            str.append(System.lineSeparator())
        }
        return str.toString()
    }
}