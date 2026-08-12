package com.ugnay.platform.warehouse;

import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict conversion from the catalogue's preserved academic-year label. */
public final class WarehouseYearParser {
    private static final Pattern SINGLE = Pattern.compile("^(\\d{4})$");
    private static final Pattern RANGE = Pattern.compile("^(\\d{4})-(\\d{4})$");

    private WarehouseYearParser() {}

    public static OptionalInt completionYear(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) return OptionalInt.empty();
        String value = rawValue.strip();
        Matcher single = SINGLE.matcher(value);
        if (single.matches()) {
            int year = Integer.parseInt(single.group(1));
            return supported(year) ? OptionalInt.of(year) : OptionalInt.empty();
        }
        Matcher range = RANGE.matcher(value);
        if (!range.matches()) return OptionalInt.empty();
        int start = Integer.parseInt(range.group(1));
        int end = Integer.parseInt(range.group(2));
        return supported(start) && supported(end) && end == start + 1
                ? OptionalInt.of(end) : OptionalInt.empty();
    }

    public static boolean isMissing(String rawValue) {
        return rawValue == null || rawValue.isBlank();
    }

    private static boolean supported(int year) {
        return year >= 1900 && year <= 2200;
    }
}
