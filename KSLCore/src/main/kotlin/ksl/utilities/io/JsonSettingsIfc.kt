package ksl.utilities.io

/**
 *  The purpose of this interface is to provide classes (especially model elements)
 *  the ability to be configured from a Json string.
 */
interface JsonSettingsIfc<out T> {

    /**
     *  Returns the current settings in the form of the data type that
     *  can be serialized.
     */
    fun currentSettings() : T

    /**
     *  Uses the supplied JSON string to configure the object using the Json string
     *
     *  @param json a valid JSON encoded string representing the object
     */
    fun configureFromJson(json: String) : T

    /**
     *  Converts the configuration settings to JSON
     */
    fun settingsToJson() : String
}