package ksl.utilities.io

import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KVisibility
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

object DataClassUtil {

    /**
     *  Extracts the names of the public properties of a data class
     *  in the order in which they are declared in the primary constructor.
     *
     *  If the supplied object [data] is not an instance of a data class,
     *  then the returned list will be empty.
     */
    fun extractPropertyNames(data: Any) : List<String> {
        val cls: KClass<out Any> = data::class
        if (!cls.isData){
            return emptyList()
        }
        val list = mutableListOf<String>()
        val parameters: List<KParameter>? = cls.primaryConstructor?.parameters
        val pairs = extractProperties(data)
        if (parameters!= null){
            for(param in parameters){
                if (pairs.containsKey(param.name!!)){
                    list.add(param.name!!)
                }
            }
        }
        return list
    }

    /**
     *  Extracts the value of the public properties of a data class in the order
     *  in which they are declared in the primary constructor.
     *
     *  If the supplied object [data] is not an instance of a data class,
     *  then the returned list will be empty.
     */
    fun extractPropertyValues(data: Any) : List<Any?> {
        val cls: KClass<out Any> = data::class
        if (!cls.isData){
            return emptyList()
        }
        val list = mutableListOf<Any?>()
        val names = extractPropertyNames(data)
        val pairs = extractProperties(data)
        for(name in names){
            list.add(pairs[name])
        }
        return list
    }

    /**
     *  Extracts the value of the public properties of a data class
     *  If the supplied object [data] is not an instance of a data class,
     *  then the returned map will be empty. The map contains the pairs
     *  of (name, value) where name is the name of the public property
     *  and value is the current value of the property
     */
    fun extractProperties(data: Any) : Map<String, Any?> {
        val cls: KClass<out Any> = data::class
        if (!cls.isData){
            return emptyMap()
        }
        val map = mutableMapOf<String, Any?>()
        val properties: Collection<KProperty1<out Any, *>> = cls.memberProperties
        for (property in properties){
            if (property.visibility == KVisibility.PUBLIC){
                val v: Any? = property.getter.call(data)
                map[property.name] = v
            }
        }
        return map
    }
}