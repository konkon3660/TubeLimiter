package com.tubelimiter.app.data

import com.tubelimiter.app.limit.ScheduleWindow

/**
 * Tiny hand-rolled encodings for the few collections kept in DataStore.
 *
 * Deliberately dependency-free: `org.json` is a stub under unit tests, and pulling in a
 * serialization plugin for four fields is not worth it. Values here are date keys,
 * integers and milestone names, none of which can contain the separators.
 */

private const val ENTRY_SEPARATOR = ";"
private const val KEY_VALUE_SEPARATOR = "="

/** Second-level separators for the nested date->hour->millis map, distinct from the above. */
private const val HOUR_ENTRY_SEPARATOR = ","
private const val HOUR_VALUE_SEPARATOR = ":"

/**
 * Separators for [encodeScheduleWindows]/[decodeScheduleWindows]. Unlike the flat collections
 * above, a [ScheduleWindow]'s `label` is free text a user typed, so a visible character (comma,
 * semicolon, colon) is not safe as a delimiter - ASCII control characters that cannot come from
 * a normal text field are used instead (record separator between windows, unit separator
 * between a window's fields).
 */
private const val SCHEDULE_WINDOW_SEPARATOR = "\u001E"
private const val SCHEDULE_FIELD_SEPARATOR = "\u001F"

fun encodeLongMap(map: Map<String, Long>): String =
    map.entries.joinToString(ENTRY_SEPARATOR) { "${it.key}$KEY_VALUE_SEPARATOR${it.value}" }

fun decodeLongMap(raw: String?): Map<String, Long> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split(ENTRY_SEPARATOR).mapNotNull { entry ->
        val key = entry.substringBefore(KEY_VALUE_SEPARATOR, missingDelimiterValue = "")
        val value = entry.substringAfter(KEY_VALUE_SEPARATOR, missingDelimiterValue = "").toLongOrNull()
        if (key.isBlank() || value == null) null else key to value
    }.toMap()
}

fun encodeIntList(values: List<Int>): String = values.joinToString(",")

/** Falls back to [default] for any slot that is missing or malformed. */
fun decodeIntList(raw: String?, size: Int, default: Int): List<Int> {
    val parsed = raw?.split(",")?.map { it.trim().toIntOrNull() } ?: emptyList()
    return List(size) { index -> parsed.getOrNull(index) ?: default }
}

fun encodeStringSet(values: Set<String>): String = values.joinToString(",")

fun decodeStringSet(raw: String?): Set<String> {
    if (raw.isNullOrBlank()) return emptySet()
    return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}

fun encodeIntSet(values: Set<Int>): String = values.joinToString(",")

fun decodeIntSet(raw: String?): Set<Int> =
    decodeStringSet(raw).mapNotNull { it.toIntOrNull() }.toSet()

/** Keeps the usage map from growing without bound; the dashboard only reads recent days. */
fun pruneHistory(history: Map<String, Long>, keepKeys: Set<String>): Map<String, Long> =
    history.filterKeys { it in keepKeys }

/**
 * Encodes the hourly usage breakdown (`date -> hour(0-23) -> millis`) used for the dashboard's
 * time-of-day pattern. Nests a second delimiter pair inside each date entry so the outer
 * `encodeLongMap` separators stay unambiguous: `date=hour:ms,hour:ms;date=hour:ms`.
 */
fun encodeHourlyMap(map: Map<String, Map<Int, Long>>): String =
    map.entries.joinToString(ENTRY_SEPARATOR) { (date, hours) ->
        val hoursEncoded = hours.entries.joinToString(HOUR_ENTRY_SEPARATOR) { (hour, millis) ->
            "$hour$HOUR_VALUE_SEPARATOR$millis"
        }
        "$date$KEY_VALUE_SEPARATOR$hoursEncoded"
    }

fun decodeHourlyMap(raw: String?): Map<String, Map<Int, Long>> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split(ENTRY_SEPARATOR).mapNotNull { entry ->
        val date = entry.substringBefore(KEY_VALUE_SEPARATOR, missingDelimiterValue = "")
        if (date.isBlank()) return@mapNotNull null
        val hoursRaw = entry.substringAfter(KEY_VALUE_SEPARATOR, missingDelimiterValue = "")
        val hours = hoursRaw.split(HOUR_ENTRY_SEPARATOR).mapNotNull { hourEntry ->
            val hour = hourEntry.substringBefore(HOUR_VALUE_SEPARATOR, missingDelimiterValue = "").toIntOrNull()
            val millis = hourEntry.substringAfter(HOUR_VALUE_SEPARATOR, missingDelimiterValue = "").toLongOrNull()
            if (hour == null || millis == null) null else hour to millis
        }.toMap()
        date to hours
    }.toMap()
}

/** Same retention window as [pruneHistory], keyed the same way (by date). */
fun pruneHourlyHistory(
    history: Map<String, Map<Int, Long>>,
    keepKeys: Set<String>,
): Map<String, Map<Int, Long>> = history.filterKeys { it in keepKeys }

/**
 * Encodes the schedule editor's list of [ScheduleWindow]s. Richer than the flat collections
 * above (each entry has 6 fields including free text), so it gets its own field/window
 * separator pair rather than reusing [encodeIntList]'s comma.
 */
fun encodeScheduleWindows(windows: List<ScheduleWindow>): String =
    windows.joinToString(SCHEDULE_WINDOW_SEPARATOR) { window ->
        listOf(
            window.id,
            window.label,
            window.days.joinToString("") { if (it) "1" else "0" },
            window.startMinute.toString(),
            window.endMinute.toString(),
            if (window.enabled) "1" else "0",
        ).joinToString(SCHEDULE_FIELD_SEPARATOR)
    }

/** Drops any entry that is malformed (wrong field count, non-numeric minute, short day list). */
fun decodeScheduleWindows(raw: String?): List<ScheduleWindow> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(SCHEDULE_WINDOW_SEPARATOR).mapNotNull { entry ->
        val fields = entry.split(SCHEDULE_FIELD_SEPARATOR)
        if (fields.size < 6) return@mapNotNull null

        val days = fields[2].map { it == '1' }
        val startMinute = fields[3].toIntOrNull()
        val endMinute = fields[4].toIntOrNull()
        if (days.size < 7 || startMinute == null || endMinute == null) return@mapNotNull null

        ScheduleWindow(
            id = fields[0],
            label = fields[1],
            days = days,
            startMinute = startMinute,
            endMinute = endMinute,
            enabled = fields[5] == "1",
        )
    }
}
