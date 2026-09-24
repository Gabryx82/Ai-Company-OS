package com.aicompany.backend.catalog;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Tag lists stored as one comma-separated column. Normalised on the way in --
 * trimmed, lower case, de-duplicated, blanks dropped -- so that matching a task
 * against capabilities never depends on how somebody typed them.
 */
public final class Tags {

    private Tags() {
    }

    public static String join(Collection<String> tags) {
        if (tags == null) {
            return "";
        }
        return String.join(",", tags.stream()
                .filter(Objects::nonNull)
                .map(tag -> tag.strip().toLowerCase(Locale.ROOT).replace(',', ' ').strip())
                .filter(tag -> !tag.isEmpty())
                .distinct()
                .toList());
    }

    public static List<String> split(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        return Arrays.stream(stored.split(",")).map(String::strip).filter(tag -> !tag.isEmpty()).toList();
    }
}
