package com.opennovel.reader.ui

import android.text.format.DateUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Live date-display preferences, mirrored from settings by the app container. */
object DateDisplay {
    @Volatile var relative: Boolean = true
    /** SimpleDateFormat pattern; blank means the locale default. */
    @Volatile var pattern: String = ""

    private fun absolute(millis: Long): String {
        val fmt = runCatching { SimpleDateFormat(pattern.ifBlank { "d MMM yyyy" }, Locale.getDefault()) }
            .getOrElse { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }
        return fmt.format(Date(millis))
    }

    /** "2 days ago" style when relative timestamps are on, else a plain date. */
    fun format(millis: Long): String {
        if (!relative) return absolute(millis)
        val days = ((System.currentTimeMillis() - millis) / 86_400_000L).toInt()
        return when {
            days <= 0 -> "Today"
            days == 1 -> "Yesterday"
            days < 30 -> "$days days ago"
            else -> absolute(millis)
        }
    }

    /** Minute-resolution relative time for history entries. */
    fun formatWithTime(millis: Long): String =
        if (relative) {
            DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        } else {
            SimpleDateFormat("${pattern.ifBlank { "d MMM yyyy" }} HH:mm", Locale.getDefault())
                .let { runCatching { it.format(Date(millis)) }.getOrElse { absolute(millis) } }
        }
}
