package com.quantalgotrade.crypto.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateTimeFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm:ss a", Locale.ENGLISH)

/** Exact local date + time with seconds for order/trade ledgers. */
fun formatDateTime(iso: String?): String {
    if (iso.isNullOrBlank()) return "—"
    return try {
        val instant = Instant.parse(iso)
        dateTimeFmt.withZone(ZoneId.systemDefault()).format(instant)
    } catch (_: Exception) {
        try {
            // Backend sometimes omits zone; treat as UTC.
            val instant = Instant.parse(if (iso.endsWith("Z")) iso else "${iso}Z")
            dateTimeFmt.withZone(ZoneId.systemDefault()).format(instant)
        } catch (_: Exception) {
            iso
        }
    }
}
