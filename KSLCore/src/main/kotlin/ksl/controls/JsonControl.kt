package ksl.controls

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import ksl.simulation.ModelElement
import ksl.simulation.ModelElementHierarchy
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KType

internal class JsonControl(
    private val myModelElement: ModelElement,
    private val myProperty:     KMutableProperty<*>,
    private val myAnnotation:   KSLJsonControl,
) : JsonControlIfc {

    private val myKType: KType = myProperty.returnType

    @Suppress("UNCHECKED_CAST")
    private val mySerializer: KSerializer<Any?> = serializer(myKType) as KSerializer<Any?>

    override val typeHint: String =
        myAnnotation.expectedTypeHint.ifEmpty { myKType.toString() }

    override val initialJsonValue: String =
        json.encodeToString(mySerializer, myProperty.getter.call(myModelElement))

    override var value: String
        get() = json.encodeToString(mySerializer, myProperty.getter.call(myModelElement))
        set(v) {
            try {
                val decoded = json.decodeFromString(mySerializer, v)
                myProperty.setter.call(myModelElement, decoded)
                Controls.logger.trace { "JsonControl: $keyName was assigned value = $v" }
            } catch (e: ControlUpdateException) {
                throw e
            } catch (e: SerializationException) {
                throw ControlUpdateException(
                    message        = "JSON deserialization failed for control '$keyName': ${e.message}",
                    controlKey     = keyName,
                    attemptedValue = v,
                    cause          = e,
                )
            } catch (e: Exception) {
                throw ControlUpdateException(
                    message        = "Property setter rejected deserialized value for control '$keyName': ${e.message}",
                    controlKey     = keyName,
                    attemptedValue = v,
                    cause          = e,
                )
            }
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

    override val parentElementName: String?
        get() = ModelElementHierarchy.parentElement(myModelElement)?.name
    override val parentElementId: Int?
        get() = ModelElementHierarchy.parentElement(myModelElement)?.id
    override val parentElementType: String?
        get() = ModelElementHierarchy.parentElement(myModelElement)?.let { it::class.simpleName }
    override val elementPath: List<String>
        get() = ModelElementHierarchy.elementPath(myModelElement)

    override fun toString(): String {
        val displayValue = value.let { if (it.length > 80) it.take(80) + "…" else it }
        val sb = StringBuilder()
        sb.append("[key = ").append(keyName)
        sb.append(", typeHint = ").append(typeHint)
        sb.append(", value = ").append(displayValue)
        sb.append(", initialJsonValue = ").append(initialJsonValue)
        sb.append(", comment = ").append(if (comment.isEmpty()) "\"\"" else comment)
        sb.append(", element type = ").append(elementType)
        sb.append(", element name = ").append(elementName)
        sb.append(", model name = ").append(modelName)
        sb.append("]")
        return sb.toString()
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
