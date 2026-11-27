package io.github.payal1762005.minisql.model;

import java.util.Objects;
import java.util.Optional;

/**
 * A selected column name.
 */
public record ColumnReference(String columnName, Optional<String> alias) implements SelectItem {
    public ColumnReference {
        if (columnName == null || columnName.isBlank()) {
            throw new IllegalArgumentException("Column name must not be blank");
        }
        Objects.requireNonNull(alias, "Column alias optional must not be null");
        alias.ifPresent(value -> {
            if (value.isBlank()) {
                throw new IllegalArgumentException("Column alias must not be blank");
            }
        });
    }

    public ColumnReference(String columnName) {
        this(columnName, Optional.empty());
    }

    public ColumnReference(String columnName, String alias) {
        this(columnName, Optional.of(Objects.requireNonNull(alias, "Column alias must not be null")));
    }
}
