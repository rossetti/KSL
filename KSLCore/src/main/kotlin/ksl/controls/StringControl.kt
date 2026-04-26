package ksl.controls

import ksl.simulation.ModelElement
import kotlin.reflect.KMutableProperty

internal class StringControl(
    private val myModelElement: ModelElement,
    private val myProperty:     KMutableProperty<*>,
    private val myAnnotation:   KSLStringControl,
) : StringControlIfc {

    override val initialValue: String  = myProperty.getter.call(myModelElement) as String
    override val allowedValues: List<String> = myAnnotation.allowedValues.toList()

    override var value: String
        get() = myProperty.getter.call(myModelElement) as String
        set(v) {
            if (!isAllowed(v)) {
                throw ControlUpdateException(
                    message        = "Value '$v' is not in allowedValues $allowedValues for control '$keyName'",
                    controlKey     = keyName,
                    attemptedValue = v,
                )
            }
            myProperty.setter.call(myModelElement, v)
            Controls.logger.trace { "StringControl: $keyName was assigned value = $v" }
        }

    override val keyName: String
        get() {
            val en = elementName.replace(".", "\\.")
            val sn = propertyName.replace(".", "\\.")
            return "$en.$sn"
        }

    override val elementName:  String get() = myModelElement.name
    override val elementId:    Int    get() = myModelElement.id
    override val elementType:  String get() = myModelElement::class.simpleName!!
    override val propertyName: String get() = myProperty.name
    override val comment:      String get() = myAnnotation.comment
    override val modelName:    String get() = myModelElement.model.name

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("[key = ").append(keyName)
        sb.append(", value = ").append(value)
        sb.append(", initialValue = ").append(initialValue)
        sb.append(", allowedValues = ").append(if (allowedValues.isEmpty()) "any" else allowedValues.toString())
        sb.append(", comment = ").append(if (comment.isEmpty()) "\"\"" else comment)
        sb.append(", element type = ").append(elementType)
        sb.append(", element name = ").append(elementName)
        sb.append(", model name = ").append(modelName)
        sb.append("]")
        return sb.toString()
    }
}
