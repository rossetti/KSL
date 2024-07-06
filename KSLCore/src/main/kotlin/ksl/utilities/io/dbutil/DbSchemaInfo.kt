package ksl.utilities.io.dbutil

/**
 *  A data class to hold meta-data information about the tables and the containing schema
 */
data class DbSchemaInfo(var catalogName: String?, var schemaName: String?, var tableName: String)