package io.github.payal1762005.minisql.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable row represented by column names and their string values.
 */
public record Row(Map<String, String> values) {
    public Row {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(values, "Row values must not be null")));
    }
}
