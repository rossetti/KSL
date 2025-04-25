package ksl.utilities.io

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface ToJSONIfc {
    fun toJson(): String
}