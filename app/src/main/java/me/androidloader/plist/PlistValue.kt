package me.androidloader.plist

/**
 * The subset of property-list types that appear in usbmuxd, lockdownd and
 * installation_proxy traffic.
 *
 * Apple distinguishes signed and unsigned integers at the binary-plist level but
 * a plist consumer only cares about the value, so both collapse onto [Int]/[Long]
 * here. Values that do not fit are widened to [Long]; anything wider still is
 * carried as [BigIntegerValue] rather than silently truncated.
 */
sealed interface PlistValue {
    data class BoolValue(val value: Boolean) : PlistValue

    data class IntValue(val value: Int) : PlistValue

    data class LongValue(val value: Long) : PlistValue

    data class UIntValue(val value: Long) : PlistValue

    data class BigIntValue(val value: java.math.BigInteger) : PlistValue

    data class DoubleValue(val value: Double) : PlistValue

    data class StringValue(val value: String) : PlistValue

    /** Raw `<data>` payload. */
    data class DataValue(val value: ByteArray) : PlistValue {
        override fun equals(other: Any?): Boolean =
            this === other || (other is DataValue && value.contentEquals(other.value))

        override fun hashCode(): Int = value.contentHashCode()
    }

    /** Property-list date, stored as seconds relative to the 2001-01-01 epoch. */
    data class DateValue(val epochSeconds: Double) : PlistValue

    data class ArrayValue(val value: List<PlistValue>) : PlistValue

    data class DictValue(val value: Map<String, PlistValue>) : PlistValue

    object UnknownValue : PlistValue
}

// Convenience accessors used throughout the protocol code. They keep call sites
// free of the `when` boilerplate and return null rather than throwing so that a
// response from a future iOS version degrades into a clear error at the layer
// that actually cares.

val PlistValue.asString: String?
    get() = (this as? PlistValue.StringValue)?.value

val PlistValue.asInt: Int?
    get() = when (this) {
        is PlistValue.IntValue -> value
        is PlistValue.LongValue -> value.toInt()
        is PlistValue.UIntValue -> value.toInt()
        is PlistValue.BoolValue -> if (value) 1 else 0
        is PlistValue.DoubleValue -> value.toInt()
        else -> null
    }

val PlistValue.asLong: Long?
    get() = when (this) {
        is PlistValue.LongValue -> value
        is PlistValue.UIntValue -> value
        is PlistValue.IntValue -> value.toLong()
        is PlistValue.BigIntValue -> value.toLong()
        is PlistValue.BoolValue -> if (value) 1L else 0L
        is PlistValue.DoubleValue -> value.toLong()
        else -> null
    }

val PlistValue.asBoolean: Boolean?
    get() = when (this) {
        is PlistValue.BoolValue -> value
        is PlistValue.IntValue -> value != 0
        is PlistValue.LongValue -> value != 0L
        is PlistValue.UIntValue -> value != 0L
        is PlistValue.StringValue -> value == "true"
        else -> null
    }

val PlistValue.asDouble: Double?
    get() = when (this) {
        is PlistValue.DoubleValue -> value
        is PlistValue.IntValue -> value.toDouble()
        is PlistValue.LongValue -> value.toDouble()
        is PlistValue.UIntValue -> value.toDouble()
        else -> null
    }

val PlistValue.asData: ByteArray?
    get() = (this as? PlistValue.DataValue)?.value

val PlistValue.asList: List<PlistValue>?
    get() = (this as? PlistValue.ArrayValue)?.value

val PlistValue.asDict: Map<String, PlistValue>?
    get() = (this as? PlistValue.DictValue)?.value

/** Builds a plist string, or null if this is not a string. */
fun Map<String, PlistValue>.string(key: String): String? = this[key]?.asString

/** Builds a plist integer, or null if this is not an integer. */
fun Map<String, PlistValue>.int(key: String): Int? = this[key]?.asInt

/** Builds a plist dictionary, or null if this is not a dictionary. */
fun Map<String, PlistValue>.dict(key: String): Map<String, PlistValue>? = this[key]?.asDict

/** Builds a plist array, or null if this is not an array. */
fun Map<String, PlistValue>.list(key: String): List<PlistValue>? = this[key]?.asList

fun plistString(value: String): PlistValue = PlistValue.StringValue(value)
fun plistInt(value: Int): PlistValue = PlistValue.IntValue(value)
fun plistLong(value: Long): PlistValue = PlistValue.LongValue(value)
fun plistData(value: ByteArray): PlistValue = PlistValue.DataValue(value)
fun plistBool(value: Boolean): PlistValue = PlistValue.BoolValue(value)
fun plistArray(values: List<PlistValue>): PlistValue = PlistValue.ArrayValue(values)
fun plistDict(values: Map<String, PlistValue>): PlistValue = PlistValue.DictValue(values)
