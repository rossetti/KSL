package ksl.utilities.io.dbutil

/**
 *  Represents column metadata extracted from database
 */
data class ColumnMetaData(
    val catalogName: String,
    val className: String,
    val label: String,
    val name: String,
    val typeName: String,
    val type: Int,
    val tableName: String,
    val schemaName: String,
    val isAutoIncrement: Boolean,
    val isCaseSensitive: Boolean,
    val isCurrency: Boolean,
    val isDefiniteWritable: Boolean,
    val isReadOnly: Boolean,
    val isSearchable: Boolean,
    val isReadable: Boolean,
    val isSigned: Boolean,
    val isWritable: Boolean,
    val nullable: Int
)