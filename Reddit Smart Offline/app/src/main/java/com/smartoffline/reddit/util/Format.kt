package com.smartoffline.reddit.util

import android.text.format.DateUtils
import java.text.DateFormat
import java.util.Date
import java.util.Locale

object Format {
    fun bytes(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", b / 1024.0)
        b < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", b / 1024.0 / 1024)
        else -> String.format(Locale.US, "%.2f GB", b / 1024.0 / 1024 / 1024)
    }

    fun count(n: Long): String = when {
        n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
        n >= 10_000 -> String.format(Locale.US, "%.0fk", n / 1000.0)
        n >= 1000 -> String.format(Locale.US, "%.1fk", n / 1000.0)
        else -> n.toString()
    }

    fun ago(ms: Long): String = if (ms <= 0) "Never" else
        DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

    fun date(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

    fun interval(hours: Int): String = when {
        hours % 168 == 0 -> if (hours == 168) "Every week" else "Every ${hours / 168} weeks"
        hours % 24 == 0 -> if (hours == 24) "Every day" else "Every ${hours / 24} days"
        else -> if (hours == 1) "Every hour" else "Every $hours hours"
    }

    fun inFuture(ms: Long): String {
        val d = ms - System.currentTimeMillis()
        if (d <= 0) return "due now"
        val h = d / 3_600_000
        return when {
            h >= 48 -> "in about ${h / 24} days"
            h >= 1 -> "in about $h h"
            else -> "in about ${(d / 60_000).coerceAtLeast(1)} min"
        }
    }
}
