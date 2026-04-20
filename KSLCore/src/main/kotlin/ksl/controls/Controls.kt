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

import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.modeling.variable.Response
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.collections.KSLMaps
import ksl.utilities.collections.toJson
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

/**
 * Holds all controls associated with a model instance, across three parallel
 * families:
 *
 * - **Numeric controls** — properties annotated with [KSLControl]; values are
 *   `Double` and respect declared lower/upper bounds.
 * - **String controls** — properties annotated with [KSLStringControl]; values
 *   are `String`, optionally constrained to a declared set of allowed values.
 *
 * Controls are extracted in a single element-graph walk performed during
 * `init`, which runs when the caller first calls `model.controls()`.  This
 * guarantees all model elements are fully constructed before any `initialValue`
 * is captured.
 *
 * Commonly used functions:
 *
 * Numeric:
 * - `controlKeys()` — names of the numeric controls
 * - `control(key)` — returns the named [ControlIfc] or null
 * - `asMap()` — flat `Map<String, Double>` of all numeric controls
 * - `setControlsFromMap(map)` — bulk-set numeric controls from a flat map
 * - `controlData()` — list of [ControlData] DTOs for data transfer
 *
 * String:
 * - `stringControlKeys()` — names of the string controls
 * - `stringControl(key)` — returns the named [StringControlIfc] or null
 * - `stringControlsAsMap()` — flat `Map<String, String>` of all string controls
 * - `setStringControlsFromMap(map)` — bulk-set string controls; invalid values
 *   are logged and skipped rather than aborting the entire update
 * - `stringControlData()` — list of [StringControlData] DTOs for data transfer
 */
class Controls(aModel: Model) {

    private val myControls = mutableMapOf<String, ControlIfc>()
    private val myStringControls = mutableMapOf<String, StringControlIfc>()

    private val myModel = aModel

    /** Number of numeric/boolean controls extracted from the model. */
    val size: Int
        get() = myControls.size

    /** Number of string controls extracted from the model. */
    val stringControlSize: Int
        get() = myStringControls.size

    init {
        extractControls(myModel)
    }

    // ── Extraction ────────────────────────────────────────────────────────────

    private fun extractControls(model: Model) {
        for (me in model.getModelElements()) {
            extractControls(me)
        }
    }

    private fun extractControls(modelElement: ModelElement) {
        val cls: KClass<out ModelElement> = modelElement::class
        val properties: Collection<KProperty1<out ModelElement, *>> = cls.memberProperties
        logger.trace { "Extracting controls for model element: ${modelElement.name}" }
        for (property in properties) {
            logger.trace { "Reviewing member property: ${property.name}" }

            // Guard: Response inherits an @KSLControl on initialValue from Variable,
            // but initialValue has no meaningful control semantics for Response.
            if (modelElement::class == Response::class && property.name == "initialValue") {
                logger.trace { "Skipping inherited initialValue for Response: ${modelElement.name}" }
                continue
            }

            if (property !is KMutableProperty<*>) {
                logger.trace { "Member property ${property.name} is not mutable — skipping" }
                continue
            }

            when {
                hasControlAnnotation(property.setter) -> {
                    val annotation = controlAnnotation(property.setter)!!
                    if (ControlType.validType(property.returnType)) {
                        if (annotation.include) {
                            val control = Control(modelElement, property, annotation)
                            myControls[control.keyName] = control
                            logger.trace { "Numeric control ${control.keyName} extracted" }
                        } else {
                            logger.trace { "Numeric control on ${property.name} excluded (include=false)" }
                        }
                    } else {
                        logger.trace { "Property ${property.name} has @KSLControl but type ${property.returnType.classifier} is not a valid ControlType" }
                    }
                }

                hasStringControlAnnotation(property.setter) -> {
                    val annotation = stringControlAnnotation(property.setter)!!
                    if (property.returnType.classifier == String::class) {
                        if (annotation.include) {
                            val control = StringControl(modelElement, property, annotation)
                            myStringControls[control.keyName] = control
                            logger.trace { "String control ${control.keyName} extracted" }
                        } else {
                            logger.trace { "String control on ${property.name} excluded (include=false)" }
                        }
                    } else {
                        logger.trace { "Property ${property.name} has @KSLStringControl but type ${property.returnType.classifier} is not String — skipping" }
                    }
                }

                else -> {
                    logger.trace { "Member property ${property.name} has no recognised control annotation" }
                }
            }
        }
    }

    // ── Numeric control accessors ─────────────────────────────────────────────

    /** Returns the set of numeric control key names. */
    fun controlKeys(): Set<String> = myControls.keys

    /**
     * Returns `true` if a numeric control with [name] exists.
     */
    fun hasControl(name: String): Boolean = myControls.containsKey(name)

    /**
     * Returns the numeric control for [controlKey], or `null` if not found.
     */
    fun control(controlKey: String): ControlIfc? = myControls[controlKey]

    /** Returns all numeric controls as a list. */
    fun asList(): List<ControlIfc> = myControls.values.toList()

    /**
     * Returns all numeric controls of the given [controlType].
     */
    fun asListByType(controlType: ControlType): List<ControlIfc> =
        myControls.values.filter { it.type == controlType }

    /**
     * Returns a map from model element name to the list of numeric controls
     * belonging to that element.
     */
    fun controlsByModelElement(): Map<String, List<ControlIfc>> {
        val map = mutableMapOf<String, MutableList<ControlIfc>>()
        for (control in myControls.values) {
            map.getOrPut(control.elementName) { mutableListOf() }.add(control)
        }
        return map
    }

    /**
     * Returns a map from model element type (simple class name) to the list of
     * numeric controls belonging to elements of that type.
     */
    fun controlsByElementType(): Map<String, List<ControlIfc>> {
        val map = mutableMapOf<String, MutableList<ControlIfc>>()
        for (control in myControls.values) {
            map.getOrPut(control.elementType) { mutableListOf() }.add(control)
        }
        return map
    }

    /** Returns the set of [ControlType] values present in the extracted controls. */
    fun controlTypes(): Set<ControlType> = myControls.values.map { it.type }.toSet()

    /** Prints all numeric controls as `keyName = value` pairs. */
    fun printControls() {
        for (control in myControls.values) {
            println("${control.keyName}  = ${control.value}")
        }
    }

    /**
     * Returns a flat `Map<String, Double>` of all numeric controls.
     * The key is [ControlIfc.keyName] and the value is the current control value.
     */
    fun asMap(): Map<String, Double> {
        val map = LinkedHashMap<String, Double>()
        for ((key, control) in myControls) {
            map[key] = control.value
        }
        return map
    }

    /**
     * Sets numeric controls from a flat map of key → value pairs.
     * Keys not found in the extracted controls are logged as warnings and skipped.
     *
     * @return the number of controls successfully set
     */
    fun setControlsFromMap(controlMap: Map<String, Double>): Int {
        var count = 0
        for ((k, v) in controlMap) {
            val control = myControls[k]
            if (control != null) {
                control.value = v
                count++
            } else {
                logger.warn { "Key '$k' not found when setting numeric controls from map" }
            }
        }
        return count
    }

    /**
     * Sets numeric controls from a JSON string representing a `Map<String, Double>`.
     *
     * @return the number of controls successfully set
     */
    fun setControlsFromJson(json: String): Int =
        setControlsFromMap(KSLMaps.stringDoubleMapFromJson(json))

    /** Returns a JSON string representation of the flat numeric controls map. */
    fun controlsMapAsJsonString(): String = asMap().toJson()

    /**
     * Returns a list of [ControlData] DTOs for all numeric controls.
     */
    fun controlData(): List<ControlData> {
        val list = ArrayList<ControlData>()
        for (control in myControls.values) {
            with(control) {
                list.add(ControlData(type, value, keyName, lowerBound, upperBound,
                    elementName, elementId, elementType, propertyName, comment, modelName))
            }
        }
        return list
    }

    /** Returns the numeric control data as a formatted string. */
    fun controlDataAsString(): String {
        val sb = StringBuilder()
        val list = controlData()
        if (list.isEmpty()) sb.append("{empty}")
        for (cd in list) sb.appendLine(cd)
        return sb.toString()
    }

    // ── String control accessors ──────────────────────────────────────────────

    /** Returns the set of string control key names. */
    fun stringControlKeys(): Set<String> = myStringControls.keys

    /**
     * Returns `true` if a string control with [name] exists.
     */
    fun hasStringControl(name: String): Boolean = myStringControls.containsKey(name)

    /**
     * Returns the string control for [key], or `null` if not found.
     */
    fun stringControl(key: String): StringControlIfc? = myStringControls[key]

    /** Returns all string controls as a list. */
    fun stringControlsAsList(): List<StringControlIfc> = myStringControls.values.toList()

    /**
     * Returns a flat `Map<String, String>` of all string controls.
     * The key is [StringControlIfc.keyName] and the value is the current control value.
     */
    fun stringControlsAsMap(): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for ((key, control) in myStringControls) {
            map[key] = control.value
        }
        return map
    }

    /**
     * Sets string controls from a flat map of key → value pairs.
     *
     * Invalid values (rejected by [StringControlIfc.isAllowed]) are caught
     * per entry, logged as warnings, and skipped — the remaining valid entries
     * continue to be applied.
     *
     * @return the number of controls successfully set
     */
    fun setStringControlsFromMap(map: Map<String, String>): Int {
        var count = 0
        for ((k, v) in map) {
            val control = myStringControls[k]
            if (control == null) {
                logger.warn { "Key '$k' not found when setting string controls from map" }
                continue
            }
            try {
                control.value = v
                count++
            } catch (e: ControlUpdateException) {
                logger.warn { "String control '$k' rejected value '$v': ${e.message}" }
            }
        }
        return count
    }

    /**
     * Returns a list of [StringControlData] DTOs for all string controls.
     */
    fun stringControlData(): List<StringControlData> {
        val list = ArrayList<StringControlData>()
        for (control in myStringControls.values) {
            with(control) {
                list.add(StringControlData(keyName, value, allowedValues,
                    elementName, elementId, elementType, propertyName, comment, modelName))
            }
        }
        return list
    }

    /** Returns the string control data as a formatted string. */
    fun stringControlDataAsString(): String {
        val sb = StringBuilder()
        val list = stringControlData()
        if (list.isEmpty()) sb.append("{empty}")
        for (sd in list) sb.appendLine(sd)
        return sb.toString()
    }

    // ── toString ──────────────────────────────────────────────────────────────

    override fun toString(): String = buildString {
        append(controlDataAsString())
        append(stringControlDataAsString())
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        @JvmStatic
        val logger = KotlinLogging.logger {}

        @JvmStatic
        fun <T> controlAnnotation(setter: KMutableProperty.Setter<T>): KSLControl? =
            setter.annotations.filterIsInstance<KSLControl>().firstOrNull()

        @JvmStatic
        fun <T> hasControlAnnotation(setter: KMutableProperty.Setter<T>): Boolean =
            setter.annotations.filterIsInstance<KSLControl>().isNotEmpty()

        @JvmStatic
        fun <T> stringControlAnnotation(setter: KMutableProperty.Setter<T>): KSLStringControl? =
            setter.annotations.filterIsInstance<KSLStringControl>().firstOrNull()

        @JvmStatic
        fun <T> hasStringControlAnnotation(setter: KMutableProperty.Setter<T>): Boolean =
            setter.annotations.filterIsInstance<KSLStringControl>().isNotEmpty()
    }
}
