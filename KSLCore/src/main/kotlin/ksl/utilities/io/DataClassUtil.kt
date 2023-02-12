package ksl.utilities.io

import kotlin.reflect.*
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.starProjectedType
import kotlin.reflect.full.withNullability
import kotlin.reflect.jvm.javaType

object DataClassUtil {

    /**
     *  Extracts the names of the public properties of a data class
     *  in the order in which they are declared in the primary constructor.
     *
     *  If the supplied object [data] is not an instance of a data class,
     *  then the returned list will be empty.
     */
    fun extractPropertyNames(data: Any): List<String> {
        val cls: KClass<out Any> = data::class
        if (!cls.isData) {
            return emptyList()
        }
        val list = mutableListOf<String>()
        val parameters: List<KParameter>? = cls.primaryConstructor?.parameters
        val pairs = extractMutableProperties(data)
        if (parameters != null) {
            for (param in parameters) {
                if (pairs.containsKey(param.name!!)) {
                    if (pairs[param.name!!] is KMutableProperty<*>) {
                        list.add(param.name!!)
                    }
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
    fun extractPropertyValues(data: Any): List<Any?> {
        val cls: KClass<out Any> = data::class
        if (!cls.isData) {
            return emptyList()
        }
        val list = mutableListOf<Any?>()
        val names = extractPropertyNames(data)
        val pairs = extractMutableProperties(data)
        for (name in names) {
            list.add(pairs[name])
        }
        return list
    }

    fun setPropertyValues(data: Any, values: List<Any?>) {
        val cls: KClass<out Any> = data::class
        if (!cls.isData) {
            return
        }
        val names = extractPropertyNames(data)
        // check the number of properties
        require(names.size == values.size) { "The data class has ${names.size} properties, but ${values.size} values were supplied" }
        val map = extractPropertyValuesName(data)
        // check the type of the properties
        for ((index, name) in names.withIndex()) {
            val obj1 = map[name]
            val obj2 = values[index]
            require(obj1!!::class == obj2!!::class) { "The type of property $name was not compatible with the corresponding value type" }
        }
        val properties = extractMutableProperties(data)
        for ((index, name) in names.withIndex()) {
            val property = properties[name]
            if (property is KMutableProperty<*>) {
                val p = property as KMutableProperty<*>
                p.setter.call(data, values[index])
            }
        }
    }

    /**
     *  Extracts the property of the public mutable properties of a data class
     *  If the supplied object [data] is not an instance of a data class,
     *  then the returned map will be empty. The map contains the pairs
     *  of (name, property) where name is the name of the public property
     *  and is the reflection property
     */
    fun extractMutableProperties(data: Any): Map<String, KProperty1<out Any, *>> {
        val cls: KClass<out Any> = data::class
        if (!cls.isData) {
            return emptyMap()
        }
        val map = mutableMapOf<String, KProperty1<out Any, *>>()
        val properties: Collection<KProperty1<out Any, *>> = cls.memberProperties
        for (property in properties) {
            if (property.visibility == KVisibility.PUBLIC) {
                if (property is KMutableProperty<*>) {
                    map[property.name] = property
                }
            }
        }
        return map
    }

    /**
     *  Extracts the value of the public mutable properties of a data class
     *  If the supplied object [data] is not an instance of a data class,
     *  then the returned map will be empty. The map contains the pairs
     *  of (name, value) where name is the name of the public property
     *  and value is the current value of the property
     */
    fun extractPropertyValuesName(data: Any): Map<String, Any?> {
        val cls: KClass<out Any> = data::class
        if (!cls.isData) {
            return emptyMap()
        }
        val map = mutableMapOf<String, Any?>()
        val properties: Collection<KProperty1<out Any, *>> = cls.memberProperties
        for (property in properties) {
            if (property.visibility == KVisibility.PUBLIC) {
                if (property is KMutableProperty<*>) {
                    val v: Any? = property.getter.call(data)
                    map[property.name] = v
                }
            }
        }
        return map
    }
}

open class DbData(val tableName: String, val autoInc: Boolean = false) {
    /**
     *  Extracts the names of the public, mutable properties of a data class
     *  in the order in which they are declared in the primary constructor.
     *
     *  If the object is not an instance of a data class,
     *  then the returned list will be empty.
     */
    fun extractPropertyNames(): List<String> {
        val cls: KClass<out Any> = this::class
        if (!cls.isData) {
            return emptyList()
        }
        val list = mutableListOf<String>()
        val parameters: List<KParameter>? = cls.primaryConstructor?.parameters
        val pairs = extractMutableProperties()
        if (parameters != null) {
            for (param in parameters) {
                if (pairs.containsKey(param.name!!)) {
                    if (pairs[param.name!!] is KMutableProperty<*>) {
                        list.add(param.name!!)
                    }
                }
            }
        }
        if (autoInc) {
            if (list.isNotEmpty()) {
                list.removeFirst()
            }
        }
        return list
    }

    /**
     *  Extracts the value of the public, mutable properties of a data class in the order
     *  in which they are declared in the primary constructor.
     *
     *  If the object is not an instance of a data class,
     *  then the returned list will be empty.
     */
    fun extractPropertyValues(): List<Any?> {
        val cls: KClass<out Any> = this::class
        if (!cls.isData) {
            return emptyList()
        }
        val list = mutableListOf<Any?>()
        val names = extractPropertyNames()
        val pairs = extractPropertyValuesByName()
        for (name in names) {
            list.add(pairs[name])
        }
        return list
    }

    /**
     * Sets the values of the public mutable properties of a data class to
     * the values supplied. If the object is not an instance of a data
     * class then nothing happens. The size of the supplied array must
     * be the same as the number of the mutable properties and
     * the type of each element in the supplied list must match the type
     * of the mutable property
     */
    fun setPropertyValues(values: List<Any?>) {
        val cls: KClass<out Any> = this::class
        if (!cls.isData) {
            return
        }
        val names = extractPropertyNames()
        // check the number of properties
        require(names.size == values.size) { "The data class has ${names.size} properties, but ${values.size} values were supplied" }
        val map = extractPropertyValuesByName()
        // check the type of the properties
//        for ((index, name) in names.withIndex()) {
//            val obj1 = map[name]
//            val obj2 = values[index]
//            if ((obj1 != null) && (obj2 != null)) {
//                println("$name -> obj1 type = (${obj1::class}) and obj2 type = (${obj2::class})")
//                println("obj1 ${obj1::class.starProjectedType}")
//                println("obj2 ${obj2::class.starProjectedType}")
//            } else if ((obj1 != null)){
//                println("$name -> obj1 type = (${obj1::class}) and obj2 is null")
//            } else if ((obj2 != null)){
//                println("$name -> obj1 is null and obj2 type is ${obj2::class}")
//            }
//            //           require(obj1!!::class == obj2!!::class) { "The type of property $name was not compatible with the corresponding value type" }
//        }
        val properties = extractMutableProperties()
        for ((index, name) in names.withIndex()) {
            val value = values[index]
            if (value != null){
                // get the property's return type
                val rt: KType? = properties[name]?.returnType
                if (rt != null){
                    val vc: KClass<out Any> = value::class
                    val vcKType = if (rt.isMarkedNullable){
                        vc.starProjectedType.withNullability(true)
                    } else {
                        vc.starProjectedType
                    }
                    require (rt == vcKType){ "The type ($rt) of property $name was not compatible with the corresponding type ($vcKType) of value $value at index $index" }
                }
            } else {
                // value was null, check if property was marked nullable
                // get the property's return type
                val rt: KType? = properties[name]?.returnType
                if (rt != null){
                    require(rt.isMarkedNullable){"The value at index $index was null and the property $name was not nullable"}
                }
            }
        }

        for ((index, name) in names.withIndex()) {
            val property = properties[name]
            if (property is KMutableProperty<*>) {
                val p = property as KMutableProperty<*>
                p.setter.call(this, values[index])
            }
        }
    }

    /**
     *  Extracts the property of the public mutable properties of a data class
     *  If the object is not an instance of a data class,
     *  then the returned map will be empty. The map contains the pairs
     *  of (name, property) where name is the name of the public property
     *  and is the reflection property
     */
    fun extractMutableProperties(): Map<String, KProperty1<out Any, *>> {
        val cls: KClass<out Any> = this::class
        if (!cls.isData) {
            return emptyMap()
        }
        val map = mutableMapOf<String, KProperty1<out Any, *>>()
        val properties: Collection<KProperty1<out Any, *>> = cls.memberProperties
        for (property in properties) {
            if (property.visibility == KVisibility.PUBLIC) {
                if (property is KMutableProperty<*>) {
                    map[property.name] = property
                }
            }
        }
        return map
    }

    /**
     *  Extracts the value of the public mutable properties of a data class
     *  If the object is not an instance of a data class,
     *  then the returned map will be empty. The map contains the pairs
     *  of (name, value) where name is the name of the public, mutable property
     *  and value is the current value of the property
     */
    fun extractPropertyValuesByName(): Map<String, Any?> {
        val cls: KClass<out Any> = this::class
        if (!cls.isData) {
            return emptyMap()
        }
        val map = mutableMapOf<String, Any?>()
        val properties: Collection<KProperty1<out Any, *>> = cls.memberProperties
        for (property in properties) {
            if (property.visibility == KVisibility.PUBLIC) {
                if (property is KMutableProperty<*>) {
                    val v: Any? = property.getter.call(this)
                    map[property.name] = v
                }
            }
        }
        return map
    }
}