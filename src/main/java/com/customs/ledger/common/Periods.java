package com.customs.ledger.common;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Period helpers. A period is a calendar month in the ledger time zone, formatted {@code YYYY-MM}. */
public final class Periods {

    public static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private Periods() {
    }

    public static YearMonth parse(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(400, "INVALID_PERIOD", "period is required (format YYYY-MM)");
        }
        try {
            return YearMonth.parse(value.trim(), MONTH);
        } catch (DateTimeParseException e) {
            throw new ApiException(400, "INVALID_PERIOD", "period must use format YYYY-MM: " + value);
        }
    }

    public static String format(YearMonth period) {
        return period.format(MONTH);
    }

    public static YearMonth of(OffsetDateTime time, ZoneId zone) {
        return YearMonth.from(time.atZoneSameInstant(zone).toLocalDate());
    }

    public static LocalDate businessDate(OffsetDateTime time, ZoneId zone) {
        return time.atZoneSameInstant(zone).toLocalDate();
    }

    public static LocalDate endOf(YearMonth period) {
        return period.atEndOfMonth();
    }

    public static LocalDate startOf(YearMonth period) {
        return period.atDay(1);
    }

    public static YearMonth current(Clock clock, ZoneId zone) {
        return YearMonth.from(LocalDate.now(clock.withZone(zone)));
    }
}
