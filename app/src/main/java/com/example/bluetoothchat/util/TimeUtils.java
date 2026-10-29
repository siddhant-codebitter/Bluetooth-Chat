package com.example.bluetoothchat.util;

import android.text.format.DateUtils;

import androidx.annotation.NonNull;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** Turns timestamps (milliseconds since 1970) into short text for the screens. */
public final class TimeUtils {

    private TimeUtils() {
    }

    /**
     * For the chat list. Today: the time ("3:45 PM"). Yesterday: "Yesterday".
     * Within the last week: the weekday ("Mon"). Older: the date ("12 Mar", or
     * "12 Mar 2025" for earlier years).
     */
    @NonNull
    public static String formatListTime(long timestamp) {
        if (timestamp <= 0) return "";

        ZoneId zone = ZoneId.systemDefault();
        Locale locale = Locale.getDefault();
        ZonedDateTime time = Instant.ofEpochMilli(timestamp).atZone(zone);
        LocalDate today = LocalDate.now(zone);
        long daysAgo = ChronoUnit.DAYS.between(time.toLocalDate(), today);

        if (daysAgo <= 0) { // today (or slightly in the future if clocks differ)
            return timeFormatter(locale).format(time);
        }
        if (daysAgo == 1) {
            return DateUtils.getRelativeTimeSpanString(
                    timestamp, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString();
        }
        if (daysAgo < 7) {
            return DateTimeFormatter.ofPattern("EEE", locale).format(time);
        }
        if (time.getYear() == today.getYear()) {
            return DateTimeFormatter.ofPattern("d MMM", locale).format(time);
        }
        return DateTimeFormatter.ofPattern("d MMM yyyy", locale).format(time);
    }

    /** For chat bubbles: just the time of day, e.g. "3:45 PM". */
    @NonNull
    public static String formatMessageTime(long timestamp) {
        if (timestamp <= 0) return "";
        ZonedDateTime time = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault());
        return timeFormatter(Locale.getDefault()).format(time);
    }

    @NonNull
    private static DateTimeFormatter timeFormatter(@NonNull Locale locale) {
        return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale);
    }
}